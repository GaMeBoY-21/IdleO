//! What finished sessions leave behind (ADR 0035): a job's processes, containers, scratch disks, mounts, loop devices, encrypted
//! volumes (ADR 0038), files and session networks must all be gone once it has ended, whatever it did to try to stay (hostile test 12). The check reads the
//! distribution itself, not what the runner believes it did, and tells what a session running now holds (its container, its
//! scratch disk, its network) from what is left.

use std::collections::HashSet;
use std::path::Path;
use std::sync::PoisonError;
use std::time::Duration;

use super::{Nerdctl, SLOTS, nerdctl, run};
use crate::{crypto, sandbox};

/// The encrypted volumes open now that the runner made: the device-mapper names in `folder` (`/dev/mapper`) with its prefix.
pub(super) fn open_mappers(folder: &Path) -> Vec<String> {
    let mut names: Vec<String> = std::fs::read_dir(folder)
        .map(|entries| {
            entries
                .flatten()
                .map(|entry| entry.file_name().to_string_lossy().into_owned())
                .filter(|name| name.starts_with(crypto::MAPPER_PREFIX))
                .collect()
        })
        .unwrap_or_default();
    names.sort();
    names
}

/// A container's systemd scope: `nerdctl-<64 hex>.scope`, the cgroup its processes live in.
fn scope_of(cgroup: &str) -> Option<&str> {
    let start = cgroup.find("nerdctl-")? + "nerdctl-".len();
    let id = cgroup.get(start..start + 64)?;
    (id.bytes().all(|b| b.is_ascii_hexdigit()) && cgroup[start + 64..].starts_with(".scope")).then_some(id)
}

/// The scratch disks' keys that `losetup -a` shows a loop device on: `/dev/loop3: [64768]:12 (/var/lib/idleo/scratch/<key>.img)`.
fn loop_keys<'a>(losetup: &'a str, scratch_folder: &Path) -> Vec<&'a str> {
    let folder = format!("{}/", scratch_folder.display());
    losetup
        .lines()
        .filter_map(|line| {
            let backing = line.rsplit_once('(')?.1.strip_suffix(')')?;
            backing.strip_prefix(&folder)?.strip_suffix(".img")
        })
        .collect()
}

/// The mount points under `folder` (the scratch disks' mount folder), by the name of the first folder below it.
fn mounted_keys<'a>(mounts: &'a str, folder: &Path) -> Vec<&'a str> {
    mounts
        .lines()
        .filter_map(|line| {
            let point = line.split_whitespace().nth(1)?;
            let below = Path::new(point).strip_prefix(folder).ok()?;
            below.components().next()?.as_os_str().to_str()
        })
        .collect()
}

/// "1 process", "3 mounts".
fn count(number: usize, one: &str, many: &str) -> Option<String> {
    match number {
        0 => None,
        1 => Some(format!("1 {one}")),
        _ => Some(format!("{number} {many}")),
    }
}

impl Nerdctl {
    /// What no session running now holds: containers in the runner's namespace, processes in a container's cgroup, scratch
    /// disks mounted, loop devices on them, files and folders where they live, and session bridges. One phrase for each kind
    /// there is any of; empty when nothing is left.
    pub(super) async fn what_is_left(&self) -> Vec<String> {
        let held_containers: HashSet<String> =
            self.held_containers.lock().unwrap_or_else(PoisonError::into_inner).clone();
        let held_scratch: HashSet<String> = self.held_scratch.lock().unwrap_or_else(PoisonError::into_inner).clone();
        let taken_slots = self.slots.lock().unwrap_or_else(PoisonError::into_inner).clone();
        let scratch_folder = self.paths.state.join("scratch");
        let mount_folder = self.paths.state.join("mnt");
        let mut left = Vec::new();

        let containers = nerdctl(&["ps", "--all", "--quiet", "--no-trunc", "--filter", "label=idleo.session"])
            .await
            .map_or(0, |out| out.stdout.split_whitespace().filter(|id| !held_containers.contains(*id)).count());
        left.extend(count(containers, "container", "containers"));

        let mut processes = 0;
        if let Ok(entries) = std::fs::read_dir("/proc") {
            for entry in entries.flatten() {
                if !entry.file_name().to_string_lossy().bytes().all(|b| b.is_ascii_digit()) {
                    continue;
                }
                let cgroup = std::fs::read_to_string(entry.path().join("cgroup")).unwrap_or_default();
                if scope_of(&cgroup).is_some_and(|id| !held_containers.contains(id)) {
                    processes += 1;
                }
            }
        }
        left.extend(count(processes, "process", "processes"));

        let mounts = std::fs::read_to_string("/proc/mounts").unwrap_or_default();
        let mounted =
            mounted_keys(&mounts, &mount_folder).into_iter().filter(|key| !held_scratch.contains(*key)).count();
        left.extend(count(mounted, "mount", "mounts"));

        let losetup =
            run("losetup", &["-a"], None, Duration::from_secs(10)).await.map(|out| out.stdout).unwrap_or_default();
        let looped =
            loop_keys(&losetup, &scratch_folder).into_iter().filter(|key| !held_scratch.contains(*key)).count();
        left.extend(count(looped, "loop device", "loop devices"));

        let volumes = open_mappers(&self.paths.mapper)
            .iter()
            .filter(|name| !held_scratch.contains(name.strip_prefix(crypto::MAPPER_PREFIX).unwrap_or(name)))
            .count();
        left.extend(count(volumes, "encrypted volume", "encrypted volumes"));

        let mut files = 0;
        for (folder, suffix) in [(&scratch_folder, ".img"), (&mount_folder, "")] {
            if let Ok(entries) = std::fs::read_dir(folder) {
                files += entries
                    .flatten()
                    .filter(|entry| {
                        let name = entry.file_name().to_string_lossy().into_owned();
                        !held_scratch.contains(name.strip_suffix(suffix).unwrap_or(&name))
                    })
                    .count();
            }
        }
        left.extend(count(files, "scratch file", "scratch files"));

        // Session networks. The 32 bridges (`idl<N>`) are prepared once and reused, as the 32 CNI configs are: an empty one is not a
        // session's, and the distribution has no `ip` to take it down. What is a session's is what it attached to the network: its
        // container's virtual interface still plugged into a bridge of a slot nobody holds, and its address lease (host-local IPAM
        // keeps one file for each, named by the address) in a slot nobody holds.
        let (mut ports, mut leases) = (0, 0);
        for slot in 0..SLOTS {
            if taken_slots.contains(&slot) {
                continue;
            }
            ports += std::fs::read_dir(format!("/sys/class/net/{}{slot}/brif", sandbox::BRIDGE_PREFIX))
                .map_or(0, |entries| entries.flatten().count());
            leases += std::fs::read_dir(self.paths.cni_state.join(format!("idleo-slot-{slot}"))).map_or(0, |entries| {
                entries
                    .flatten()
                    .filter(|entry| entry.file_name().to_string_lossy().parse::<std::net::IpAddr>().is_ok())
                    .count()
            });
        }
        left.extend(count(ports, "session network interface", "session network interfaces"));
        left.extend(count(leases, "network address lease", "network address leases"));
        left
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_containers_scope_is_found_in_its_cgroup_and_nothing_else_is() {
        let id = "ab".repeat(32);
        assert_eq!(scope_of(&format!("0::/system.slice/nerdctl-{id}.scope\n")), Some(id.as_str()));
        assert_eq!(scope_of("0::/system.slice/containerd.service\n"), None);
        assert_eq!(scope_of(&format!("0::/system.slice/nerdctl-{}.scope", "zz".repeat(32))), None, "not hex");
        assert_eq!(scope_of("0::/system.slice/nerdctl-abcd.scope"), None, "too short");
    }

    #[test]
    fn loop_devices_and_mounts_on_scratch_disks_are_read_by_their_keys() {
        let losetup = "/dev/loop0: [64768]:12 (/var/lib/idleo/scratch/s1-1.img)\n/dev/loop1: [64768]:13 (/usr/share/other.img)\n/dev/loop2: [64768]:14 (/var/lib/idleo/scratch/s2-1.img)\n";
        assert_eq!(loop_keys(losetup, Path::new("/var/lib/idleo/scratch")), ["s1-1", "s2-1"]);
        let mounts = "/dev/loop0 /var/lib/idleo/mnt/s1-1 ext4 rw 0 0\nproc /proc proc rw 0 0\n/dev/loop2 /var/lib/idleo/mnt/s2-1/work ext4 rw 0 0\n";
        assert_eq!(mounted_keys(mounts, Path::new("/var/lib/idleo/mnt")), ["s1-1", "s2-1"]);
    }

    #[test]
    fn the_runners_encrypted_volumes_are_found_by_their_prefix_and_nothing_else_is() {
        let folder = tempfile::tempdir().unwrap();
        for name in ["control", "idleo-s1-1", "idleo-selfcheck-scratch", "other-volume"] {
            std::fs::write(folder.path().join(name), b"").unwrap();
        }
        assert_eq!(open_mappers(folder.path()), ["idleo-s1-1", "idleo-selfcheck-scratch"]);
        assert_eq!(open_mappers(&folder.path().join("missing")), Vec::<String>::new());
    }

    #[test]
    fn what_is_left_is_said_in_the_plural_only_when_it_is() {
        assert_eq!(count(0, "process", "processes"), None);
        assert_eq!(count(1, "process", "processes").as_deref(), Some("1 process"));
        assert_eq!(count(3, "mount", "mounts").as_deref(), Some("3 mounts"));
    }
}

//! The binary's command line: what it refuses before reading any config or opening a connection.

use std::process::Command;

fn run(args: &[&str]) -> (Option<i32>, String) {
    let output = Command::new(env!("CARGO_BIN_EXE_idleo-agent")).args(args).output().unwrap();
    (output.status.code(), String::from_utf8_lossy(&output.stderr).into_owned())
}

/// A fake GPU's model and memory are development settings: they're refused without `--fake-gpu`.
#[test]
fn the_fake_gpus_model_and_memory_need_fake_gpus() {
    for args in
        [["run", "--fake-gpu-model", "RTX 4060 Laptop"].as_slice(), ["run", "--fake-gpu-vram-mb", "8188"].as_slice()]
    {
        let (code, stderr) = run(args);
        assert_eq!(code, Some(2), "{args:?}: {stderr}");
        assert!(stderr.contains("--fake-gpu"), "{stderr}");
    }
}

/// `link` takes the same development flags, under the same rule.
#[test]
fn link_refuses_a_fake_gpu_model_without_fake_gpus() {
    let (code, stderr) = run(&["link", "--fake-gpu-model", "RTX 4060 Laptop"]);
    assert_eq!(code, Some(2), "{stderr}");
    assert!(stderr.contains("--fake-gpu"), "{stderr}");
}

#[test]
fn a_fake_gpu_needs_at_least_a_gigabyte() {
    let (code, stderr) = run(&["run", "--fake-gpu", "--fake-gpu-vram-mb", "512"]);
    assert_eq!(code, Some(2), "{stderr}");
    assert!(stderr.contains("512"), "{stderr}");
}

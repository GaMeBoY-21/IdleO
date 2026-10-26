//! Where the scanner may connect for a renter's reference (ADR 0042): public addresses only. A reference names a registry the
//! renter chose, so without this a reference could point the scanner at our own network (the database, the control plane's
//! other ports, a cloud's metadata address). Every name is resolved here and only public addresses are returned, which covers
//! redirects and a name that resolves differently the second time; an address written as the host is checked the same way,
//! on every request and every redirect. The development stack's registry is a private address, so `SCANNER_ALLOW_PRIVATE`
//! names the hosts that may be (a production scanner names none, and the control plane refuses to start a production
//! profile that would send one, `images.dev-registries`).

use std::collections::BTreeSet;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;

use reqwest::dns::{Addrs, Name, Resolve, Resolving};

/// The hosts that may resolve to private addresses (development only), compared without their port.
#[derive(Debug, Clone, Default)]
pub struct Allowed(Arc<BTreeSet<String>>);

impl Allowed {
    /// From a comma-separated list of hosts, each with or without a port.
    #[must_use]
    pub fn from_list(list: &str) -> Self {
        Self(Arc::new(
            list.split(',')
                .map(str::trim)
                .filter(|host| !host.is_empty())
                .map(|host| host.rsplit_once(':').map_or(host, |(h, _)| h).to_ascii_lowercase())
                .collect(),
        ))
    }

    /// Whether `host` may be private (and may be spoken to over plain HTTP).
    #[must_use]
    pub fn allows(&self, host: &str) -> bool {
        self.0.contains(&host.to_ascii_lowercase())
    }

    /// Whether the scanner may connect to `host` (a name or an address) at `ip`.
    #[must_use]
    pub fn may_connect(&self, host: &str, ip: IpAddr) -> bool {
        is_public(ip) || self.allows(host)
    }
}

/// Resolves names to their public addresses only.
#[derive(Debug, Clone)]
pub struct PublicOnly(pub Allowed);

impl Resolve for PublicOnly {
    fn resolve(&self, name: Name) -> Resolving {
        let allowed = self.0.clone();
        Box::pin(async move {
            let host = name.as_str().to_owned();
            let found: Vec<SocketAddr> = tokio::net::lookup_host((host.as_str(), 0)).await?.collect();
            let usable: Vec<SocketAddr> =
                found.into_iter().filter(|addr| allowed.may_connect(&host, addr.ip())).collect();
            if usable.is_empty() {
                return Err(format!("{host} doesn't resolve to a public address").into());
            }
            let addrs: Addrs = Box::new(usable.into_iter());
            Ok(addrs)
        })
    }
}

/// Whether `url` may be fetched: http(s), and a host that's a name (the resolver checks it) or a public address. Plain HTTP
/// only to an allowed development host.
#[must_use]
pub fn url_allowed(url: &reqwest::Url, allowed: &Allowed) -> bool {
    let Some(host) = url.host() else { return false };
    let name = match host {
        url::Host::Domain(name) => name.to_owned(),
        url::Host::Ipv4(ip) => ip.to_string(),
        url::Host::Ipv6(ip) => ip.to_string(),
    };
    let scheme_ok = url.scheme() == "https" || (url.scheme() == "http" && allowed.allows(&name));
    let address_ok = match host {
        url::Host::Domain(_) => true,
        url::Host::Ipv4(ip) => allowed.may_connect(&name, IpAddr::V4(ip)),
        url::Host::Ipv6(ip) => allowed.may_connect(&name, IpAddr::V6(ip)),
    };
    scheme_ok && address_ok
}

/// A globally routable unicast address: none of the private, shared, loopback, link-local, documentation, benchmarking,
/// multicast or reserved ranges, and an IPv6 address that embeds an IPv4 one is judged by that.
#[must_use]
pub fn is_public(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => public_v4(v4),
        IpAddr::V6(v6) => public_v6(v6),
    }
}

fn public_v4(ip: Ipv4Addr) -> bool {
    let [a, b, c, _] = ip.octets();
    !(ip.is_unspecified()
        || ip.is_loopback()
        || ip.is_private()
        || ip.is_link_local()
        || ip.is_multicast()
        || ip.is_broadcast()
        || ip.is_documentation()
        || a == 0
        || a >= 240
        || (a == 100 && (64..128).contains(&b))
        || (a == 192 && b == 0 && c == 0)
        || (a == 198 && (b == 18 || b == 19)))
}

fn public_v6(ip: Ipv6Addr) -> bool {
    if let Some(v4) = ip.to_ipv4_mapped() {
        return public_v4(v4);
    }
    let segments = ip.segments();
    // NAT64's well-known prefix carries an IPv4 address in its last 32 bits.
    if segments[..6] == [0x64, 0xff9b, 0, 0, 0, 0] {
        return public_v4(Ipv4Addr::new(
            (segments[6] >> 8) as u8,
            (segments[6] & 0xff) as u8,
            (segments[7] >> 8) as u8,
            (segments[7] & 0xff) as u8,
        ));
    }
    !(ip.is_unspecified()
        || ip.is_loopback()
        || ip.is_multicast()
        || (segments[0] & 0xfe00) == 0xfc00
        || (segments[0] & 0xffc0) == 0xfe80
        || (segments[0] == 0x2001 && segments[1] == 0x0db8)
        || segments[..6] == [0, 0, 0, 0, 0, 0])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn our_own_network_and_the_special_ranges_are_never_public() {
        for private in [
            "10.0.0.5",
            "172.17.0.2",
            "192.168.1.10",
            "127.0.0.1",
            "169.254.169.254",
            "100.64.0.1",
            "0.0.0.0",
            "192.0.2.1",
            "198.18.0.1",
            "203.0.113.9",
            "224.0.0.1",
            "255.255.255.255",
            "::1",
            "fd00::1",
            "fe80::1",
            "::ffff:10.0.0.1",
            "64:ff9b::a00:1",
            "2001:db8::1",
            "::",
        ] {
            assert!(!is_public(private.parse().unwrap()), "{private}");
        }
        for public in ["104.16.1.1", "8.8.8.8", "2606:4700::1111", "::ffff:8.8.8.8"] {
            assert!(is_public(public.parse().unwrap()), "{public}");
        }
    }

    #[test]
    fn only_https_to_names_or_public_addresses_unless_a_development_host_is_allowed() {
        let none = Allowed::default();
        let ok = |url: &str, allowed: &Allowed| url_allowed(&url.parse().unwrap(), allowed);
        assert!(ok("https://ghcr.io/v2/", &none));
        assert!(!ok("http://ghcr.io/v2/", &none), "plain HTTP");
        assert!(!ok("https://169.254.169.254/latest/", &none));
        assert!(!ok("https://[::1]:5000/v2/", &none));
        assert!(!ok("http://registry:5000/v2/", &none));
        let dev = Allowed::from_list("registry:5000, localhost");
        assert!(ok("http://registry:5000/v2/", &dev));
        assert!(dev.may_connect("registry", "172.18.0.4".parse().unwrap()));
        assert!(!dev.may_connect("postgres", "172.18.0.3".parse().unwrap()));
    }

    #[tokio::test]
    async fn a_name_that_resolves_only_to_private_addresses_is_refused() {
        let resolver = PublicOnly(Allowed::default());
        let refused = resolver.resolve("localhost".parse().unwrap()).await;
        assert!(refused.is_err());
        let allowed = PublicOnly(Allowed::from_list("localhost"));
        assert!(allowed.resolve("localhost".parse().unwrap()).await.is_ok());
    }
}

//! The DNS a session uses (ADR 0038): a small forwarder the runner starts for each session on its network's gateway address
//! (`172.30.<N>.1`, port 53, UDP and TCP), which is the only place the sandbox's rules let a job ask a DNS question.
//!
//! It forwards every question to the same four public resolvers the sandbox allowed before (1.1.1.1 and 8.8.8.8 are tried in
//! turn), and answers `NXDOMAIN` itself for a name that is, or is below, a mining pool in the blocklist
//! ([`crate::abuse::Blocklist::pool_matching`]), telling the session's watcher so it can flag the session (a second lookup
//! stops it). **It logs no other question**: what a renter looks up is the renter's private business, and nothing here writes a
//! name down, counts one, or keeps one, unless it is a pool's. A miner that uses DNS-over-HTTPS or a bare address beats this;
//! that is what the behaviour reports are for.
//!
//! The question is read from the packet (RFC 1035 §4.1: a header of 12 bytes, then the first question's labels); answers
//! pass through untouched, so DNSSEC, EDNS and every record type work as the resolver gave them.

use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use socket2::{Domain, Protocol, Socket, Type};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::{Semaphore, mpsc};
use tokio::task::JoinHandle;

use crate::abuse::Blocklist;

/// The public resolvers a question is forwarded to, in the order they are tried.
pub const RESOLVERS: [Ipv4Addr; 2] = [Ipv4Addr::new(1, 1, 1, 1), Ipv4Addr::new(8, 8, 8, 8)];

/// The resolvers to forward to: [`RESOLVERS`], unless a development distribution's marker file names its own with
/// `dns-upstream=` lines, for a developer's network that doesn't let public DNS through (a release distribution has no
/// marker, so a host's jobs only ever reach the public resolvers, never the home network's own).
#[must_use]
pub fn resolvers_for(development_marker: &str) -> Arc<[Ipv4Addr]> {
    let named: Vec<Ipv4Addr> = development_marker
        .lines()
        .filter_map(|line| line.trim().strip_prefix("dns-upstream=")?.trim().parse().ok())
        .collect();
    if named.is_empty() { Arc::from(RESOLVERS) } else { Arc::from(named) }
}
/// The DNS port.
const PORT: u16 = 53;
/// The most a question or an answer may be (UDP with EDNS is at most this; a TCP message has its own length).
const MAX_MESSAGE: usize = 4096;
/// How long a resolver has to answer before the next is tried.
const RESOLVER_WAIT: Duration = Duration::from_secs(3);
/// How many questions one session may have in flight, so a job can't make the runner open endless sockets.
const IN_FLIGHT: usize = 64;
/// How long a TCP connection may sit idle.
const TCP_IDLE: Duration = Duration::from_secs(5);

/// The name of the first question in a DNS message, lower-case with no trailing dot; `None` for anything that isn't a plain
/// query with a question (or whose labels are compressed or malformed: a question's name never is).
#[must_use]
pub fn question_name(message: &[u8]) -> Option<String> {
    if message.len() < 17 {
        return None;
    }
    let flags = u16::from_be_bytes([message[2], message[3]]);
    let questions = u16::from_be_bytes([message[4], message[5]]);
    // A query (QR 0), and with a question.
    if flags & 0x8000 != 0 || questions == 0 {
        return None;
    }
    let mut name = String::new();
    let mut at = 12;
    loop {
        let length = usize::from(*message.get(at)?);
        if length == 0 {
            break;
        }
        // 0xC0 and up is a compression pointer (or worse): not in a question.
        if length > 63 {
            return None;
        }
        let label = message.get(at + 1..at + 1 + length)?;
        if !name.is_empty() {
            name.push('.');
        }
        name.extend(label.iter().map(|b| char::from(b.to_ascii_lowercase())));
        at += 1 + length;
        if name.len() > 253 {
            return None;
        }
    }
    // The type and class that follow must be there.
    message.get(at + 1..at + 5)?;
    (!name.is_empty()).then_some(name)
}

/// An `NXDOMAIN` answer to `query`: its id and question, the response bit and `RA` set, response code 3, no records.
#[must_use]
pub fn nxdomain_reply(query: &[u8]) -> Option<Vec<u8>> {
    question_name(query)?;
    // The header and the first question only: its end is after the name's zero byte and the 4 bytes of type and class.
    let mut at = 12;
    while *query.get(at)? != 0 {
        at += 1 + usize::from(query[at]);
    }
    let end = at + 5;
    let mut reply = query.get(..end)?.to_vec();
    let recursion_desired = reply[2] & 0x01;
    reply[2] = 0x80 | (reply[2] & 0x78) | recursion_desired; // QR, the opcode, RD
    reply[3] = 0x80 | 0x03; // RA, response code NXDOMAIN
    reply[4..6].copy_from_slice(&1_u16.to_be_bytes()); // one question
    reply[6..12].fill(0); // no answers, authorities or additionals
    Some(reply)
}

/// A pool lookup the forwarder refused, told to the session's watcher: the listed domain it matched.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PoolLookup {
    /// The domain in the blocklist, not what the job typed.
    pub domain: String,
}

/// A session's forwarder, running until it is dropped or [`Forwarder::stop`]ped.
pub struct Forwarder {
    tasks: Vec<JoinHandle<()>>,
    udp: SocketAddr,
    tcp: SocketAddr,
}

impl Forwarder {
    /// Starts a forwarder on `gateway:53`, UDP and TCP. The address may not exist yet: a session's bridge gets it when its
    /// container starts, and a socket bound with `IP_FREEBIND` takes it from the moment it does.
    ///
    /// # Errors
    /// If the ports can't be bound.
    pub fn start(
        gateway: Ipv4Addr,
        blocklist: Arc<Blocklist>,
        resolvers: Arc<[Ipv4Addr]>,
        lookups: mpsc::UnboundedSender<PoolLookup>,
    ) -> Result<Self, String> {
        Self::start_on(SocketAddr::from((gateway, PORT)), blocklist, resolvers, lookups)
    }

    /// The same on any address and port (0 for any free one), which is how the tests run it.
    ///
    /// # Errors
    /// If the sockets can't be bound.
    pub fn start_on(
        address: SocketAddr,
        blocklist: Arc<Blocklist>,
        resolvers: Arc<[Ipv4Addr]>,
        lookups: mpsc::UnboundedSender<PoolLookup>,
    ) -> Result<Self, String> {
        let udp = bound(Type::DGRAM, Protocol::UDP, address)?;
        let tcp = bound(Type::STREAM, Protocol::TCP, address)?;
        tcp.listen(128).map_err(|e| format!("DNS on {address} couldn't listen: {e}"))?;
        let udp = UdpSocket::from_std(udp.into()).map_err(|e| format!("DNS on {address} (UDP): {e}"))?;
        let tcp = TcpListener::from_std(tcp.into()).map_err(|e| format!("DNS on {address} (TCP): {e}"))?;
        let (udp_address, tcp_address) = (
            udp.local_addr().map_err(|e| format!("DNS on {address}: {e}"))?,
            tcp.local_addr().map_err(|e| format!("DNS on {address}: {e}"))?,
        );
        let limit = Arc::new(Semaphore::new(IN_FLIGHT));
        let udp = Arc::new(udp);
        let udp_task = {
            let (blocklist, resolvers, lookups, limit, socket) =
                (Arc::clone(&blocklist), Arc::clone(&resolvers), lookups.clone(), Arc::clone(&limit), Arc::clone(&udp));
            tokio::spawn(async move {
                let mut buffer = vec![0_u8; MAX_MESSAGE];
                while let Ok((length, from)) = socket.recv_from(&mut buffer).await {
                    let message = buffer[..length].to_vec();
                    let Ok(permit) = Arc::clone(&limit).try_acquire_owned() else {
                        continue; // Too many in flight: a UDP question is retried by its sender.
                    };
                    let (blocklist, resolvers, lookups, socket) =
                        (Arc::clone(&blocklist), Arc::clone(&resolvers), lookups.clone(), Arc::clone(&socket));
                    tokio::spawn(async move {
                        if let Some(reply) = answer(&message, &blocklist, &resolvers, &lookups, false).await {
                            let _ = socket.send_to(&reply, from).await;
                        }
                        drop(permit);
                    });
                }
            })
        };
        let tcp_task = tokio::spawn(async move {
            while let Ok((stream, _)) = tcp.accept().await {
                let Ok(permit) = Arc::clone(&limit).try_acquire_owned() else { continue };
                let (blocklist, resolvers, lookups) = (Arc::clone(&blocklist), Arc::clone(&resolvers), lookups.clone());
                tokio::spawn(async move {
                    serve_tcp(stream, &blocklist, &resolvers, &lookups).await;
                    drop(permit);
                });
            }
        });
        Ok(Self { tasks: vec![udp_task, tcp_task], udp: udp_address, tcp: tcp_address })
    }

    /// Where it answers over UDP and over TCP.
    #[must_use]
    pub fn addresses(&self) -> (SocketAddr, SocketAddr) {
        (self.udp, self.tcp)
    }

    /// Stops answering and gives the address back.
    pub fn stop(&self) {
        for task in &self.tasks {
            task.abort();
        }
    }
}

impl Drop for Forwarder {
    fn drop(&mut self) {
        self.stop();
    }
}

/// A non-blocking socket bound to `address` even if no interface has it yet.
fn bound(kind: Type, protocol: Protocol, address: SocketAddr) -> Result<Socket, String> {
    let socket = Socket::new(Domain::IPV4, kind, Some(protocol)).map_err(|e| format!("DNS on {address}: {e}"))?;
    socket.set_reuse_address(true).map_err(|e| format!("DNS on {address}: {e}"))?;
    // Linux only, which is the only place the runner runs; elsewhere the address must exist already (the tests' loopback does).
    #[cfg(any(target_os = "linux", target_os = "android"))]
    socket.set_freebind_v4(true).map_err(|e| format!("DNS on {address}: {e}"))?;
    socket.set_nonblocking(true).map_err(|e| format!("DNS on {address}: {e}"))?;
    socket.bind(&address.into()).map_err(|e| format!("DNS couldn't bind {address}: {e}"))?;
    Ok(socket)
}

/// The answer to one question: ours for a pool, else whatever a resolver says. `None` when no resolver answered.
async fn answer(
    message: &[u8],
    blocklist: &Blocklist,
    resolvers: &[Ipv4Addr],
    lookups: &mpsc::UnboundedSender<PoolLookup>,
    over_tcp: bool,
) -> Option<Vec<u8>> {
    if let Some(name) = question_name(message)
        && let Some(domain) = blocklist.pool_matching(&name)
    {
        let _ = lookups.send(PoolLookup { domain: domain.to_owned() });
        return nxdomain_reply(message);
    }
    for &resolver in resolvers {
        let result = if over_tcp { ask_tcp(message, resolver).await } else { ask_udp(message, resolver).await };
        if result.is_some() {
            return result;
        }
    }
    None
}

async fn ask_udp(message: &[u8], resolver: Ipv4Addr) -> Option<Vec<u8>> {
    let socket = UdpSocket::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await.ok()?;
    socket.connect(SocketAddr::from((resolver, PORT))).await.ok()?;
    socket.send(message).await.ok()?;
    let mut buffer = vec![0_u8; MAX_MESSAGE];
    let length = tokio::time::timeout(RESOLVER_WAIT, socket.recv(&mut buffer)).await.ok()?.ok()?;
    // Only the answer to this question: the same id.
    (length >= 2 && buffer[..2] == message[..2]).then(|| buffer[..length].to_vec())
}

async fn ask_tcp(message: &[u8], resolver: Ipv4Addr) -> Option<Vec<u8>> {
    let mut stream =
        tokio::time::timeout(RESOLVER_WAIT, TcpStream::connect(SocketAddr::from((resolver, PORT)))).await.ok()?.ok()?;
    stream.write_all(&u16::try_from(message.len()).ok()?.to_be_bytes()).await.ok()?;
    stream.write_all(message).await.ok()?;
    let mut length = [0_u8; 2];
    tokio::time::timeout(RESOLVER_WAIT, stream.read_exact(&mut length)).await.ok()?.ok()?;
    let mut reply = vec![0_u8; usize::from(u16::from_be_bytes(length))];
    tokio::time::timeout(RESOLVER_WAIT, stream.read_exact(&mut reply)).await.ok()?.ok()?;
    (reply.len() >= 2 && reply[..2] == message[..2]).then_some(reply)
}

/// One TCP connection from a job: length-prefixed questions until it goes quiet or closes.
async fn serve_tcp(
    mut stream: TcpStream,
    blocklist: &Blocklist,
    resolvers: &[Ipv4Addr],
    lookups: &mpsc::UnboundedSender<PoolLookup>,
) {
    loop {
        let mut length = [0_u8; 2];
        match tokio::time::timeout(TCP_IDLE, stream.read_exact(&mut length)).await {
            Ok(Ok(_)) => {}
            _ => return,
        }
        let size = usize::from(u16::from_be_bytes(length));
        if size == 0 || size > MAX_MESSAGE {
            return;
        }
        let mut message = vec![0_u8; size];
        if !matches!(tokio::time::timeout(TCP_IDLE, stream.read_exact(&mut message)).await, Ok(Ok(_))) {
            return;
        }
        let Some(reply) = answer(&message, blocklist, resolvers, lookups, true).await else { return };
        let Ok(prefix) = u16::try_from(reply.len()) else { return };
        if stream.write_all(&prefix.to_be_bytes()).await.is_err() || stream.write_all(&reply).await.is_err() {
            return;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A query for `name` (type A, class IN) with id 0xBEEF and recursion desired, as a stub resolver sends it.
    fn query(name: &str) -> Vec<u8> {
        let mut message = vec![0xBE, 0xEF, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
        for label in name.split('.') {
            message.push(u8::try_from(label.len()).unwrap());
            message.extend_from_slice(label.as_bytes());
        }
        message.extend_from_slice(&[0, 0, 1, 0, 1]);
        message
    }

    #[test]
    fn the_name_asked_is_read_from_the_question_lower_cased() {
        assert_eq!(question_name(&query("Eth.2Miners.com")).as_deref(), Some("eth.2miners.com"));
        assert_eq!(question_name(&query("a.b")).as_deref(), Some("a.b"));
    }

    #[test]
    fn anything_that_is_not_a_plain_question_has_no_name() {
        assert_eq!(question_name(&[]), None);
        assert_eq!(question_name(&query("a.b")[..14]), None, "cut short");
        // A response, not a query.
        let mut response = query("a.b");
        response[2] |= 0x80;
        assert_eq!(question_name(&response), None);
        // No question at all.
        let mut empty = query("a.b");
        empty[5] = 0;
        assert_eq!(question_name(&empty), None);
        // A compression pointer where a label should be.
        let mut pointer = query("a.b");
        pointer[12] = 0xC0;
        assert_eq!(question_name(&pointer), None);
        // The root alone is no name to match.
        assert_eq!(question_name(&[0xBE, 0xEF, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1]), None);
    }

    #[test]
    fn a_refusal_is_an_nxdomain_with_the_same_id_and_question_and_nothing_else() {
        let asked = query("xmr.nanopool.org");
        let reply = nxdomain_reply(&asked).unwrap();
        assert_eq!(&reply[..2], &[0xBE, 0xEF], "the id the stub is waiting for");
        assert_eq!(reply[2] & 0x80, 0x80, "a response");
        assert_eq!(reply[2] & 0x01, 0x01, "recursion desired is echoed");
        assert_eq!(reply[3] & 0x0F, 3, "NXDOMAIN");
        assert_eq!(reply[3] & 0x80, 0x80, "recursion available");
        assert_eq!(&reply[4..12], &[0, 1, 0, 0, 0, 0, 0, 0], "one question and no records");
        assert_eq!(&reply[12..], &asked[12..], "the question as it was asked");
        assert_eq!(nxdomain_reply(&[1, 2, 3]), None);
    }

    #[test]
    fn a_development_marker_may_name_the_resolvers_and_a_release_distribution_never_does() {
        assert_eq!(*resolvers_for(""), RESOLVERS);
        assert_eq!(*resolvers_for("registry=x\nabuse-name=y"), RESOLVERS);
        let named = resolvers_for(
            "registry=x\ndns-upstream=192.168.65.7\ndns-upstream= 10.0.58.70 \ndns-upstream=not-an-address",
        );
        assert_eq!(*named, [Ipv4Addr::new(192, 168, 65, 7), Ipv4Addr::new(10, 0, 58, 70)]);
    }

    #[tokio::test]
    async fn a_pool_lookup_is_refused_and_told_and_nothing_else_is_ever_told() {
        let list = Blocklist::built_in();
        let (lookups, mut told) = mpsc::unbounded_channel();
        let refused = answer(&query("eu.NanoPool.org"), &list, &RESOLVERS, &lookups, false).await.unwrap();
        assert_eq!(refused[3] & 0x0F, 3);
        assert_eq!(told.recv().await, Some(PoolLookup { domain: "nanopool.org".to_owned() }));
        // A name that isn't a pool's is forwarded (and here, in a test with no network, goes unanswered): nothing is told.
        let forwarded = tokio::time::timeout(
            Duration::from_millis(50),
            answer(&query("pypi.org"), &list, &RESOLVERS, &lookups, false),
        )
        .await;
        let _ = forwarded;
        assert!(told.try_recv().is_err(), "a lookup that is no pool's is never recorded");
    }

    #[tokio::test]
    async fn the_forwarder_answers_a_pool_over_udp_and_tcp_and_tells_the_session() {
        let (lookups, mut told) = mpsc::unbounded_channel();
        let forwarder = Forwarder::start_on(
            "127.0.0.1:0".parse().unwrap(),
            Arc::new(Blocklist::built_in()),
            Arc::from(RESOLVERS),
            lookups,
        )
        .unwrap();
        let (udp, tcp) = forwarder.addresses();
        let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        socket.send_to(&query("pool.nanopool.org"), udp).await.unwrap();
        let mut buffer = [0_u8; 512];
        let (length, _) =
            tokio::time::timeout(Duration::from_secs(2), socket.recv_from(&mut buffer)).await.unwrap().unwrap();
        assert_eq!(buffer[3] & 0x0F, 3, "NXDOMAIN over UDP");
        assert!(length > 12);
        let mut stream = TcpStream::connect(tcp).await.unwrap();
        let asked = query("xmr.2miners.com");
        stream.write_all(&u16::try_from(asked.len()).unwrap().to_be_bytes()).await.unwrap();
        stream.write_all(&asked).await.unwrap();
        let mut size = [0_u8; 2];
        stream.read_exact(&mut size).await.unwrap();
        let mut reply = vec![0_u8; usize::from(u16::from_be_bytes(size))];
        stream.read_exact(&mut reply).await.unwrap();
        assert_eq!(reply[3] & 0x0F, 3, "NXDOMAIN over TCP");
        assert_eq!(told.recv().await.unwrap().domain, "nanopool.org");
        assert_eq!(told.recv().await.unwrap().domain, "2miners.com");
        forwarder.stop();
    }
}

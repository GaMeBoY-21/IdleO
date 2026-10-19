//! `idleo-runner attach`: the agent's one transport (ADR 0035). It carries the protocol's lines between this process's
//! stdin and stdout and the runner's socket, byte for byte, and ends when either side does. On Windows the agent starts it
//! through `wsl.exe -d IdleO-<version> -u root -- /usr/local/bin/idleo-runner attach`; in development through `docker exec
//! -i`.

use std::io;
use std::path::Path;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::UnixStream;

/// How long `attach` waits for the runner's socket: starting a stopped distribution takes the boot and the services' start.
pub const WAIT_FOR_SOCKET: std::time::Duration = std::time::Duration::from_secs(90);

/// Connects to the socket, waiting up to `wait` for it to appear: the first `wsl.exe` of an idle machine starts the
/// distribution, and its runner a few seconds after.
///
/// # Errors
/// The last connection error, once `wait` is up.
pub async fn connect(socket: &Path, wait: std::time::Duration) -> io::Result<UnixStream> {
    let deadline = tokio::time::Instant::now() + wait;
    loop {
        match UnixStream::connect(socket).await {
            Ok(stream) => return Ok(stream),
            Err(error) if tokio::time::Instant::now() >= deadline => return Err(error),
            Err(_) => tokio::time::sleep(std::time::Duration::from_millis(250)).await,
        }
    }
}

/// Bridges stdin and stdout to the socket at `socket`, once it's there.
///
/// # Errors
/// If the socket can't be reached in [`WAIT_FOR_SOCKET`].
pub async fn attach(socket: &Path) -> io::Result<()> {
    let (mut from_socket, mut to_socket) = connect(socket, WAIT_FOR_SOCKET).await?.into_split();
    let upstream = async {
        let mut stdin = tokio::io::stdin();
        let mut buffer = vec![0_u8; 64 * 1024];
        loop {
            let read = stdin.read(&mut buffer).await?;
            if read == 0 {
                // Nothing more to send; what's still being answered is still read, until the runner closes the connection.
                to_socket.shutdown().await?;
                return std::future::pending().await;
            }
            to_socket.write_all(&buffer[..read]).await?;
        }
    };
    let downstream = async {
        let mut stdout = tokio::io::stdout();
        let mut buffer = vec![0_u8; 64 * 1024];
        loop {
            let read = from_socket.read(&mut buffer).await?;
            if read == 0 {
                return io::Result::Ok(());
            }
            stdout.write_all(&buffer[..read]).await?;
            stdout.flush().await?;
        }
    };
    tokio::select! {
        result = upstream => result,
        result = downstream => result,
    }
}

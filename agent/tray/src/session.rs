//! One connection to the agent over the local channel (ADR 0033): every status the agent pushes is handed on, and
//! every request from the menu is sent. When the agent goes (the launcher restarts it), the session ends and the
//! tray shows "Starting…" and connects again with backoff.

use std::time::Duration;

use idleo_release::channel::{ChannelError, Client, Message, Request, Status};
use tokio::io::{AsyncBufRead, AsyncWrite};
use tokio::sync::mpsc;

/// The waits between tries to reach the agent: 1 second, doubling to 30.
#[must_use]
pub fn backoff(failures: u32) -> Duration {
    Duration::from_secs((1u64 << failures.min(5)).min(30))
}

/// Runs one session until the channel ends: `on_status` hears every status, and every request from `requests` is
/// sent. Returns why it ended.
pub async fn run<R, W>(
    client: &mut Client<R, W>,
    requests: &mut mpsc::UnboundedReceiver<Request>,
    on_status: &mut (dyn FnMut(Status) + Send),
) -> ChannelError
where
    R: AsyncBufRead + Unpin + Send,
    W: AsyncWrite + Unpin + Send,
{
    loop {
        tokio::select! {
            message = client.next() => match message {
                Ok(Message::Status(status)) => on_status(status),
                Ok(Message::Failed { message, .. }) => tracing::warn!(%message, "the agent refused a request"),
                Ok(_) => {}
                Err(e) => return e,
            },
            request = requests.recv() => match request {
                Some(request) => {
                    if let Err(e) = client.send(&request).await {
                        return e;
                    }
                }
                None => return ChannelError::Closed,
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use idleo_release::channel::{AutoPause, State, decode_request, encode_message, read_line};
    use tokio::io::{AsyncWriteExt, BufReader};

    use super::*;
    use crate::menu::{self, Action, Item};
    use crate::status::tests::ready;

    #[test]
    fn backoff_doubles_to_thirty_seconds() {
        let waits: Vec<u64> = (0..7).map(|n| backoff(n).as_secs()).collect();
        assert_eq!(waits, [1, 2, 4, 8, 16, 30, 30]);
    }

    /// The menu's Hosting item, wired through a fake agent on an in-memory pipe: the click sends the request, the agent
    /// answers with a new status, and the menu rebuilt from it has hosting off and no Pause.
    #[tokio::test]
    async fn clicking_hosting_off_sends_it_and_the_new_status_rebuilds_the_menu() {
        let (tray_side, agent_side) = tokio::io::duplex(1 << 16);
        let (tray_read, tray_write) = tokio::io::split(tray_side);
        let (agent_read, mut agent_write) = tokio::io::split(agent_side);
        let mut client = Client::new(BufReader::new(tray_read), tray_write);
        let (requests, mut queued) = mpsc::unbounded_channel();
        let (statuses, mut seen) = mpsc::unbounded_channel::<Status>();

        let agent = tokio::spawn(async move {
            agent_write.write_all(encode_message(&Message::Status(ready())).unwrap().as_bytes()).await.unwrap();
            let mut agent_read = BufReader::new(agent_read);
            let (id, request) = decode_request(&read_line(&mut agent_read).await.unwrap()).unwrap();
            assert_eq!(request, Request::SetHosting { on: false });
            agent_write.write_all(encode_message(&Message::Done { id }).unwrap().as_bytes()).await.unwrap();
            let off = Status { state: State::HostingOff, ..ready() };
            agent_write.write_all(encode_message(&Message::Status(off)).unwrap().as_bytes()).await.unwrap();
            agent_write
        });
        let session = tokio::spawn(async move {
            let mut forward = |status: Status| {
                let _ = statuses.send(status);
            };
            run(&mut client, &mut queued, &mut forward).await
        });

        let first = seen.recv().await.unwrap();
        let offset = time::UtcOffset::UTC;
        let now = time::OffsetDateTime::now_utc();
        let items = menu::build(Some(&first), now, offset);
        let Some(Item::Check { action: Action::Send(request), .. }) =
            items.iter().find(|item| matches!(item, Item::Check { text, .. } if text == "Hosting"))
        else {
            panic!("no Hosting item")
        };
        requests.send(request.clone()).unwrap();
        let second = seen.recv().await.unwrap();
        assert_eq!(second.state, State::HostingOff);
        let rebuilt = menu::build(Some(&second), now, offset);
        assert!(!rebuilt.iter().any(|item| matches!(item, Item::Submenu { text, .. } if text == "Pause")));
        assert_eq!(second.auto_pause, AutoPause::default());

        drop(agent.await.unwrap());
        assert!(matches!(session.await.unwrap(), ChannelError::Closed));
    }
}

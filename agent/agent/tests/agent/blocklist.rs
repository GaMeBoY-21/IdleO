//! The abuse blocklist over the stream (ADR 0038): an agent takes only a list that verifies against its release key.

use std::time::Duration;

use agent_proto::v1::open_stream_request::Body;
use agent_proto::v1::open_stream_response::Body as ServerBody;
use agent_proto::v1::{OpenStreamResponse, UpdateBlocklist};
use idleo_agent::connection::Event;
use uuid::Uuid;

use crate::support::{Stream, TestServer, messages, next_event, spawn_agent, timing};

#[tokio::test]
async fn hello_says_which_blocklist_the_agent_holds_and_a_list_it_cannot_verify_is_acked_and_changes_nothing() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.default_stream = Stream::Scripted);
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (options, mut events) = server.run_options(dir.path(), timing());
    let (_stop, _agent) = spawn_agent(options);
    next_event(&mut events, |e| matches!(e, Event::Welcomed { .. })).await;

    let hello = messages(&server.recorded(), 0)
        .into_iter()
        .find_map(|m| match m.body {
            Some(Body::Hello(hello)) => Some(hello),
            _ => None,
        })
        .expect("Hello");
    assert_eq!(hello.blocklist_version, "", "a new agent holds none, so the control plane sends the newest");

    // A list that was never signed by our key (this test's agent has none to check it against, or the wrong one).
    let list = br#"{"version":"2026-10-25T09:30:00Z","names":[],"hashes":[],"pools":["example.org"]}"#.to_vec();
    let message = OpenStreamResponse {
        message_id: Uuid::now_v7().to_string(),
        body: Some(ServerBody::UpdateBlocklist(UpdateBlocklist {
            version: "2026-10-25T09:30:00Z".to_owned(),
            blocklist: list,
            signature: vec![7; 64],
        })),
    };
    let id = message.message_id.clone();
    server.send(message).await;
    server
        .wait_until(Duration::from_secs(5), |r| {
            r.connections[0]
                .messages
                .iter()
                .any(|(_, m)| matches!(&m.body, Some(Body::Ack(ack)) if ack.acked_message_id == id))
        })
        .await;

    assert!(!dir.path().join("blocklist.json").exists(), "a list that doesn't verify is never kept");
}

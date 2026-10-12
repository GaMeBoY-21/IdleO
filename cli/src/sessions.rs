//! A session as a person reads it: its state, what it has cost, and how it ended.

use chrono::{DateTime, Utc};

use crate::api::SessionResponse;
use crate::brand::BIN_NAME;
use crate::error::{CliError, Exit, Result};
use crate::money::format_paise;
use crate::time::duration;

/// A session id as typed.
///
/// # Errors
/// If it isn't an id.
pub fn parse_id(text: &str) -> Result<uuid::Uuid> {
    text.trim()
        .parse()
        .map_err(|_| CliError::usage(format!("{text:?} isn't a session id; `{BIN_NAME} ls` lists yours")))
}

/// What the session cost so far: what's been charged, and what has run since but isn't settled yet.
#[must_use]
pub fn cost(session: &SessionResponse) -> i64 {
    session.charged_paise + session.accrued_paise
}

/// How long it has run, from its start to its end, or to `now` while it's running.
#[must_use]
pub fn ran_for(session: &SessionResponse, now: DateTime<Utc>) -> Option<i64> {
    session.started_at.map(|start| (session.ended_at.unwrap_or(now) - start).num_seconds())
}

/// The state in a person's words, lower case.
#[must_use]
pub fn state_words(state: &str) -> String {
    state.to_lowercase().replace('_', " ")
}

/// How a settled session ends the command: completed is 0, no host free is 4, everything else is 1.
#[must_use]
pub fn exit_for(session: &SessionResponse) -> Exit {
    match session.state.as_str() {
        "COMPLETED" => Exit::Success,
        "NO_HOST_FREE" => Exit::NoHostFree,
        _ => Exit::Failed,
    }
}

/// Why it ended the way it did, in a sentence, when it didn't complete.
#[must_use]
pub fn why_ended(session: &SessionResponse) -> Option<String> {
    let last = session.events.last().map(|event| event.reason.as_str()).filter(|reason| !reason.is_empty());
    match (session.state.as_str(), session.error_code.as_deref().or(last)) {
        ("COMPLETED", _) => None,
        ("NO_HOST_FREE", _) => Some("No GPU became free for it. Nothing was charged.".to_owned()),
        ("CANCELLED", _) => Some("It was cancelled before it started. Nothing was charged.".to_owned()),
        (_, Some(code)) => Some(format!("It ended with: {}.", code.replace('_', " "))),
        (state, None) => Some(format!("It ended: {}.", state_words(state))),
    }
}

/// The block `status` prints.
#[must_use]
pub fn describe(session: &SessionResponse, now: DateTime<Utc>) -> String {
    let mut lines = vec![
        format!("Session   {}", session.id),
        format!("State     {}{}", state_words(&session.state), if session.settled { "" } else { "  (still changing)" }),
        format!("Template  {}", session.template_id),
    ];
    if let Some(gpu) = &session.gpu {
        lines.push(format!("GPU       {} ({} MB)", gpu.model, gpu.vram_mb));
    }
    if let Some(seconds) = ran_for(session, now) {
        lines.push(format!("Ran for   {}", duration(seconds)));
    }
    lines.push(if session.settled {
        format!("Cost      {}", format_paise(session.charged_paise))
    } else {
        format!(
            "Cost      {} so far; {} held; at most {}",
            format_paise(cost(session)),
            format_paise(session.reserved_paise),
            format_paise(session.max_spend_paise)
        )
    });
    if !session.checkpoints.is_empty() {
        lines.push(format!("Saved     {} checkpoints", session.checkpoints.len()));
    }
    if let Some(why) = why_ended(session) {
        lines.push(why);
    }
    lines.join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    pub fn session(state: &str) -> SessionResponse {
        serde_json::from_value(serde_json::json!({
            "accruedPaise": 2, "attempt": 1, "chargedPaise": 4, "checkpoints": [], "createdAt": "2026-10-12T10:00:00Z",
            "events": [], "id": "01a10e8e-9c65-7789-a526-473491c82218", "inputs": {}, "lowCredit": false,
            "maxPricePaisePerMin": 5, "maxRuntimeSeconds": 300, "maxSpendPaise": 1000, "params": {}, "presets": {},
            "reservedPaise": 60, "settled": false, "state": state, "templateId": "t", "vramNeededMb": 1024,
        }))
        .unwrap()
    }

    #[test]
    fn what_a_session_has_cost_is_charged_plus_accrued() {
        assert_eq!(cost(&session("RUNNING")), 6);
    }

    #[test]
    fn a_settled_session_ends_the_command_by_how_it_ended() {
        assert_eq!(exit_for(&session("COMPLETED")), Exit::Success);
        assert_eq!(exit_for(&session("NO_HOST_FREE")), Exit::NoHostFree);
        for state in ["FAILED", "CANCELLED", "ENDED_REFUNDED"] {
            assert_eq!(exit_for(&session(state)), Exit::Failed, "{state}");
        }
    }

    #[test]
    fn a_completed_session_needs_no_explanation_and_a_failed_one_gives_its_code() {
        assert_eq!(why_ended(&session("COMPLETED")), None);
        let mut failed = session("FAILED");
        failed.error_code = Some("start_timeout".to_owned());
        assert_eq!(why_ended(&failed).as_deref(), Some("It ended with: start timeout."));
    }

    #[test]
    fn an_id_must_be_an_id() {
        assert!(parse_id("01a10e8e-9c65-7789-a526-473491c82218").is_ok());
        assert_eq!(parse_id("nope").unwrap_err().exit, Exit::Usage);
    }

    #[test]
    fn a_settled_session_says_only_what_it_was_charged() {
        let mut settled = session("COMPLETED");
        settled.settled = true;
        let text = describe(&settled, Utc::now());
        assert!(text.contains("Cost      ₹0.04") && !text.contains("held"), "{text}");
    }

    #[test]
    fn status_says_what_it_cost_and_that_it_may_still_change() {
        let text = describe(&session("RUNNING"), Utc::now());
        assert!(text.contains("running  (still changing)"), "{text}");
        assert!(text.contains("₹0.06 so far; ₹0.60 held; at most ₹10.00"), "{text}");
    }
}

//! The CLI and the API's contract (`api/openapi.yaml`), which the control plane's own tests keep true: every call the
//! CLI makes is one the contract has, and the types it uses are generated from it, so an API change that breaks the
//! CLI fails here (and in the build), never in a renter's terminal. The web app does the same (`src/api/schema.gen.ts`).

use std::fs;

use serde_json::Value;

/// Every call the CLI makes: the method, the path as the contract writes it, and where in the CLI it's made.
const CALLS: &[(&str, &str, &str)] = &[
    ("post", "/v1/cli-logins", "login, keys create"),
    ("post", "/v1/cli-logins/{id}/token", "login, keys create"),
    ("get", "/v1/me", "whoami, login"),
    ("get", "/v1/api-keys", "keys, logout"),
    ("delete", "/v1/api-keys/{id}", "keys revoke, logout"),
    ("get", "/v1/wallet", "credit"),
    ("get", "/v1/wallet/transactions", "credit history"),
    ("get", "/v1/templates", "templates, run"),
    ("get", "/v1/availability", "gpus"),
    ("post", "/v1/uploads", "run"),
    ("post", "/v1/uploads/{id}/part-urls", "run"),
    ("post", "/v1/uploads/{id}/complete", "run"),
    ("post", "/v1/sessions", "run"),
    ("get", "/v1/sessions", "ls"),
    ("get", "/v1/sessions/{id}", "status, run, watch"),
    ("get", "/v1/sessions/{id}/events", "run, logs -f, watch"),
    ("get", "/v1/sessions/{id}/logs", "logs, run"),
    ("post", "/v1/sessions/{id}/stop", "stop"),
    ("post", "/v1/sessions/{id}/cancel", "cancel"),
    ("get", "/v1/sessions/{id}/outputs", "outputs, run"),
];

fn contract() -> Value {
    let text =
        fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../api/openapi.yaml")).expect("api/openapi.yaml");
    serde_norway::from_str(&text).expect("the contract is YAML")
}

#[test]
fn every_call_the_cli_makes_is_in_the_contract() {
    let contract = contract();
    for (method, path, used_by) in CALLS {
        assert!(
            contract["paths"][path][method].is_object(),
            "{method} {path} ({used_by}) is no longer in api/openapi.yaml: the CLI has to change with it"
        );
    }
}

#[test]
fn a_waiting_cli_sign_in_and_a_spent_one_are_problems_the_contract_lists() {
    let contract = contract();
    let types: Vec<&str> = contract["components"]["schemas"]["ProblemDetail"]["properties"]["type"]["enum"]
        .as_array()
        .expect("the problem types are an enum")
        .iter()
        .filter_map(Value::as_str)
        .collect();
    for slug in [
        "authorization-pending",
        "cli-login-expired",
        "insufficient-credit",
        "no-gpu-free",
        "rate-limited",
        "too-many-new-sessions",
        "unauthenticated",
    ] {
        assert!(
            types.contains(&format!("/problems/{slug}").as_str()),
            "the CLI branches on {slug}, which the contract no longer lists"
        );
    }
}

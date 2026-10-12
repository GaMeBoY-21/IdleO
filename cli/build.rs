//! Writes the API's types from `api/openapi.yaml`, the contract the control plane's own tests keep true (ADR 0018),
//! so an API change that breaks the CLI fails its build, not a renter's command.
//!
//! The spec's component schemas are read as JSON Schema (its `$ref`s pointed at `definitions`) and turned into Rust
//! types by typify. The result is `$OUT_DIR/api.rs`, never committed.

use std::path::{Path, PathBuf};
use std::{env, fs};

fn main() {
    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("cargo sets CARGO_MANIFEST_DIR"));
    let spec_path = manifest.join("../api/openapi.yaml");
    println!("cargo:rerun-if-changed={}", spec_path.display());
    println!("cargo:rerun-if-changed=build.rs");

    let spec_text =
        fs::read_to_string(&spec_path).unwrap_or_else(|e| panic!("can't read {}: {e}", spec_path.display()));
    let spec: serde_json::Value = serde_norway::from_str(&spec_text).expect("api/openapi.yaml is YAML");
    let schemas = serde_json::to_string(&spec["components"]["schemas"]).expect("schemas are JSON");
    let definitions: serde_json::Value =
        serde_json::from_str(&schemas.replace("#/components/schemas/", "#/definitions/")).expect("still JSON");
    let root = serde_json::json!({
        "$schema": "http://json-schema.org/draft-07/schema#",
        "definitions": definitions,
    });
    let root: schemars::schema::RootSchema = serde_json::from_value(root).expect("the schemas are JSON Schema");

    let mut types = typify::TypeSpace::new(typify::TypeSpaceSettings::default().with_struct_builder(false));
    types.add_root_schema(root).expect("typify takes the API's schemas");
    let file = syn::parse2::<syn::File>(types.to_stream()).expect("typify writes Rust");
    let out = PathBuf::from(env::var("OUT_DIR").expect("cargo sets OUT_DIR"));
    fs::write(Path::new(&out).join("api.rs"), prettyplease::unparse(&file)).expect("OUT_DIR is writable");
}

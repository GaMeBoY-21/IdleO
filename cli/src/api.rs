//! The API's types, generated from `api/openapi.yaml` by build.rs. Nothing here is written by hand.
#![allow(
    clippy::all,
    clippy::pedantic,
    clippy::nursery,
    missing_docs,
    unused,
    unused_comparisons,
    unreachable_pub,
    deprecated,
    rustdoc::bare_urls,
    rustdoc::invalid_html_tags,
    rustdoc::broken_intra_doc_links
)]

include!(concat!(env!("OUT_DIR"), "/api.rs"));

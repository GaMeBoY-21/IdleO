//! One module for each group of commands.
//!
//! Every command fails the same way: with a `CliError`, which carries the exit code the process ends with
//! (src/error.rs); saying so on each one would only repeat it.
#![allow(clippy::missing_errors_doc)]

pub mod docs;
pub mod keys;
pub mod login;
pub mod outputs;
pub mod reads;
pub mod run;
pub mod sessions;

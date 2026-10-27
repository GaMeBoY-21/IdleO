//! The command reference, written from the command line's own definition (ADR 0029), so the docs site can't drift from
//! the commands: `idleo docs --markdown` prints it, and the web app's build reads it. Every command, flag and
//! argument comes from clap; the exit codes come from [`Exit`].

use std::fmt::Write;

use clap::{Arg, Command, CommandFactory};

use crate::brand::{BIN_NAME, PRODUCT};
use crate::cli::Cli;
use crate::error::Exit;

/// What each exit code means, in the order of the codes.
const EXIT_MEANINGS: [(Exit, &str); 8] = [
    (Exit::Success, "Success, or the session completed"),
    (Exit::Failed, "The session failed, or the API refused something no other code names"),
    (Exit::Usage, "Wrong usage"),
    (Exit::NotEnoughCredit, "Not enough credit"),
    (Exit::NoHostFree, "No host free"),
    (Exit::NotSignedIn, "Not signed in"),
    (Exit::RateLimited, "Rate limited"),
    (Exit::Network, "Network"),
];

/// The whole reference as Markdown: the global options, every command with its flags, and the exit codes.
#[must_use]
pub fn markdown() -> String {
    let mut root = Cli::command();
    root.build();
    let mut out = String::new();
    let _ = writeln!(out, "{PRODUCT}'s command line, `{BIN_NAME}`: {}.\n", lower_first(root.get_about()));
    let globals: Vec<&Arg> = root.get_arguments().filter(|arg| arg.is_global_set() && !is_builtin(arg)).collect();
    if !globals.is_empty() {
        out.push_str("## Options for every command\n\n");
        arguments(&mut out, &globals);
    }
    for command in visible(&root) {
        section(&mut out, command, BIN_NAME, 2);
    }
    out.push_str("## Exit codes\n\n| Code | Meaning |\n| --- | --- |\n");
    for (exit, meaning) in EXIT_MEANINGS {
        let _ = writeln!(out, "| {} | {meaning} |", exit.code());
    }
    out
}

fn section(out: &mut String, command: &Command, parent: &str, depth: usize) {
    let path = format!("{parent} {}", command.get_name());
    let _ = writeln!(out, "{} `{path}`\n", "#".repeat(depth));
    if let Some(about) = command.get_about() {
        let _ = writeln!(out, "{}\n", sentence(&about.to_string()));
    }
    let _ = writeln!(out, "```\n{}\n```\n", usage(command, &path));
    let own: Vec<&Arg> = command.get_arguments().filter(|arg| !arg.is_global_set() && !is_builtin(arg)).collect();
    if !own.is_empty() {
        arguments(out, &own);
    }
    for sub in visible(command) {
        section(out, sub, &path, (depth + 1).min(4));
    }
}

fn usage(command: &Command, path: &str) -> String {
    let mut line = path.to_string();
    for arg in command.get_positionals() {
        let name = arg.get_id().as_str().to_uppercase().replace('_', "-");
        let _ = write!(line, " <{name}>");
    }
    if command.get_arguments().any(|arg| !arg.is_positional() && !arg.is_global_set() && !is_builtin(arg)) {
        line.push_str(" [OPTIONS]");
    }
    if visible(command).next().is_some() {
        line.push_str(if command.is_subcommand_required_set() { " <COMMAND>" } else { " [COMMAND]" });
    }
    line
}

fn arguments(out: &mut String, args: &[&Arg]) {
    out.push_str("| Argument | What it does |\n| --- | --- |\n");
    for arg in args {
        // clap drops a help line's final full stop; a sentence in a table needs it.
        let mut what = sentence(&arg.get_help().map(ToString::to_string).unwrap_or_default().replace('|', "\\|"));
        let defaults: Vec<String> =
            arg.get_default_values().iter().map(|value| value.to_string_lossy().into_owned()).collect();
        if !defaults.is_empty() && takes_value(arg) {
            let _ = write!(what, " Default: {}.", defaults.join(", "));
        }
        let _ = writeln!(out, "| `{}` | {what} |", signature(arg).replace('|', "\\|"));
    }
    out.push('\n');
}

fn signature(arg: &Arg) -> String {
    if arg.is_positional() {
        return format!("<{}>", arg.get_id().as_str().to_uppercase().replace('_', "-"));
    }
    let mut names = Vec::new();
    if let Some(short) = arg.get_short() {
        names.push(format!("-{short}"));
    }
    if let Some(long) = arg.get_long() {
        names.push(format!("--{long}"));
    }
    let mut text = names.join(", ");
    if takes_value(arg) {
        let value = arg
            .get_value_names()
            .and_then(|names| names.first())
            .map_or_else(|| arg.get_id().as_str().to_uppercase().replace('_', "-"), ToString::to_string);
        let _ = write!(text, " <{value}>");
    }
    text
}

fn sentence(text: &str) -> String {
    if text.ends_with(['.', '?', '!']) { text.to_string() } else { format!("{text}.") }
}

fn takes_value(arg: &Arg) -> bool {
    arg.get_num_args().is_some_and(|range| range.takes_values())
}

/// clap's own `--help` and `--version`, which every program has and no reference needs to list.
fn is_builtin(arg: &Arg) -> bool {
    matches!(arg.get_id().as_str(), "help" | "version")
}

/// The commands a person may use: not clap's `help`, and not the hidden ones (this one).
fn visible(command: &Command) -> impl Iterator<Item = &Command> {
    command.get_subcommands().filter(|sub| !sub.is_hide_set() && sub.get_name() != "help")
}

fn lower_first(text: Option<&clap::builder::StyledStr>) -> String {
    let text = text.map(ToString::to_string).unwrap_or_default();
    let mut chars = text.chars();
    chars.next().map_or_else(String::new, |first| first.to_lowercase().chain(chars).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every command and subcommand, by its full name.
    fn every_command(command: &Command, parent: &str, found: &mut Vec<String>) {
        for sub in visible(command) {
            let path = format!("{parent} {}", sub.get_name());
            found.push(path.clone());
            every_command(sub, &path, found);
        }
    }

    #[test]
    fn every_command_and_flag_appears() {
        let reference = markdown();
        let mut root = Cli::command();
        root.build();
        let mut commands = Vec::new();
        every_command(&root, BIN_NAME, &mut commands);
        assert!(commands.len() >= 15, "found {commands:?}");
        for path in &commands {
            assert!(reference.contains(&format!("`{path}`")), "{path} is missing");
        }
        for arg in root.get_arguments().filter(|arg| arg.is_global_set() && !is_builtin(arg)) {
            assert!(reference.contains(&format!("`{}", signature(arg))), "{} is missing", arg.get_id());
        }
        let run = root.find_subcommand("run").unwrap();
        for arg in run.get_arguments().filter(|arg| !is_builtin(arg)) {
            assert!(reference.contains(&signature(arg).replace('|', "\\|")), "run's {} is missing", arg.get_id());
        }
    }

    #[test]
    fn a_hidden_command_is_left_out() {
        let reference = markdown();
        assert!(!reference.contains(&format!("`{BIN_NAME} docs`")));
    }

    #[test]
    fn every_exit_code_is_listed_once_in_order() {
        let codes: Vec<i32> = EXIT_MEANINGS.iter().map(|(exit, _)| exit.code()).collect();
        assert_eq!(codes, (0..=7).collect::<Vec<_>>());
        assert!(markdown().contains("| 4 | No host free |"));
    }

    /// The reference the docs site is built from (docs/site/generated/cli.md, ADR 0029) is what the command line says
    /// now. After changing a command: `IDLEO_DOCS_WRITE=1 cargo test docs_reference`.
    #[test]
    fn docs_reference_is_current() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../docs/site/generated/cli.md");
        if std::env::var("IDLEO_DOCS_WRITE").is_ok_and(|value| value == "1") {
            std::fs::write(&path, markdown()).unwrap();
        }
        let committed = std::fs::read_to_string(&path).unwrap_or_default().replace("\r\n", "\n");
        assert!(
            committed == markdown(),
            "docs/site/generated/cli.md is out of date: run IDLEO_DOCS_WRITE=1 cargo test docs_reference"
        );
    }
}

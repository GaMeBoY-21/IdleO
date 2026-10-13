//! The command line: every command and its flags (docs/plan/product-surface.md section 4.2).

use clap::{Args, Parser, Subcommand};

use crate::brand::BIN_NAME;

#[derive(Debug, Parser)]
#[command(name = BIN_NAME, version, about = "Rent a GPU by the minute, from your terminal", propagate_version = true)]
pub struct Cli {
    /// The API's address.
    #[arg(long, global = true, value_name = "URL")]
    pub api: Option<String>,

    /// Print JSON and nothing else on stdout, for scripts.
    #[arg(long, global = true)]
    pub json: bool,

    #[command(subcommand)]
    pub command: Command,
}

#[derive(Debug, Subcommand)]
pub enum Command {
    /// Sign in through the web app.
    Login(LoginArgs),
    /// Sign out, and revoke this computer's key.
    Logout,
    /// Show who's signed in.
    Whoami,
    /// Your credit: available, on hold, and the history.
    Credit(CreditArgs),
    /// What you can run, and what each takes.
    Templates(TemplatesArgs),
    /// What GPUs are free now, by memory.
    Gpus,
    /// Book a GPU for a template, then follow it until it ends.
    Run(RunArgs),
    /// Your sessions.
    Ls(LsArgs),
    /// One session: its state, cost so far, and GPU.
    Status(SessionId),
    /// A session's logs.
    Logs(LogsArgs),
    /// Follow a session's state as it changes.
    Watch(SessionId),
    /// Stop a running session.
    Stop(StopArgs),
    /// Cancel a session that hasn't started.
    Cancel(SessionId),
    /// A session's outputs, and downloading them.
    Outputs(OutputsArgs),
    /// Your API keys.
    Keys(KeysArgs),
    /// Print this command line's reference as Markdown, for the docs site.
    #[command(hide = true)]
    Docs(DocsArgs),
}

#[derive(Debug, Args)]
pub struct DocsArgs {
    /// Print the reference as Markdown.
    #[arg(long)]
    pub markdown: bool,
}

#[derive(Debug, Args)]
pub struct LoginArgs {
    /// What to call this computer in the web app's approval page and in your key's name.
    #[arg(long, value_name = "NAME")]
    pub device_name: Option<String>,
    /// Print the link, but don't open it.
    #[arg(long)]
    pub no_browser: bool,
}

#[derive(Debug, Args)]
pub struct CreditArgs {
    #[command(subcommand)]
    pub command: Option<CreditCommand>,
}

#[derive(Debug, Subcommand)]
pub enum CreditCommand {
    /// Every movement of your credit, newest first.
    History(HistoryArgs),
}

#[derive(Debug, Args)]
pub struct HistoryArgs {
    /// How many entries.
    #[arg(long, default_value_t = 20)]
    pub limit: u32,
}

#[derive(Debug, Args)]
pub struct TemplatesArgs {
    #[command(subcommand)]
    pub command: Option<TemplatesCommand>,
}

#[derive(Debug, Subcommand)]
pub enum TemplatesCommand {
    /// One template: its inputs, questions and parameters.
    Show { id: String },
}

#[derive(Debug, Args)]
pub struct RunArgs {
    /// The template to run (see `templates`).
    pub template: String,
    /// An input, as `name=PATH` or `name=https://…`; repeat it for each input. With one input, `--input PATH` alone.
    #[arg(long, short = 'i', value_name = "NAME=PATH|URL")]
    pub input: Vec<String>,
    /// A parameter, as `name=value`.
    #[arg(long, value_name = "NAME=VALUE")]
    pub param: Vec<String>,
    /// A question's answer, as `question=option`.
    #[arg(long, value_name = "QUESTION=OPTION")]
    pub preset: Vec<String>,
    /// The most to spend on this session, in rupees; the template's usual budget if left out.
    #[arg(long, value_name = "RUPEES")]
    pub max_spend: Option<String>,
    /// The highest price you'll pay, in rupees an hour; the best GPU that fits is used if left out.
    #[arg(long, value_name = "RUPEES")]
    pub max_price: Option<String>,
    /// GPU memory, like 12GB, when the template asks.
    #[arg(long, value_name = "SIZE")]
    pub gpu_memory: Option<String>,
    /// The longest it may run, like 90m or 2h.
    #[arg(long, value_name = "DURATION")]
    pub max_runtime: Option<String>,
    /// Book and print the session's id, without following it.
    #[arg(long)]
    pub detach: bool,
}

#[derive(Debug, Args)]
pub struct LsArgs {
    /// Include sessions that have ended.
    #[arg(long)]
    pub all: bool,
    /// Only sessions in this state, like running.
    #[arg(long, value_name = "STATE")]
    pub state: Option<String>,
    /// How many sessions.
    #[arg(long, default_value_t = 20)]
    pub limit: u32,
}

#[derive(Debug, Args)]
pub struct SessionId {
    /// The session's id.
    pub id: String,
}

#[derive(Debug, Args)]
pub struct LogsArgs {
    pub id: String,
    /// Keep following until the session has ended.
    #[arg(long, short = 'f')]
    pub follow: bool,
}

#[derive(Debug, Args)]
pub struct StopArgs {
    pub id: String,
    /// Stop at once, without saving a last checkpoint.
    #[arg(long)]
    pub no_checkpoint: bool,
}

#[derive(Debug, Args)]
pub struct OutputsArgs {
    pub id: String,
    /// Download the outputs into this folder; a partly downloaded file carries on.
    #[arg(long, value_name = "DIR")]
    pub download: Option<std::path::PathBuf>,
}

#[derive(Debug, Args)]
pub struct KeysArgs {
    #[command(subcommand)]
    pub command: Option<KeysCommand>,
}

#[derive(Debug, Subcommand)]
pub enum KeysCommand {
    /// Make a new full key, approved in the web app like a sign-in.
    Create {
        /// What to call it; shown as "CLI on NAME".
        #[arg(long)]
        name: String,
    },
    /// Revoke a key by its id.
    Revoke { id: String },
}

#[cfg(test)]
mod tests {
    use clap::CommandFactory;

    use super::*;

    #[test]
    fn the_command_line_is_well_formed() {
        Cli::command().debug_assert();
    }

    #[test]
    fn the_command_is_called_by_the_one_constant() {
        assert_eq!(Cli::command().get_name(), BIN_NAME);
    }

    #[test]
    fn the_binary_is_named_by_the_same_constant() {
        // Line by line, so a Windows checkout's CRLF endings don't matter.
        let lines: Vec<&str> = include_str!("../Cargo.toml").lines().collect();
        let named = format!("name = \"{BIN_NAME}\"");
        assert!(
            lines.windows(2).any(|pair| pair[0] == named && pair[1] == "path = \"src/main.rs\""),
            "Cargo.toml's [[bin]]"
        );
    }

    #[test]
    fn run_takes_repeated_inputs_and_parameters() {
        let cli = Cli::try_parse_from([
            "idleo",
            "run",
            "fine-tune-simulated",
            "--input",
            "model=./m",
            "--input",
            "data=./d.jsonl",
            "--param",
            "epochs=3",
            "--preset",
            "quality=quick",
            "--max-spend",
            "50",
            "--detach",
        ])
        .unwrap();
        let Command::Run(run) = cli.command else { panic!("not run") };
        assert_eq!(run.template, "fine-tune-simulated");
        assert_eq!(run.input, ["model=./m", "data=./d.jsonl"]);
        assert_eq!(run.param, ["epochs=3"]);
        assert_eq!(run.preset, ["quality=quick"]);
        assert_eq!(run.max_spend.as_deref(), Some("50"));
        assert!(run.detach);
    }

    #[test]
    fn global_flags_work_after_the_command() {
        let cli = Cli::try_parse_from(["idleo", "ls", "--json", "--api", "https://x.example", "--all"]).unwrap();
        assert!(cli.json && cli.api.as_deref() == Some("https://x.example"));
    }

    #[test]
    fn a_missing_template_is_a_usage_error() {
        assert!(Cli::try_parse_from(["idleo", "run"]).is_err());
    }
}

use clap::Parser;
use idleo_cli::cli::Cli;
use idleo_cli::error::Exit;

#[tokio::main]
async fn main() {
    // clap's own errors (a bad flag, a missing argument) exit 2, as usage errors do; --help and --version exit 0.
    let cli = Cli::parse();
    let json = cli.json;
    let code = match idleo_cli::execute(cli).await {
        Ok(()) => Exit::Success.code(),
        Err(error) => {
            // An empty message means the command has already said everything (a session that ended badly).
            if !error.message.is_empty() {
                if json {
                    let problem = serde_json::json!({ "error": error.message, "problem": error.problem, "exit": error.exit.code() });
                    eprintln!("{problem}");
                } else {
                    eprintln!("{}: {}", idleo_cli::brand::BIN_NAME, error.message);
                }
            }
            error.exit.code()
        }
    };
    std::process::exit(code);
}

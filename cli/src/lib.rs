//! The IdleO renter command line (docs/plan/product-surface.md section 4).

pub mod api;
pub mod brand;
pub mod bytes_fmt;
pub mod cli;
pub mod client;
pub mod commands;
pub mod config;
pub mod context;
pub mod docs;
pub mod error;
pub mod events;
pub mod files;
pub mod follow;
pub mod gpu_class;
pub mod hex;
pub mod keystore;
pub mod logs;
pub mod money;
pub mod pending;
pub mod resume;
pub mod run_args;
pub mod sessions;
pub mod table;
pub mod time;
pub mod upload;

use cli::{Cli, Command};
use commands::run::Stores;
use context::Context;
use error::Result;

/// Runs a parsed command line: finds the API and the person's key, then does what was asked.
///
/// # Errors
/// Whatever the command fails with, carrying the exit code it should end the process with.
pub async fn execute(cli: Cli) -> Result<()> {
    // The reference needs neither an API nor a key.
    if let Command::Docs(args) = &cli.command {
        return commands::docs::docs(args);
    }
    let env = |name: &str| std::env::var(name).ok();
    let api = config::api_url(cli.api.as_deref(), env("IDLEO_API_URL").as_deref())?;
    let dir = config::config_dir(env("IDLEO_CONFIG_DIR").as_deref())?;
    let no_keychain = env("IDLEO_NO_KEYCHAIN").is_some_and(|value| !value.is_empty() && value != "0");
    let keys = keystore::Keystore::new(api.as_str(), &dir, env("IDLEO_API_KEY"), !no_keychain);
    let key = keys.load().map(|(stored, _)| stored.key);
    let ctx = Context { client: client::Client::new(api, key)?, keys, json: cli.json };
    match cli.command {
        Command::Login(args) => commands::login::login(&ctx, &args).await,
        Command::Logout => commands::login::logout(&ctx).await,
        Command::Whoami => commands::login::whoami(&ctx).await,
        Command::Credit(args) => commands::reads::credit(&ctx, &args).await,
        Command::Templates(args) => commands::reads::templates(&ctx, &args).await,
        Command::Gpus => commands::reads::gpus(&ctx).await,
        Command::Run(args) => {
            let stores = Stores { resume: resume::ResumeStore::new(&dir), pending: pending::PendingKeys::new(&dir) };
            commands::run::run(&ctx, &stores, &args).await
        }
        Command::Ls(args) => commands::sessions::ls(&ctx, &args).await,
        Command::Status(args) => commands::sessions::status(&ctx, &args).await,
        Command::Logs(args) => commands::sessions::logs(&ctx, &args).await,
        Command::Watch(args) => commands::sessions::watch(&ctx, &args).await,
        Command::Stop(args) => commands::sessions::stop(&ctx, &args).await,
        Command::Cancel(args) => commands::sessions::cancel(&ctx, &args).await,
        Command::Outputs(args) => commands::outputs::outputs(&ctx, &args).await,
        Command::Keys(args) => commands::keys::keys(&ctx, args.command.as_ref()).await,
        Command::Docs(_) => unreachable!("answered before the API is looked up"),
    }
}

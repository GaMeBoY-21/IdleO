//! `docs --markdown`: the command reference for the docs site (src/docs.rs). Hidden from help.

use crate::cli::DocsArgs;
use crate::error::{CliError, Result};

pub fn docs(args: &DocsArgs) -> Result<()> {
    if !args.markdown {
        return Err(CliError::usage("docs needs --markdown"));
    }
    print!("{}", crate::docs::markdown());
    Ok(())
}

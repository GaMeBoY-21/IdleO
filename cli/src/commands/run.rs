//! `run`: uploads the inputs, books, follows the session until it ends, and prints what it cost and what it gave
//! (docs/plan/product-surface.md section 4.2). Ctrl-C during it detaches; it never stops the session.

use std::collections::HashMap;
use std::num::{NonZeroU32, NonZeroU64};
use std::time::Duration;

use serde::Serialize;

use crate::api::{CreateSessionRequest, Outputs, ParamValue, SessionInput, SessionResponse, TemplateResponse};
use crate::brand::BIN_NAME;
use crate::bytes_fmt::format_bytes;
use crate::cli::RunArgs;
use crate::commands::reads::find;
use crate::context::Context;
use crate::error::{CliError, Exit, Result};
use crate::follow::{Outcome, Show, follow};
use crate::gpu_class::parse_gpu_memory;
use crate::money::{format_paise, parse_rupees};
use crate::pending::PendingKeys;
use crate::resume::ResumeStore;
use crate::run_args::{InputValue, map_inputs, map_params, map_presets, parse_duration};
use crate::sessions::{exit_for, ran_for, state_words, why_ended};
use crate::table::table;
use crate::time::duration;
use crate::upload::upload;

/// How many times a booking is tried when the answer doesn't come, always with the same key.
const BOOKING_TRIES: u32 = 3;

pub struct Stores {
    pub resume: ResumeStore,
    pub pending: PendingKeys,
}

#[derive(Serialize)]
struct Finished<'a> {
    session: &'a SessionResponse,
    #[serde(skip_serializing_if = "Option::is_none")]
    outputs: Option<&'a Outputs>,
}

pub async fn run(ctx: &Context, stores: &Stores, args: &RunArgs) -> Result<()> {
    ctx.client.require_key()?;
    let list: crate::api::TemplateList = ctx.client.get("/v1/templates", &[]).await?;
    let template = find(&list, &args.template)?;
    let inputs = map_inputs(&args.input, &template.inputs)?;
    let params = map_params(&args.param, &template.params)?;
    let presets = map_presets(&args.preset, &template.questions)?;
    let shape = Shape::of(args, template)?;

    // Uploads first: each folder or file once, in the order the template names its inputs.
    let mut given: HashMap<String, SessionInput> = HashMap::new();
    for input in &template.inputs {
        match inputs.get(&input.name) {
            Some(InputValue::Url(url)) => {
                given.insert(input.name.clone(), SessionInput { upload_id: None, url: Some(url.clone()) });
            }
            Some(InputValue::Path(path)) => {
                let id = upload(ctx, &stores.resume, path, &input.name).await?;
                given.insert(input.name.clone(), SessionInput { upload_id: Some(id), url: None });
            }
            None => {}
        }
    }

    let request = CreateSessionRequest {
        template_id: template
            .id
            .as_str()
            .try_into()
            .map_err(|_| CliError::failed("the API sent a template id this version can't use"))?,
        inputs: Some(given),
        params: Some(params.into_iter().collect::<HashMap<String, ParamValue>>()),
        presets: Some(presets.into_iter().collect()),
        max_spend_paise: shape.max_spend,
        max_price_paise_per_min: shape.max_price,
        max_runtime_seconds: shape.max_runtime,
        vram_needed_mb: shape.vram_mb,
    };
    let session = book(ctx, stores, &request).await?;
    ctx.say(format!(
        "Booked {}. It can cost at most {}.",
        session.id,
        format_paise(i64::try_from(shape.max_spend.get()).unwrap_or(i64::MAX))
    ));
    if args.detach {
        ctx.result(&session, || format!("{}\nFollow it: {BIN_NAME} logs {} -f", session.id, session.id));
        return Ok(());
    }
    let id = session.id.to_string();
    match follow(ctx, &id, Show { logs: true }).await? {
        Outcome::Detached => Ok(()),
        Outcome::Settled(settled) => finish(ctx, &settled).await,
    }
}

/// The numbers a booking carries, worked out from the flags and the template.
struct Shape {
    max_spend: NonZeroU64,
    max_price: Option<NonZeroU32>,
    max_runtime: Option<NonZeroU32>,
    vram_mb: Option<NonZeroU32>,
}

impl Shape {
    fn of(args: &RunArgs, template: &TemplateResponse) -> Result<Self> {
        let spend = match &args.max_spend {
            Some(text) => parse_rupees(text).map_err(CliError::usage)?,
            None => template.default_budget_paise.ok_or_else(|| {
                CliError::usage("this template has no usual budget; give one with --max-spend RUPEES")
            })?,
        };
        let max_spend = u64::try_from(spend)
            .ok()
            .and_then(NonZeroU64::new)
            .ok_or_else(|| CliError::usage("--max-spend must be at least ₹0.01"))?;
        // The most a person will pay: rupees an hour, as paise a minute (rounded down, as the web app does).
        let max_price = match &args.max_price {
            Some(text) => {
                let per_minute = parse_rupees(text).map_err(CliError::usage)? / 60;
                Some(
                    u32::try_from(per_minute)
                        .ok()
                        .and_then(NonZeroU32::new)
                        .ok_or_else(|| CliError::usage("--max-price must be at least ₹0.60 an hour"))?,
                )
            }
            None => None,
        };
        let max_runtime = match &args.max_runtime {
            Some(text) => Some(
                NonZeroU32::new(parse_duration(text).map_err(CliError::usage)?).expect("parse_duration is never zero"),
            ),
            None => None,
        };
        let vram_mb = match &args.gpu_memory {
            Some(text) => {
                let mb = parse_gpu_memory(text).map_err(CliError::usage)?;
                NonZeroU32::new(mb.max(u32::try_from(template.min_vram_mb).unwrap_or(0)))
            }
            None => None,
        };
        Ok(Self { max_spend, max_price, max_runtime, vram_mb })
    }
}

/// `POST /v1/sessions`, keeping its Idempotency-Key until the API answers, and trying again with the same key when
/// no answer comes.
async fn book(ctx: &Context, stores: &Stores, request: &CreateSessionRequest) -> Result<SessionResponse> {
    let text = serde_json::to_string(request).map_err(|e| CliError::failed(e.to_string()))?;
    let key = stores.pending.key_for(&text);
    let mut tries = 0;
    loop {
        tries += 1;
        match ctx.client.post::<_, SessionResponse>("/v1/sessions", request, Some(&key)).await {
            Ok(session) => {
                stores.pending.answered(&text);
                return Ok(session);
            }
            Err(error) if error.exit == Exit::Network || error.problem.as_deref() == Some("server-error") => {
                if tries >= BOOKING_TRIES {
                    return Err(CliError::new(
                        error.exit,
                        format!(
                            "{error}\nThe booking may or may not have gone through. Run the same command again: it won't book twice."
                        ),
                    ));
                }
                tokio::time::sleep(Duration::from_secs(u64::from(tries) * 2)).await;
            }
            Err(error) => {
                // The API's answer is final: the key has done its job.
                stores.pending.answered(&text);
                return Err(error);
            }
        }
    }
}

async fn finish(ctx: &Context, session: &SessionResponse) -> Result<()> {
    let outputs: Option<Outputs> = if session.state == "COMPLETED" {
        ctx.client.get(&format!("/v1/sessions/{}/outputs", session.id), &[]).await.ok()
    } else {
        None
    };
    let json = Finished { session, outputs: outputs.as_ref() };
    ctx.result(&json, || summary(session, outputs.as_ref()));
    match exit_for(session) {
        Exit::Success => Ok(()),
        exit => Err(CliError::new(exit, String::new())),
    }
}

fn summary(session: &SessionResponse, outputs: Option<&Outputs>) -> String {
    let mut lines = vec![format!("\nFinished: {}.", state_words(&session.state))];
    if let Some(seconds) = ran_for(session, chrono::Utc::now()) {
        lines.push(format!("Ran for {}.", duration(seconds)));
    }
    lines.push(format!("Charged {}.", format_paise(session.charged_paise)));
    if let Some(why) = why_ended(session) {
        lines.push(why);
    }
    if let Some(outputs) = outputs {
        if outputs.files.is_empty() {
            lines.push("It made no output files.".to_owned());
        } else {
            let rows: Vec<Vec<String>> = outputs
                .files
                .iter()
                .map(|file| vec![file.path.clone(), format_bytes(u64::try_from(file.size_bytes).unwrap_or(0))])
                .collect();
            lines.push(format!(
                "\n{}\n\nDownload them: {BIN_NAME} outputs {} --download ./out",
                table(&["OUTPUT", "SIZE"], &rows),
                session.id
            ));
        }
    }
    lines.join("\n")
}

//! `credit`, `templates` and `gpus`: what you have, what you can run, and what's free now.

use chrono::Utc;

use crate::api::{
    Availability, HistoryPage, TemplateInputKind, TemplateList, TemplateQuestionKind, TemplateResponse, WalletResponse,
};
use crate::cli::{CreditArgs, CreditCommand, TemplatesArgs, TemplatesCommand};
use crate::context::Context;
use crate::error::{CliError, Result};
use crate::gpu_class::{CLASSES_GB, class_of};
use crate::money::{format_paise, format_per_hour};
use crate::table::table;
use crate::time::ago;

pub async fn credit(ctx: &Context, args: &CreditArgs) -> Result<()> {
    match &args.command {
        None => {
            let wallet: WalletResponse = ctx.client.get("/v1/wallet", &[]).await?;
            ctx.result(&wallet, || {
                format!(
                    "Available  {}\nOn hold    {}\nTotal      {}",
                    format_paise(wallet.available_paise),
                    format_paise(wallet.reserved_paise),
                    format_paise(wallet.balance_paise)
                )
            });
        }
        Some(CreditCommand::History(history)) => {
            let page: HistoryPage =
                ctx.client.get("/v1/wallet/transactions", &[("limit", history.limit.to_string())]).await?;
            ctx.result(&page, || {
                if page.entries.is_empty() {
                    return "No credit movements yet.".to_owned();
                }
                let now = Utc::now();
                let rows: Vec<Vec<String>> = page
                    .entries
                    .iter()
                    .map(|entry| {
                        let sign = if entry.direction == "credit" { "+" } else { "-" };
                        vec![
                            ago(entry.created_at, now),
                            entry.kind.replace('_', " "),
                            format!("{sign}{}", format_paise(entry.amount_paise)),
                            if entry.reference.type_.starts_with("session") {
                                entry.reference.id.to_string()
                            } else {
                                String::new()
                            },
                        ]
                    })
                    .collect();
                table(&["WHEN", "WHAT", "AMOUNT", "SESSION"], &rows)
            });
        }
    }
    Ok(())
}

pub async fn templates(ctx: &Context, args: &TemplatesArgs) -> Result<()> {
    let list: TemplateList = ctx.client.get("/v1/templates", &[]).await?;
    match &args.command {
        None => ctx.result(&list, || {
            let rows: Vec<Vec<String>> = list
                .templates
                .iter()
                .filter(|template| !is_hidden(&list, template))
                .map(|template| {
                    vec![
                        template.id.clone(),
                        template.name.clone(),
                        template.typical_runtime_seconds.map_or_else(String::new, crate::time::duration),
                        template.default_budget_paise.map_or_else(String::new, format_paise),
                    ]
                })
                .collect();
            table(&["TEMPLATE", "NAME", "TYPICAL RUN", "USUAL BUDGET"], &rows)
        }),
        Some(TemplatesCommand::Show { id }) => {
            let template = find(&list, id)?;
            ctx.result(template, || describe(template));
        }
    }
    Ok(())
}

/// A hidden service's templates are for support, not for people browsing.
fn is_hidden(list: &TemplateList, template: &TemplateResponse) -> bool {
    list.services.iter().any(|service| service.id == template.service && service.hidden)
}

/// The template with this id.
///
/// # Errors
/// If there's none, naming the ones there are.
pub fn find<'a>(list: &'a TemplateList, id: &str) -> Result<&'a TemplateResponse> {
    list.templates.iter().find(|template| template.id == id).ok_or_else(|| {
        let known: Vec<&str> = list.templates.iter().map(|template| template.id.as_str()).collect();
        CliError::usage(format!("There's no template called {id:?}. The templates are: {}.", known.join(", ")))
    })
}

fn describe(template: &TemplateResponse) -> String {
    let mut out = vec![format!("{}  ({})", template.name, template.id), template.description.clone()];
    if let Some(gets) = &template.you_get {
        out.push(format!("You get: {gets}"));
    }
    out.push(format!("GPU memory: at least {} MB", template.min_vram_mb));
    if let Some(budget) = template.default_budget_paise {
        out.push(format!(
            "Usual budget: {}  (chips: {})",
            format_paise(budget),
            template.budget_chips.iter().map(|p| format_paise(*p)).collect::<Vec<_>>().join(", ")
        ));
    }
    if !template.inputs.is_empty() {
        out.push("\nInputs (--input NAME=PATH or NAME=https://…):".to_owned());
        for input in &template.inputs {
            let kind = match input.kind {
                TemplateInputKind::Folder => "a folder",
                TemplateInputKind::File => "a file",
            };
            let ways = match (input.upload, input.link) {
                (true, true) => "upload or link",
                (true, false) => "upload",
                (false, _) => "link",
            };
            let required = if input.required { "required" } else { "optional" };
            out.push(format!("  {:<12} {kind}, {ways}, {required}. {}", input.name, input.label));
        }
    }
    let questions: Vec<_> = template.questions.iter().filter(|q| q.kind == TemplateQuestionKind::Preset).collect();
    if !questions.is_empty() {
        out.push("\nQuestions (--preset QUESTION=OPTION):".to_owned());
        for question in questions {
            let options: Vec<&str> = question.options.iter().map(|option| option.id.as_str()).collect();
            let default = question.default_option.as_deref().unwrap_or("");
            out.push(format!(
                "  {:<12} {}: {} (usually {default})",
                question.name.as_deref().unwrap_or(""),
                question.label,
                options.join(" | ")
            ));
        }
    }
    if !template.params.is_empty() {
        out.push("\nParameters (--param NAME=VALUE):".to_owned());
        for param in &template.params {
            out.push(format!("  {:<14} {:?}, {}", param.name, param.type_, param.label));
        }
    }
    out.join("\n")
}

pub async fn gpus(ctx: &Context) -> Result<()> {
    let available: Availability = ctx.client.get("/v1/availability", &[]).await?;
    ctx.result(&available, || {
        let rows: Vec<Vec<String>> = CLASSES_GB
            .iter()
            .map(|gb| {
                let cards: Vec<_> = available.gpus.iter().filter(|gpu| class_of(gpu.vram_mb) == Some(*gb)).collect();
                let free: i32 = cards.iter().map(|gpu| gpu.hosts_idle).sum();
                let online: i32 = cards.iter().map(|gpu| gpu.hosts_online).sum();
                let price = match (
                    cards.iter().map(|gpu| gpu.price_paise_per_min.min).min(),
                    cards.iter().map(|gpu| gpu.price_paise_per_min.max).max(),
                ) {
                    (Some(low), Some(high)) if free > 0 => {
                        format!("{}–{}", format_per_hour(i64::from(low)), format_per_hour(i64::from(high)))
                    }
                    _ => "—".to_owned(),
                };
                vec![format!("{gb} GB"), free.to_string(), online.to_string(), price]
            })
            .collect();
        table(&["GPU MEMORY", "FREE NOW", "ONLINE", "PRICE"], &rows)
    });
    Ok(())
}

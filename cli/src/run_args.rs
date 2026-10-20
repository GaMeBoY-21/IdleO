//! What `run`'s flags mean against a template: which input each `--input` fills, the type of each `--param`, each
//! question's answer, and the durations and sizes typed. The server checks everything again and its answer is final;
//! these checks are for saying what's wrong in terms of the flags, before anything is uploaded.

use std::collections::BTreeMap;
use std::path::PathBuf;

use crate::api::{
    ParamValue, TemplateInput, TemplateInputKind, TemplateParam, TemplateParamType, TemplateQuestion,
    TemplateQuestionKind,
};
use crate::brand::BIN_NAME;
use crate::error::{CliError, Result};

/// What an input is given: a link the job fetches inside its sandbox, or a local file or folder to upload.
#[derive(Debug, PartialEq, Eq)]
pub enum InputValue {
    Url(String),
    Path(PathBuf),
}

/// Pairs each `--input` with the template's input it fills.
///
/// `name=VALUE` names its input. A bare `PATH` (or a spec whose left side isn't an input's name) is the template's one
/// input, when it has exactly one and nothing else is given.
///
/// # Errors
/// For a name the template doesn't have, an input given twice, a value of the wrong kind, a path that isn't there,
/// or a required input left out.
pub fn map_inputs(specs: &[String], inputs: &[TemplateInput]) -> Result<BTreeMap<String, InputValue>> {
    let mut given = BTreeMap::new();
    for spec in specs {
        let named = spec
            .split_once('=')
            .filter(|(_, value)| !value.is_empty())
            .and_then(|(name, value)| inputs.iter().find(|input| input.name == name).map(|input| (input, value)));
        let (input, value) = match named {
            Some(found) => found,
            None if specs.len() == 1 && inputs.len() == 1 => (&inputs[0], spec.as_str()),
            None if inputs.is_empty() => return Err(CliError::usage("this template takes no inputs")),
            None => return Err(unknown_input(spec, inputs)),
        };
        let value = value_of(input, value)?;
        if given.insert(input.name.clone(), value).is_some() {
            return Err(CliError::usage(format!("--input {} is given twice", input.name)));
        }
    }
    let missing: Vec<&str> = inputs
        .iter()
        .filter(|input| input.required && !given.contains_key(&input.name))
        .map(|input| input.name.as_str())
        .collect();
    if !missing.is_empty() {
        return Err(CliError::usage(format!(
            "this template needs {}; give {} with --input NAME=PATH (see `{BIN_NAME} templates show`)",
            missing.join(" and "),
            if missing.len() == 1 { "it" } else { "each" }
        )));
    }
    Ok(given)
}

fn unknown_input(spec: &str, inputs: &[TemplateInput]) -> CliError {
    let names: Vec<&str> = inputs.iter().map(|input| input.name.as_str()).collect();
    CliError::usage(format!(
        "{spec:?} doesn't name one of this template's inputs; write --input NAME=PATH, with NAME one of: {}",
        names.join(", ")
    ))
}

fn value_of(input: &TemplateInput, value: &str) -> Result<InputValue> {
    if value.starts_with("https://") {
        return if input.link {
            Ok(InputValue::Url(value.to_owned()))
        } else {
            Err(CliError::usage(format!("{} takes files you upload, not a link", input.name)))
        };
    }
    if value.starts_with("http://") {
        return Err(CliError::usage(format!("{} must be an https:// link, not http://", input.name)));
    }
    if !input.upload {
        return Err(CliError::usage(format!("{} takes an https:// link, not files from this computer", input.name)));
    }
    let path = PathBuf::from(value);
    let meta = std::fs::metadata(&path)
        .map_err(|e| CliError::usage(format!("{}: {value:?} can't be read ({e})", input.name)))?;
    match (&input.kind, meta.is_dir()) {
        (TemplateInputKind::Folder, true) | (TemplateInputKind::File, false) => Ok(InputValue::Path(path)),
        (TemplateInputKind::Folder, false) => {
            Err(CliError::usage(format!("{} takes a folder, but {value:?} is a file", input.name)))
        }
        (TemplateInputKind::File, true) => {
            Err(CliError::usage(format!("{} takes a single file, but {value:?} is a folder", input.name)))
        }
    }
}

/// Each `--param NAME=VALUE`, typed as the template says: whole numbers, numbers, true/false, or text.
///
/// # Errors
/// For a parameter the template doesn't have, one given twice, or a value that isn't its type.
pub fn map_params(specs: &[String], params: &[TemplateParam]) -> Result<BTreeMap<String, ParamValue>> {
    let mut out = BTreeMap::new();
    for spec in specs {
        let (name, value) =
            spec.split_once('=').ok_or_else(|| CliError::usage(format!("--param {spec:?} should be NAME=VALUE")))?;
        let param = params.iter().find(|param| param.name == name).ok_or_else(|| {
            let names: Vec<&str> = params.iter().map(|param| param.name.as_str()).collect();
            CliError::usage(format!(
                "this template has no parameter {name:?}; it has: {}",
                if names.is_empty() { "none".to_owned() } else { names.join(", ") }
            ))
        })?;
        let typed = match param.type_ {
            TemplateParamType::Integer => {
                ParamValue::Integer(value.trim().parse().map_err(|_| bad_value(name, value, "a whole number"))?)
            }
            TemplateParamType::Number => {
                ParamValue::Number(value.trim().parse().map_err(|_| bad_value(name, value, "a number"))?)
            }
            TemplateParamType::Boolean => ParamValue::Boolean(match value.trim().to_lowercase().as_str() {
                "true" | "yes" | "on" | "1" => true,
                "false" | "no" | "off" | "0" => false,
                _ => return Err(bad_value(name, value, "true or false")),
            }),
            TemplateParamType::String | TemplateParamType::Choice => ParamValue::String(value.to_owned()),
        };
        if out.insert(name.to_owned(), typed).is_some() {
            return Err(CliError::usage(format!("--param {name} is given twice")));
        }
    }
    Ok(out)
}

fn bad_value(name: &str, value: &str, wanted: &str) -> CliError {
    CliError::usage(format!("--param {name}={value} should be {wanted}"))
}

/// The answer to every preset question: the one given with `--preset`, else the question's usual one.
///
/// # Errors
/// For a question the template doesn't ask, an option it doesn't offer, or a question given twice.
pub fn map_presets(specs: &[String], questions: &[TemplateQuestion]) -> Result<BTreeMap<String, String>> {
    let asked: Vec<&TemplateQuestion> = questions.iter().filter(|q| q.kind == TemplateQuestionKind::Preset).collect();
    let mut chosen = BTreeMap::new();
    for spec in specs {
        let (name, option) = spec
            .split_once('=')
            .ok_or_else(|| CliError::usage(format!("--preset {spec:?} should be QUESTION=OPTION")))?;
        let question = asked.iter().find(|q| q.name.as_deref() == Some(name)).ok_or_else(|| {
            let names: Vec<&str> = asked.iter().filter_map(|q| q.name.as_deref()).collect();
            CliError::usage(format!(
                "this template doesn't ask {name:?}; it asks: {}",
                if names.is_empty() { "nothing".to_owned() } else { names.join(", ") }
            ))
        })?;
        if !question.options.iter().any(|candidate| candidate.id == option) {
            let options: Vec<&str> = question.options.iter().map(|o| o.id.as_str()).collect();
            return Err(CliError::usage(format!("{name} can be {}, not {option:?}", options.join(" or "))));
        }
        if chosen.insert(name.to_owned(), option.to_owned()).is_some() {
            return Err(CliError::usage(format!("--preset {name} is given twice")));
        }
    }
    for question in asked {
        if let (Some(name), Some(default)) = (question.name.as_deref(), question.default_option.as_deref()) {
            chosen.entry(name.to_owned()).or_insert_with(|| default.to_owned());
        }
    }
    Ok(chosen)
}

/// `90m`, `2h`, `1h30m`, `45s` or `3600` (seconds), in seconds.
///
/// # Errors
/// If it isn't one of those, or is zero.
pub fn parse_duration(text: &str) -> std::result::Result<u32, String> {
    let bad = || format!("{text:?} isn't a time like 90m, 2h or 1h30m");
    let lower = text.trim().to_lowercase();
    if let Ok(seconds) = lower.parse::<u32>() {
        return if seconds == 0 { Err(bad()) } else { Ok(seconds) };
    }
    let mut total: u32 = 0;
    let mut digits = String::new();
    for c in lower.chars() {
        if c.is_ascii_digit() {
            digits.push(c);
            continue;
        }
        let amount: u32 = digits.parse().map_err(|_| bad())?;
        digits.clear();
        let unit = match c {
            'h' => 3_600,
            'm' => 60,
            's' => 1,
            _ => return Err(bad()),
        };
        total = amount.checked_mul(unit).and_then(|part| total.checked_add(part)).ok_or_else(bad)?;
    }
    if !digits.is_empty() || total == 0 {
        return Err(bad());
    }
    Ok(total)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(name: &str, kind: &str, upload: bool, link: bool, required: bool) -> TemplateInput {
        serde_json::from_value(serde_json::json!({"name": name, "kind": kind, "label": name, "upload": upload, "link": link, "required": required, "mustContain": []})).unwrap()
    }

    fn param(name: &str, kind: &str) -> TemplateParam {
        serde_json::from_value(serde_json::json!({"name": name, "type": kind, "label": name, "choices": [], "group": "basic", "required": false})).unwrap()
    }

    fn question(name: &str, default: &str, options: &[&str]) -> TemplateQuestion {
        let options: Vec<_> = options.iter().map(|id| serde_json::json!({"id": id, "label": id, "sets": {}})).collect();
        serde_json::from_value(serde_json::json!({"kind": "preset", "name": name, "label": name, "defaultOption": default, "options": options, "choicesGb": []})).unwrap()
    }

    #[test]
    fn named_inputs_fill_the_inputs_they_name() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("train.jsonl");
        std::fs::write(&file, "{}").unwrap();
        let inputs = [input("model", "folder", true, true, true), input("data", "file", true, true, true)];
        let specs = [format!("model={}", dir.path().display()), format!("data={}", file.display())];

        let mapped = map_inputs(&specs, &inputs).unwrap();

        assert_eq!(mapped["model"], InputValue::Path(dir.path().to_owned()));
        assert_eq!(mapped["data"], InputValue::Path(file));
    }

    #[test]
    fn a_bare_path_is_the_one_input() {
        let dir = tempfile::tempdir().unwrap();
        let mapped =
            map_inputs(&[dir.path().display().to_string()], &[input("code", "folder", true, false, true)]).unwrap();
        assert_eq!(mapped["code"], InputValue::Path(dir.path().to_owned()));
    }

    #[test]
    fn a_bare_path_is_refused_when_the_template_has_two_inputs() {
        let inputs = [input("model", "folder", true, true, true), input("data", "file", true, true, true)];
        let error = map_inputs(&["./thing".to_owned()], &inputs).unwrap_err();
        assert!(error.message.contains("one of: model, data"), "{}", error.message);
    }

    #[test]
    fn a_link_is_a_link_where_links_are_taken() {
        let inputs = [input("data", "file", true, true, true)];
        let mapped = map_inputs(&["data=https://example.com/t.jsonl".to_owned()], &inputs).unwrap();
        assert_eq!(mapped["data"], InputValue::Url("https://example.com/t.jsonl".to_owned()));
        let upload_only = [input("data", "file", true, false, true)];
        assert!(map_inputs(&["data=https://example.com/t.jsonl".to_owned()], &upload_only).is_err());
        assert!(
            map_inputs(&["data=http://example.com/t.jsonl".to_owned()], &inputs).unwrap_err().message.contains("https")
        );
    }

    #[test]
    fn a_folder_isnt_a_file_and_the_other_way_round() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("a.txt");
        std::fs::write(&file, "x").unwrap();
        let folder_input = [input("model", "folder", true, false, true)];
        let file_input = [input("data", "file", true, false, true)];
        assert!(
            map_inputs(&[format!("model={}", file.display())], &folder_input)
                .unwrap_err()
                .message
                .contains("takes a folder")
        );
        assert!(
            map_inputs(&[format!("data={}", dir.path().display())], &file_input)
                .unwrap_err()
                .message
                .contains("single file")
        );
        assert!(
            map_inputs(&["data=/nowhere/at/all".to_owned()], &file_input)
                .unwrap_err()
                .message
                .contains("can't be read")
        );
    }

    #[test]
    fn a_required_input_left_out_is_named() {
        let inputs = [input("model", "folder", true, true, true), input("data", "file", true, true, false)];
        let error = map_inputs(&[], &inputs).unwrap_err();
        assert!(error.message.contains("needs model"), "{}", error.message);
        assert!(map_inputs(&[], &[input("data", "file", true, true, false)]).unwrap().is_empty());
    }

    #[test]
    fn an_input_given_twice_is_refused() {
        let inputs = [input("data", "file", true, true, true)];
        let specs = ["data=https://a.example/x".to_owned(), "data=https://b.example/y".to_owned()];
        assert!(map_inputs(&specs, &inputs).unwrap_err().message.contains("twice"));
    }

    #[test]
    fn parameters_are_typed_as_the_template_says() {
        let params =
            [param("epochs", "integer"), param("lr", "number"), param("fast", "boolean"), param("name", "string")];
        let specs = ["epochs=3", "lr=0.0002", "fast=yes", "name=my run=2"].map(str::to_owned);
        let mapped = map_params(&specs, &params).unwrap();
        assert!(matches!(mapped["epochs"], ParamValue::Integer(3)));
        assert!(matches!(mapped["lr"], ParamValue::Number(n) if (n - 0.0002).abs() < 1e-12));
        assert!(matches!(mapped["fast"], ParamValue::Boolean(true)));
        assert!(matches!(&mapped["name"], ParamValue::String(s) if s == "my run=2"));
    }

    #[test]
    fn a_parameter_of_the_wrong_type_or_name_is_refused() {
        let params = [param("epochs", "integer")];
        assert!(map_params(&["epochs=lots".to_owned()], &params).unwrap_err().message.contains("whole number"));
        assert!(map_params(&["epoch=3".to_owned()], &params).unwrap_err().message.contains("it has: epochs"));
        assert!(map_params(&["epochs".to_owned()], &params).is_err());
    }

    #[test]
    fn a_questions_usual_answer_fills_in_for_one_not_given() {
        let questions =
            [question("quality", "standard", &["quick", "standard"]), question("size", "small", &["small", "large"])];
        let mapped = map_presets(&["quality=quick".to_owned()], &questions).unwrap();
        assert_eq!((mapped["quality"].as_str(), mapped["size"].as_str()), ("quick", "small"));
    }

    #[test]
    fn an_answer_the_question_doesnt_offer_is_refused() {
        let questions = [question("quality", "standard", &["quick", "standard"])];
        assert!(
            map_presets(&["quality=slow".to_owned()], &questions).unwrap_err().message.contains("quick or standard")
        );
        assert!(map_presets(&["speed=quick".to_owned()], &questions).unwrap_err().message.contains("asks: quality"));
    }

    #[test]
    fn times_are_read_in_hours_minutes_and_seconds() {
        assert_eq!(parse_duration("90m"), Ok(5_400));
        assert_eq!(parse_duration("2h"), Ok(7_200));
        assert_eq!(parse_duration("1h30m"), Ok(5_400));
        assert_eq!(parse_duration("45s"), Ok(45));
        assert_eq!(parse_duration("3600"), Ok(3_600));
        for bad in ["", "0", "0m", "m", "1x", "1h30", "-5m", "99999999999h"] {
            assert!(parse_duration(bad).is_err(), "{bad}");
        }
    }
}

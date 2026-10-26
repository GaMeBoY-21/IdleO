//! Trivy (pinned by digest in the scanner's image, ADR 0042) on the image as an OCI layout of the blobs as downloaded:
//! known vulnerabilities and what looks like a secret. Both are warnings for the renter, never a reason to block. Of a
//! secret only its rule, its title and the file it is in are kept: never the matched text, which Trivy masks anyway.

use std::collections::BTreeSet;
use std::path::Path;
use std::process::Stdio;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use tokio::process::Command;

/// The most vulnerabilities kept for the full list; the counts are always whole.
pub const MAX_LISTED: usize = 5000;

/// What Trivy found.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Findings {
    /// Trivy's version.
    pub trivy_version: String,
    /// Distinct vulnerabilities (by id and package) of each severity.
    pub critical: u32,
    /// High.
    pub high: u32,
    /// Medium.
    pub medium: u32,
    /// Low.
    pub low: u32,
    /// Unknown.
    pub unknown: u32,
    /// The vulnerabilities, most severe first, at most [`MAX_LISTED`].
    pub vulnerabilities: Vec<Vulnerability>,
    /// What looks like a secret.
    pub secrets: Vec<Secret>,
}

/// One vulnerability in one package.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Vulnerability {
    /// `CRITICAL`, `HIGH`, `MEDIUM`, `LOW` or `UNKNOWN`.
    pub severity: String,
    /// `CVE-2024-…`.
    pub id: String,
    /// The package.
    #[serde(rename = "packageName")]
    pub package: String,
    /// The version installed.
    pub installed: String,
    /// The version that fixes it, if one does.
    pub fixed: String,
    /// One line on what it is, at most 200 characters.
    pub title: String,
}

/// Something in a file that looks like a secret.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Secret {
    /// The file, with a leading `/`.
    pub path: String,
    /// Trivy's rule, `aws-access-key-id`.
    pub rule: String,
    /// What kind, `AWS`.
    pub category: String,
    /// The rule's title, `AWS Access Key ID`.
    pub title: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct Report {
    #[serde(default)]
    trivy: Option<Version>,
    #[serde(default)]
    results: Vec<Result_>,
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct Version {
    #[serde(default)]
    version: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct Result_ {
    #[serde(default)]
    target: String,
    #[serde(default)]
    vulnerabilities: Vec<RawVulnerability>,
    #[serde(default)]
    secrets: Vec<RawSecret>,
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct RawVulnerability {
    #[serde(rename = "VulnerabilityID", default)]
    id: String,
    #[serde(default)]
    pkg_name: String,
    #[serde(default)]
    installed_version: String,
    #[serde(default)]
    fixed_version: String,
    #[serde(default)]
    severity: String,
    #[serde(default)]
    title: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct RawSecret {
    #[serde(rename = "RuleID", default)]
    rule_id: String,
    #[serde(default)]
    category: String,
    #[serde(default)]
    title: String,
}

/// How Trivy is run.
#[derive(Debug, Clone)]
pub struct Settings {
    /// The program.
    pub program: String,
    /// Its cache: the vulnerability database, kept between checks.
    pub cache: String,
    /// Don't update the database (tests and an offline stack).
    pub offline: bool,
    /// The longest a scan may take.
    pub timeout: Duration,
}

/// Runs Trivy on the OCI layout at `layout`.
///
/// # Errors
/// If Trivy can't run, fails, runs past its time, or says something that isn't its report.
pub async fn scan(settings: &Settings, layout: &Path, tmp: &Path) -> Result<Findings, String> {
    let timeout = format!("{}s", settings.timeout.as_secs());
    let mut command = Command::new(&settings.program);
    command
        .args(["image", "--input"])
        .arg(layout)
        .args([
            "--scanners",
            "vuln,secret",
            "--format",
            "json",
            "--quiet",
            "--skip-version-check",
            "--timeout",
            &timeout,
        ])
        .args(["--cache-dir", &settings.cache])
        .env("TMPDIR", tmp)
        .env_remove("TRIVY_USERNAME")
        .env_remove("TRIVY_PASSWORD")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    if settings.offline {
        command.args(["--skip-db-update", "--skip-java-db-update", "--offline-scan"]);
    }
    let running = command.output();
    let out = tokio::time::timeout(settings.timeout + Duration::from_secs(30), running)
        .await
        .map_err(|_| "Trivy ran past its time".to_owned())?
        .map_err(|e| format!("Trivy couldn't run: {e}"))?;
    if !out.status.success() {
        let stderr = String::from_utf8_lossy(&out.stderr);
        let last = stderr.lines().rev().find(|line| !line.trim().is_empty()).unwrap_or("no message");
        return Err(format!("Trivy failed ({}): {}", out.status, last.chars().take(300).collect::<String>()));
    }
    read(&out.stdout)
}

/// Trivy's JSON report, read into findings.
///
/// # Errors
/// If it isn't Trivy's report.
pub fn read(json: &[u8]) -> Result<Findings, String> {
    let report: Report = serde_json::from_slice(json).map_err(|e| format!("Trivy's report isn't JSON: {e}"))?;
    let mut findings =
        Findings { trivy_version: report.trivy.map(|v| v.version).unwrap_or_default(), ..Findings::default() };
    let mut seen = BTreeSet::new();
    let mut listed = Vec::new();
    let mut secrets = BTreeSet::new();
    for result in report.results {
        for raw in result.vulnerabilities {
            if !seen.insert((raw.id.clone(), raw.pkg_name.clone())) {
                continue;
            }
            let severity = match raw.severity.as_str() {
                "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" => raw.severity.clone(),
                _ => "UNKNOWN".to_owned(),
            };
            match severity.as_str() {
                "CRITICAL" => findings.critical += 1,
                "HIGH" => findings.high += 1,
                "MEDIUM" => findings.medium += 1,
                "LOW" => findings.low += 1,
                _ => findings.unknown += 1,
            }
            listed.push(Vulnerability {
                severity,
                id: raw.id,
                package: raw.pkg_name,
                installed: raw.installed_version,
                fixed: raw.fixed_version,
                title: raw.title.chars().take(200).collect(),
            });
        }
        for raw in result.secrets {
            let path =
                if result.target.starts_with('/') { result.target.clone() } else { format!("/{}", result.target) };
            secrets.insert(Secret { path, rule: raw.rule_id, category: raw.category, title: raw.title });
        }
    }
    let rank = |severity: &str| ["CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN"].iter().position(|s| *s == severity);
    listed.sort_by(|a, b| rank(&a.severity).cmp(&rank(&b.severity)).then_with(|| a.id.cmp(&b.id)));
    listed.truncate(MAX_LISTED);
    findings.vulnerabilities = listed;
    findings.secrets = secrets.into_iter().collect();
    Ok(findings)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_report_becomes_counts_a_list_and_secrets_without_their_text() {
        let report = br#"{
          "Trivy": {"Version": "0.75.0"},
          "Results": [
            {"Target": "img (ubuntu 24.04)", "Vulnerabilities": [
              {"VulnerabilityID": "CVE-1", "PkgName": "openssl", "InstalledVersion": "3.0", "FixedVersion": "3.1", "Severity": "HIGH", "Title": "bad"},
              {"VulnerabilityID": "CVE-1", "PkgName": "openssl", "InstalledVersion": "3.0", "FixedVersion": "3.1", "Severity": "HIGH", "Title": "bad"},
              {"VulnerabilityID": "CVE-2", "PkgName": "zlib", "InstalledVersion": "1", "Severity": "CRITICAL", "Title": "worse"},
              {"VulnerabilityID": "CVE-3", "PkgName": "zlib", "InstalledVersion": "1", "Severity": "NEGLIGIBLE"}
            ]},
            {"Target": "/app/.env", "Class": "secret", "Secrets": [
              {"RuleID": "aws-access-key-id", "Category": "AWS", "Severity": "CRITICAL", "Title": "AWS Access Key ID",
               "Match": "AWS_ACCESS_KEY_ID=********************"}
            ]}
          ]
        }"#;
        let findings = read(report).unwrap();
        assert_eq!((findings.critical, findings.high, findings.unknown), (1, 1, 1));
        assert_eq!(findings.vulnerabilities[0].id, "CVE-2", "most severe first");
        assert_eq!(findings.vulnerabilities.len(), 3, "a repeat is counted once");
        assert_eq!(
            findings.secrets,
            [Secret {
                path: "/app/.env".into(),
                rule: "aws-access-key-id".into(),
                category: "AWS".into(),
                title: "AWS Access Key ID".into()
            }]
        );
        assert!(!serde_json::to_string(&findings).unwrap().contains("AWS_ACCESS_KEY_ID="));
    }
}

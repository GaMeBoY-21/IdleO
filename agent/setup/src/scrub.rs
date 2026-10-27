//! Scrubbing what setup reports: an install event's `detail` may carry an error's text or a
//! check's finding, and those can name the person or the machine. Before anything is sent, the profile folder becomes
//! `%USERPROFILE%`, the Windows user name `<user>`, the computer's name `<machine>`, and anything that looks like a
//! hardware serial or id (a GPU's UUID, a `serial …` value) `<id>`. The local setup log keeps the original words.

/// The most characters of a detail that are sent (the server keeps 2,000).
pub const MAX_DETAIL: usize = 2_000;

/// Who and what to take out.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Identity {
    /// The user's profile folder, for example `C:\Users\nikki`.
    pub profile: String,
    /// The Windows user name.
    pub user: String,
    /// The computer's name.
    pub machine: String,
}

/// `text` with the person and the machine taken out, trimmed, and cut to [`MAX_DETAIL`] characters.
#[must_use]
pub fn detail(text: &str, who: &Identity) -> String {
    let mut out = scrub(text, who);
    out = out.trim().to_owned();
    if out.chars().count() > MAX_DETAIL {
        out = out.chars().take(MAX_DETAIL).collect();
    }
    out
}

/// `text` with the person and the machine taken out.
#[must_use]
pub fn scrub(text: &str, who: &Identity) -> String {
    let mut out = text.to_owned();
    let profile = who.profile.trim_end_matches(['\\', '/']);
    if !profile.is_empty() {
        // The folder as written, with forward slashes, and doubled backslashes as JSON writes them; longest first.
        let spellings = [profile.replace('\\', "\\\\"), profile.to_owned(), profile.replace('\\', "/")];
        for spelling in spellings {
            out = replace_ignoring_case(&out, &spelling, "%USERPROFILE%");
        }
    }
    out = replace_word(&out, &who.user, "<user>");
    out = replace_word(&out, &who.machine, "<machine>");
    out = hide_ids(&out);
    out
}

/// Every `needle`, whatever its letters' case (as Windows paths are), replaced.
fn replace_ignoring_case(text: &str, needle: &str, with: &str) -> String {
    if needle.is_empty() {
        return text.to_owned();
    }
    let lower = text.to_ascii_lowercase();
    let needle = needle.to_ascii_lowercase();
    let mut out = String::with_capacity(text.len());
    let mut from = 0;
    while let Some(found) = lower[from..].find(&needle) {
        let start = from + found;
        out.push_str(&text[from..start]);
        out.push_str(with);
        from = start + needle.len();
    }
    out.push_str(&text[from..]);
    out
}

/// Every whole-word `word` (not part of a longer run of letters and digits), replaced. Words under three characters
/// are left: they'd mangle ordinary text, and the profile folder has gone already.
fn replace_word(text: &str, word: &str, with: &str) -> String {
    if word.chars().count() < 3 {
        return text.to_owned();
    }
    let lower = text.to_ascii_lowercase();
    let needle = word.to_ascii_lowercase();
    let is_word = |c: char| c.is_alphanumeric();
    let mut out = String::with_capacity(text.len());
    let mut from = 0;
    while let Some(found) = lower[from..].find(&needle) {
        let start = from + found;
        let end = start + needle.len();
        let before = text[..start].chars().next_back();
        let after = text[end..].chars().next();
        out.push_str(&text[from..start]);
        if before.is_some_and(is_word) || after.is_some_and(is_word) {
            out.push_str(&text[start..end]);
        } else {
            out.push_str(with);
        }
        from = end;
    }
    out.push_str(&text[from..]);
    out
}

/// A GPU's UUID (`GPU-` and 8-4-4-4-12 hex) or any 8-4-4-4-12 id, and the value after the word "serial", as `<id>`.
fn hide_ids(text: &str) -> String {
    let chars: Vec<char> = text.chars().collect();
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < chars.len() {
        if let Some(length) = uuid_at(&chars, i) {
            // A leading "GPU-" goes with it.
            if out.ends_with("GPU-") {
                out.truncate(out.len() - 4);
            }
            out.push_str("<id>");
            i += length;
            continue;
        }
        if let Some(length) = serial_at(&chars, i) {
            out.extend(&chars[i..i + length.0]);
            out.push_str("<id>");
            i += length.0 + length.1;
            continue;
        }
        out.push(chars[i]);
        i += 1;
    }
    out
}

/// The length of an 8-4-4-4-12 hex id starting at `i`, if one does and it isn't inside a longer word.
fn uuid_at(chars: &[char], i: usize) -> Option<usize> {
    const SHAPE: [usize; 5] = [8, 4, 4, 4, 12];
    if i > 0 && (chars[i - 1].is_ascii_alphanumeric()) && !(i >= 4 && chars[i - 4..i] == ['G', 'P', 'U', '-']) {
        return None;
    }
    let mut at = i;
    for (n, size) in SHAPE.iter().enumerate() {
        if n > 0 {
            if chars.get(at) != Some(&'-') {
                return None;
            }
            at += 1;
        }
        for _ in 0..*size {
            if !chars.get(at).is_some_and(char::is_ascii_hexdigit) {
                return None;
            }
            at += 1;
        }
    }
    if chars.get(at).is_some_and(char::is_ascii_alphanumeric) {
        return None;
    }
    Some(at - i)
}

/// `serial`, then `:`, `=` or spaces, then a value: returns (the length up to the value, the value's length).
fn serial_at(chars: &[char], i: usize) -> Option<(usize, usize)> {
    const WORD: [char; 6] = ['s', 'e', 'r', 'i', 'a', 'l'];
    if i > 0 && chars[i - 1].is_ascii_alphanumeric() {
        return None;
    }
    for (n, expected) in WORD.iter().enumerate() {
        if chars.get(i + n).map(char::to_ascii_lowercase) != Some(*expected) {
            return None;
        }
    }
    let mut at = i + WORD.len();
    // "serial number" is two words.
    if chars[at..].iter().take(7).collect::<String>().eq_ignore_ascii_case(" number") {
        at += 7;
    }
    let separators = chars[at..].iter().take_while(|c| matches!(c, ':' | '=' | ' ' | '\t')).count();
    if separators == 0 {
        return None;
    }
    at += separators;
    let value = chars[at..].iter().take_while(|c| !c.is_whitespace() && !matches!(c, ',' | ';' | ')')).count();
    // A serial has digits in it; ordinary words after "serial" ("serial port") are left.
    let has_digit = chars[at..at + value].iter().any(char::is_ascii_digit);
    (value >= 4 && has_digit).then_some((at - i, value))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn who() -> Identity {
        Identity { profile: r"C:\Users\Asha Rao".into(), user: "Asha".into(), machine: "TESTRIG-7".into() }
    }

    #[test]
    fn the_profile_folder_in_any_spelling_and_case_becomes_a_variable() {
        let text =
            r#"failed at C:\Users\Asha Rao\AppData\Local\IdleO\x, c:/users/asha rao/y and "C:\\Users\\Asha Rao\\z""#;
        let out = scrub(text, &who());
        assert_eq!(out, r#"failed at %USERPROFILE%\AppData\Local\IdleO\x, %USERPROFILE%/y and "%USERPROFILE%\\z""#);
    }

    #[test]
    fn the_user_name_and_the_machine_are_replaced_only_as_whole_words() {
        let out = scrub("user asha on TESTRIG-7 ran Ashanti's thing; ASHA.", &who());
        assert_eq!(out, "user <user> on <machine> ran Ashanti's thing; <user>.");
    }

    #[test]
    fn a_gpu_uuid_and_a_serial_are_hidden() {
        let out = scrub(
            "GPU-3f2a9c1e-0b7d-4e55-9a10-6c1d2e3f4a5b is the RTX; Serial Number: 1234567890 and serial=AB-99 ok",
            &who(),
        );
        assert_eq!(out, "<id> is the RTX; Serial Number: <id> and serial=<id> ok");
    }

    #[test]
    fn short_values_and_ordinary_words_are_left_alone() {
        let out = scrub("a serial port, version 572.16, 12 checks", &Identity::default());
        assert_eq!(out, "a serial port, version 572.16, 12 checks");
    }

    #[test]
    fn a_detail_is_trimmed_and_cut_to_the_limit() {
        let long = format!("  {}  ", "é".repeat(MAX_DETAIL + 50));
        let out = detail(&long, &Identity::default());
        assert_eq!(out.chars().count(), MAX_DETAIL);
        assert_eq!(detail("  hello \n", &Identity::default()), "hello");
    }

    #[test]
    fn nothing_of_the_person_survives_an_error_that_names_them() {
        let who = who();
        let text = format!(
            "import failed: The system cannot find the path specified. ({}\\AppData\\Local\\IdleO\\wsl\\0.1.0) \
             by ASHA on TESTRIG-7 serial: 5T6U7V8W",
            who.profile
        );
        let out = detail(&text, &who);
        for secret in ["Asha", "TESTRIG", "5T6U7V8W", "C:\\Users"] {
            assert!(!out.to_lowercase().contains(&secret.to_lowercase()), "{secret} in {out}");
        }
        assert!(out.contains("%USERPROFILE%"), "{out}");
    }
}

//! Money, from integer paise, never floating point (CLAUDE.md): rupees with Indian digit grouping, like the web app's
//! `formatPaise` (web/src/lib/money.ts).

/// `₹1,23,456.78` for 12,345,678 paise; a minus sign for a negative amount.
#[must_use]
pub fn format_paise(paise: i64) -> String {
    let sign = if paise < 0 { "-" } else { "" };
    let whole = paise.unsigned_abs();
    let rupees = (whole / 100).to_string();
    let grouped = group_indian(&rupees);
    format!("{sign}₹{grouped}.{:02}", whole % 100)
}

/// Digits grouped as 3 then 2s from the right: 1234567 is 12,34,567.
fn group_indian(digits: &str) -> String {
    if digits.len() <= 3 {
        return digits.to_owned();
    }
    let (head, tail) = digits.split_at(digits.len() - 3);
    let mut groups: Vec<&str> = Vec::new();
    let mut end = head.len();
    while end > 0 {
        let start = end.saturating_sub(2);
        groups.push(&head[start..end]);
        end = start;
    }
    groups.reverse();
    format!("{},{tail}", groups.join(","))
}

/// A price per minute as the plan quotes it, per hour: 12 paise a minute is `₹7.20/h`.
#[must_use]
pub fn format_per_hour(paise_per_minute: i64) -> String {
    format!("{}/h", format_paise(paise_per_minute * 60))
}

/// Rupees a person types, to paise, exactly: `500`, `12.5` and `₹1,200.05` are 50000, 1250 and 120005.
///
/// # Errors
/// If it isn't a plain amount with at most two decimals.
pub fn parse_rupees(text: &str) -> Result<i64, String> {
    let cleaned: String = text.trim().trim_start_matches('₹').chars().filter(|c| *c != ',').collect();
    let (whole, fraction) = cleaned.split_once('.').unwrap_or((&cleaned, ""));
    let bad = || format!("{text:?} isn't an amount in rupees, like 500 or 12.50");
    if whole.is_empty()
        || fraction.len() > 2
        || ![whole, fraction].iter().all(|p| p.chars().all(|c| c.is_ascii_digit()))
    {
        return Err(bad());
    }
    let rupees: i64 = whole.parse().map_err(|_| bad())?;
    let paise: i64 = if fraction.is_empty() { 0 } else { format!("{fraction:0<2}").parse().map_err(|_| bad())? };
    rupees.checked_mul(100).and_then(|r| r.checked_add(paise)).ok_or_else(bad)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rupees_are_grouped_the_indian_way() {
        assert_eq!(format_paise(0), "₹0.00");
        assert_eq!(format_paise(6), "₹0.06");
        assert_eq!(format_paise(49_994), "₹499.94");
        assert_eq!(format_paise(100_000), "₹1,000.00");
        assert_eq!(format_paise(12_345_678), "₹1,23,456.78");
        assert_eq!(format_paise(1_234_567_890), "₹1,23,45,678.90");
        assert_eq!(format_paise(-250), "-₹2.50");
    }

    #[test]
    fn a_price_a_minute_is_quoted_an_hour() {
        assert_eq!(format_per_hour(12), "₹7.20/h");
        assert_eq!(format_per_hour(18), "₹10.80/h");
    }

    #[test]
    fn amounts_in_rupees_become_exact_paise() {
        assert_eq!(parse_rupees("500"), Ok(50_000));
        assert_eq!(parse_rupees("12.5"), Ok(1_250));
        assert_eq!(parse_rupees("₹1,200.05"), Ok(120_005));
        assert_eq!(parse_rupees("0.07"), Ok(7));
    }

    #[test]
    fn anything_else_is_refused() {
        for text in ["", "abc", "1.234", "-5", "1e3", "₹", ".5x", "99999999999999999999"] {
            assert!(parse_rupees(text).is_err(), "{text}");
        }
    }
}

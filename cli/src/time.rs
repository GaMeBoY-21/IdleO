//! Times for people: "5 min ago", from a moment and now.

use chrono::{DateTime, Utc};

/// How long before `now` a moment was, in the largest whole unit; a date for anything over a month.
#[must_use]
pub fn ago(moment: DateTime<Utc>, now: DateTime<Utc>) -> String {
    let seconds = (now - moment).num_seconds();
    match seconds {
        ..45 => "just now".to_owned(),
        45..3_600 => format!("{} min ago", (seconds + 30) / 60),
        3_600..86_400 => format!("{} h ago", seconds / 3_600),
        86_400..2_592_000 => format!("{} d ago", seconds / 86_400),
        _ => moment.format("%Y-%m-%d").to_string(),
    }
}

/// Seconds as `1h 05m 09s`, `5m 09s` or `9s`.
#[must_use]
pub fn duration(seconds: i64) -> String {
    let seconds = seconds.max(0);
    let (h, m, s) = (seconds / 3_600, seconds % 3_600 / 60, seconds % 60);
    match (h, m) {
        (0, 0) => format!("{s}s"),
        (0, _) => format!("{m}m {s:02}s"),
        _ => format!("{h}h {m:02}m {s:02}s"),
    }
}

#[cfg(test)]
mod tests {
    use chrono::TimeZone;

    use super::*;

    fn at(h: u32, m: u32, s: u32) -> DateTime<Utc> {
        Utc.with_ymd_and_hms(2026, 10, 12, h, m, s).unwrap()
    }

    #[test]
    fn a_moment_is_told_in_its_largest_unit() {
        let now = at(12, 0, 0);
        assert_eq!(ago(at(11, 59, 50), now), "just now");
        assert_eq!(ago(at(11, 55, 0), now), "5 min ago");
        assert_eq!(ago(at(9, 0, 0), now), "3 h ago");
        assert_eq!(ago(Utc.with_ymd_and_hms(2026, 10, 9, 12, 0, 0).unwrap(), now), "3 d ago");
        assert_eq!(ago(Utc.with_ymd_and_hms(2026, 8, 1, 12, 0, 0).unwrap(), now), "2026-08-01");
        assert_eq!(ago(at(12, 0, 5), now), "just now");
    }

    #[test]
    fn a_run_time_is_short_and_exact() {
        assert_eq!(duration(9), "9s");
        assert_eq!(duration(309), "5m 09s");
        assert_eq!(duration(3_909), "1h 05m 09s");
        assert_eq!(duration(-4), "0s");
    }
}

use anyhow::{anyhow, Result};
use chrono::NaiveDate;
use time::OffsetDateTime;

pub fn millis_to_utc(ms: i64) -> OffsetDateTime {
    let nanos = i128::from(ms).saturating_mul(1_000_000);
    OffsetDateTime::from_unix_timestamp_nanos(nanos).unwrap_or_else(|_| OffsetDateTime::now_utc())
}

pub fn current_millis() -> i64 {
    OffsetDateTime::now_utc().unix_timestamp_nanos() as i64 / 1_000_000
}

#[allow(dead_code)]
pub fn parse_date(date: &str) -> Result<NaiveDate> {
    NaiveDate::parse_from_str(date, "%Y-%m-%d").map_err(|err| anyhow!(err))
}

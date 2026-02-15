use anyhow::Result;
use clickhouse::Client;

use crate::model::{AnomalyRow, IngestEvent, ItemEventRow, ReportSummary, StorageScanEventRow};
use crate::utils::millis_to_utc;

#[derive(Clone)]
pub struct ClickhouseRepo {
    client: Client,
    database: String,
}

impl ClickhouseRepo {
    pub fn new(client: Client, database: String) -> Self {
        Self { client, database }
    }

    pub async fn ensure_schema(&self) -> Result<()> {
        let create_db = format!("CREATE DATABASE IF NOT EXISTS {}", self.database);
        self.client.query(&create_db).execute().await?;

        let create_events = r#"
CREATE TABLE IF NOT EXISTS item_events (
    event_time DateTime64(3),
    event_id String,
    server_id String,
    event_type String,
    player_uuid String,
    player_name String,
    item_id String,
    count Int64,
    origin_id String,
    origin_type String,
    origin_ref String,
    source_type String,
    source_ref String,
    storage_mod String,
    storage_id String,
    actor_type String,
    trace_id String,
    item_fingerprint String,
    dim String,
    x Nullable(Int32),
    y Nullable(Int32),
    z Nullable(Int32)
) ENGINE = MergeTree
PARTITION BY toDate(event_time)
ORDER BY (event_time, player_uuid, item_id)
TTL toDateTime(event_time) + INTERVAL 7 DAY
"#;

        self.client.query(create_events).execute().await?;

        let create_anomalies = r#"
CREATE TABLE IF NOT EXISTS anomalies (
    event_time DateTime64(3),
    server_id String,
    player_uuid String,
    player_name String,
    item_id String,
    count Int64,
    risk_level String,
    rule_id String,
    reason String,
    evidence_json String
) ENGINE = MergeTree
PARTITION BY toDate(event_time)
ORDER BY (event_time, player_uuid, item_id)
TTL toDateTime(event_time) + INTERVAL 30 DAY
"#;

        self.client.query(create_anomalies).execute().await?;
        Ok(())
    }

    pub async fn insert_events(&self, events: &[IngestEvent]) -> Result<()> {
        let mut insert = self.client.insert("item_events")?;
        for event in events {
            insert
                .write(&ItemEventRow {
                    event_time: millis_to_utc(event.event_time),
                    event_id: event.event_id.clone(),
                    server_id: event.server_id.clone().unwrap_or_default(),
                    event_type: event.event_type.clone(),
                    player_uuid: event.player_uuid.clone().unwrap_or_default(),
                    player_name: event.player_name.clone().unwrap_or_default(),
                    item_id: event.item_id.clone(),
                    count: event.count,
                    origin_id: event.origin_id.clone().unwrap_or_default(),
                    origin_type: event.origin_type.clone().unwrap_or_default(),
                    origin_ref: event.origin_ref.clone().unwrap_or_default(),
                    source_type: event.source_type.clone().unwrap_or_default(),
                    source_ref: event.source_ref.clone().unwrap_or_default(),
                    storage_mod: event.storage_mod.clone().unwrap_or_default(),
                    storage_id: event.storage_id.clone().unwrap_or_default(),
                    actor_type: event.actor_type.clone().unwrap_or_default(),
                    trace_id: event.trace_id.clone().unwrap_or_default(),
                    item_fingerprint: event.item_fingerprint.clone().unwrap_or_default(),
                    dim: event.dim.clone().unwrap_or_default(),
                    x: event.x,
                    y: event.y,
                    z: event.z,
                })
                .await?;
        }
        insert.end().await?;
        Ok(())
    }

    pub async fn insert_anomalies(&self, anomalies: &[AnomalyRow]) -> Result<()> {
        let mut insert = self.client.insert("anomalies")?;
        for anomaly in anomalies {
            insert.write(anomaly).await?;
        }
        insert.end().await?;
        Ok(())
    }

    pub async fn fetch_anomalies(&self, date: &str, player: Option<&str>) -> Result<Vec<AnomalyRow>> {
        let mut query = format!(
            "SELECT event_time, server_id, player_uuid, player_name, item_id, count, risk_level, rule_id, reason, evidence_json FROM anomalies WHERE toDate(event_time) = toDate('{}')",
            date
        );
        if let Some(player_name) = player {
            query.push_str(&format!(" AND player_name = '{}'", player_name));
        }
        query.push_str(" ORDER BY event_time DESC LIMIT 500");
        let rows = self.client.query(&query).fetch_all::<AnomalyRow>().await?;
        Ok(rows)
    }

    pub async fn fetch_summary(&self, date: &str) -> Result<ReportSummary> {
        let query = format!(
            "SELECT risk_level, count() as cnt FROM anomalies WHERE toDate(event_time) = toDate('{}') GROUP BY risk_level",
            date
        );
        let rows = self.client.query(&query).fetch_all::<(String, u64)>().await?;
        let mut summary = ReportSummary::default();
        for (risk, count) in rows {
            match risk.as_str() {
                "HIGH" => summary.high = count,
                "MEDIUM" => summary.medium = count,
                "LOW" => summary.low = count,
                _ => {}
            }
        }
        Ok(summary)
    }

    pub async fn fetch_storage_scan_events(
        &self,
        date: &str,
        item: Option<&str>,
        limit: usize,
    ) -> Result<Vec<StorageScanEventRow>> {
        let mut query = format!(
            "SELECT event_time, item_id, count, storage_mod, storage_id, dim, x, y, z \
             FROM item_events \
             WHERE event_type = 'STORAGE_SNAPSHOT' AND toDate(event_time) = toDate('{}')",
            date
        );
        if let Some(item_id) = item {
            query.push_str(&format!(" AND item_id = '{}'", item_id));
        }
        query.push_str(" ORDER BY event_time DESC");
        query.push_str(&format!(" LIMIT {}", limit));
        let rows = self.client.query(&query).fetch_all::<StorageScanEventRow>().await?;
        Ok(rows)
    }

    pub async fn ping(&self) -> Result<()> {
        let _: u8 = self.client.query("SELECT toUInt8(1)").fetch_one().await?;
        Ok(())
    }
}

use std::collections::{HashMap, VecDeque};

use crate::entities::{AnomalyRow, IngestEvent, KeyItemRule, TransferRecord};
use crate::utils::{current_millis, millis_to_utc};

#[derive(Debug, Default)]
pub struct Analyzer {
    transfer_cache: VecDeque<TransferRecord>,
    origin_seen: HashMap<String, (String, i64)>,
    key_item_windows: HashMap<(String, String), VecDeque<i64>>,
    pickup_windows: HashMap<(String, String, String), VecDeque<i64>>,
    audit_windows: HashMap<(String, String, String), VecDeque<AuditRecord>>,
    strict_pickup_windows: HashMap<(String, String), VecDeque<CountRecord>>,
}

impl Analyzer {
    pub fn analyze_batch(
        &mut self,
        events: &[IngestEvent],
        rules: &HashMap<String, KeyItemRule>,
        transfer_window_ms: i64,
        key_item_window_ms: i64,
        strict_pickup_window_ms: i64,
        strict_pickup_threshold: i64,
    ) -> Vec<AnomalyRow> {
        let now = current_millis();
        self.cleanup(now, transfer_window_ms, key_item_window_ms, strict_pickup_window_ms);

        let mut anomalies = Vec::new();
        for event in events {
            if event.item_id.trim().is_empty() || event.item_id == "minecraft:air" || event.count <= 0 {
                continue;
            }
            if event.event_type == "INVENTORY_SNAPSHOT" || event.event_type == "STORAGE_SNAPSHOT" {
                if let Some(rule) = rules.get(&event.item_id) {
                    let threshold = rule.effective_threshold();
                    if threshold > 0 && (event.count as u64) > threshold {
                        let risk = rule.effective_risk_level();
                        let (rule_id, reason) = if event.event_type == "INVENTORY_SNAPSHOT" {
                            ("R9", "Inventory snapshot exceeds threshold")
                        } else {
                            ("R12", "Storage snapshot exceeds threshold")
                        };
                        anomalies.push(self.build_anomaly(
                            event,
                            &risk,
                            rule_id,
                            reason,
                            &None,
                        ));
                    }
                }
                continue;
            }
            if event.event_type == "TRANSFER" {
                self.record_transfer(event);
                continue;
            }
            if event.event_type != "ACQUIRE" {
                continue;
            }

            let player_uuid = event.player_uuid.clone().unwrap_or_default();
            let item_fingerprint = event
                .item_fingerprint
                .clone()
                .unwrap_or_else(|| format!("{}:{}", event.item_id, event.nbt_hash.clone().unwrap_or_default()));
            let count = event.count;
            let origin_id = event.origin_id.clone().unwrap_or_default();
            let origin_type = event.origin_type.clone().unwrap_or_default();

            let transfer_match = self.find_transfer(
                &player_uuid,
                &item_fingerprint,
                count,
                transfer_window_ms,
                event.event_time,
            );
            let has_transfer = transfer_match.is_some();

            if origin_id.is_empty() && !has_transfer {
                anomalies.push(self.build_anomaly(
                    event,
                    "HIGH",
                    "R1",
                    "ACQUIRE missing origin and no transfer match",
                    &transfer_match,
                ));
            }

            let whitelist = [
                "world_pickup",
                "container_click",
                "storage_transfer",
                "craft",
                "smelt",
                "trade",
                "loot",
                "barter",
                "fishing",
                "smithing",
                "stonecutting",
                "grindstone",
                "anvil",
                "brewing",
                "loom",
                "cartography",
                "enchant",
                "inventory_audit",
                "command",
            ];
            if !origin_type.is_empty() && !whitelist.contains(&origin_type.as_str()) && !has_transfer {
                anomalies.push(self.build_anomaly(
                    event,
                    "HIGH",
                    "R2",
                    "ACQUIRE origin_type not in whitelist",
                    &transfer_match,
                ));
            }

            if !origin_id.is_empty() {
                if let Some((prev_player, prev_time)) = self.origin_seen.get(&origin_id) {
                    let delta = (event.event_time - *prev_time).abs();
                    if prev_player != &player_uuid && delta < 10_000 {
                        anomalies.push(self.build_anomaly(
                            event,
                            "HIGH",
                            "R3",
                            "Duplicate origin_id across players",
                            &transfer_match,
                        ));
                    } else if prev_player == &player_uuid
                        && !has_transfer
                        && is_world_pickup(event, &origin_type)
                    {
                        if delta < 30_000 {
                            anomalies.push(self.build_anomaly(
                                event,
                                "MEDIUM",
                                "R5",
                                "Origin id reused by same player (possible duplication)",
                                &transfer_match,
                            ));
                        } else if delta < 6 * 60 * 60 * 1000 {
                            anomalies.push(self.build_anomaly(
                                event,
                                "MEDIUM",
                                "R8",
                                "Origin id reused by same player (long window)",
                                &transfer_match,
                            ));
                        }
                    }
                }
                self.origin_seen
                    .insert(origin_id, (player_uuid.clone(), event.event_time));
            }

            if !has_transfer && is_world_pickup(event, &origin_type) {
                const DUP_PICKUP_WINDOW_MS: i64 = 15_000;
                const DUP_PICKUP_THRESHOLD: usize = 2;
                let nbt_hash = event.nbt_hash.clone().unwrap_or_default();
                let key = (player_uuid.clone(), event.item_id.clone(), nbt_hash);
                let window = self.pickup_windows.entry(key).or_default();
                window.push_back(event.event_time);
                while let Some(front) = window.front() {
                    if event.event_time - *front > DUP_PICKUP_WINDOW_MS {
                        window.pop_front();
                    } else {
                        break;
                    }
                }
                if window.len() == DUP_PICKUP_THRESHOLD {
                    anomalies.push(self.build_anomaly(
                        event,
                        "MEDIUM",
                        "R6",
                        "Rapid repeated world pickup of identical item",
                        &transfer_match,
                    ));
                }
            }

            if strict_pickup_window_ms > 0 && strict_pickup_threshold > 0 && !has_transfer && is_world_pickup(event, &origin_type) {
                let key = (player_uuid.clone(), event.item_id.clone());
                let should_alert = {
                    let window = self.strict_pickup_windows.entry(key.clone()).or_default();
                    window.push_back(CountRecord {
                        time_ms: event.event_time,
                        count: event.count,
                    });
                    while let Some(front) = window.front() {
                        if event.event_time - front.time_ms > strict_pickup_window_ms {
                            window.pop_front();
                        } else {
                            break;
                        }
                    }
                    let sum: i64 = window.iter().map(|entry| entry.count).sum();
                    sum >= strict_pickup_threshold
                };
                if should_alert {
                    anomalies.push(self.build_anomaly(
                        event,
                        "HIGH",
                        "R10",
                        "Large world pickup volume in short window",
                        &transfer_match,
                    ));
                    if let Some(window) = self.strict_pickup_windows.get_mut(&key) {
                        window.clear();
                    }
                }
            }

            if origin_type == "inventory_audit" && !has_transfer {
                const AUDIT_WINDOW_MS: i64 = 30_000;
                const AUDIT_THRESHOLD: i64 = 16;
                let nbt_hash = event.nbt_hash.clone().unwrap_or_default();
                let key = (player_uuid.clone(), event.item_id.clone(), nbt_hash);
                let window = self.audit_windows.entry(key).or_default();
                let sum_before: i64 = window.iter().map(|entry| entry.count).sum();
                window.push_back(AuditRecord {
                    time_ms: event.event_time,
                    count: event.count,
                });
                while let Some(front) = window.front() {
                    if event.event_time - front.time_ms > AUDIT_WINDOW_MS {
                        window.pop_front();
                    } else {
                        break;
                    }
                }
                let sum_after: i64 = window.iter().map(|entry| entry.count).sum();
                if sum_before < AUDIT_THRESHOLD && sum_after >= AUDIT_THRESHOLD {
                    anomalies.push(self.build_anomaly(
                        event,
                        "HIGH",
                        "R7",
                        "Inventory gain without source (rapid increase)",
                        &transfer_match,
                    ));
                }
            }

            if let Some(rule) = rules.get(&event.item_id) {
                let threshold = rule.effective_threshold();
                if threshold == 0 {
                    continue;
                }
                let key = (player_uuid.clone(), event.item_id.clone());
                let window = self.key_item_windows.entry(key).or_default();
                for _ in 0..count.max(0) {
                    window.push_back(event.event_time);
                }
                while let Some(front) = window.front() {
                    if event.event_time - *front > key_item_window_ms {
                        window.pop_front();
                    } else {
                        break;
                    }
                }
                if window.len() as u64 > threshold {
                    let risk = rule.effective_risk_level();
                    anomalies.push(self.build_anomaly(
                        event,
                        &risk,
                        "R4",
                        "Rare item threshold exceeded",
                        &transfer_match,
                    ));
                }
            }

            if has_transfer {
                anomalies.push(self.build_anomaly(
                    event,
                    "LOW",
                    "R0",
                    "Matched transfer chain",
                    &transfer_match,
                ));
            }
        }
        anomalies
    }

    fn record_transfer(&mut self, event: &IngestEvent) {
        let record = TransferRecord {
            time_ms: event.event_time,
            player_uuid: event.player_uuid.clone().unwrap_or_default(),
            player_name: event.player_name.clone().unwrap_or_default(),
            item_fingerprint: event
                .item_fingerprint
                .clone()
                .unwrap_or_else(|| format!("{}:{}", event.item_id, event.nbt_hash.clone().unwrap_or_default())),
            count: event.count,
            storage_mod: event.storage_mod.clone().unwrap_or_default(),
            storage_id: event.storage_id.clone().unwrap_or_default(),
            trace_id: event.trace_id.clone().unwrap_or_default(),
        };
        self.transfer_cache.push_back(record);
    }

    fn find_transfer(
        &self,
        player_uuid: &str,
        item_fingerprint: &str,
        count: i64,
        window_ms: i64,
        event_time: i64,
    ) -> Option<TransferRecord> {
        self.transfer_cache
            .iter()
            .rev()
            .find(|record| {
                record.player_uuid == player_uuid
                    && record.item_fingerprint == item_fingerprint
                    && record.count == count
                    && (event_time - record.time_ms).abs() <= window_ms
            })
            .cloned()
    }

    fn build_anomaly(
        &self,
        event: &IngestEvent,
        risk: &str,
        rule_id: &str,
        reason: &str,
        transfer: &Option<TransferRecord>,
    ) -> AnomalyRow {
        let evidence_json = serde_json::json!({
            "transfer": transfer,
            "origin_id": event.origin_id,
            "origin_type": event.origin_type,
            "origin_ref": event.origin_ref,
            "trace_id": event.trace_id,
        })
        .to_string();
        AnomalyRow {
            event_time: millis_to_utc(event.event_time),
            server_id: event.server_id.clone().unwrap_or_default(),
            player_uuid: event.player_uuid.clone().unwrap_or_default(),
            player_name: event.player_name.clone().unwrap_or_default(),
            item_id: event.item_id.clone(),
            count: event.count,
            risk_level: risk.to_string(),
            rule_id: rule_id.to_string(),
            reason: reason.to_string(),
            evidence_json,
        }
    }

    fn cleanup(&mut self, now: i64, transfer_window_ms: i64, key_item_window_ms: i64, strict_pickup_window_ms: i64) {
        while let Some(front) = self.transfer_cache.front() {
            if now - front.time_ms > transfer_window_ms {
                self.transfer_cache.pop_front();
            } else {
                break;
            }
        }
        for window in self.key_item_windows.values_mut() {
            while let Some(front) = window.front() {
                if now - *front > key_item_window_ms {
                    window.pop_front();
                } else {
                    break;
                }
            }
        }
        const DUP_PICKUP_WINDOW_MS: i64 = 15_000;
        let mut empty_keys = Vec::new();
        for (key, window) in self.pickup_windows.iter_mut() {
            while let Some(front) = window.front() {
                if now - *front > DUP_PICKUP_WINDOW_MS {
                    window.pop_front();
                } else {
                    break;
                }
            }
            if window.is_empty() {
                empty_keys.push(key.clone());
            }
        }
        for key in empty_keys {
            self.pickup_windows.remove(&key);
        }

        const AUDIT_WINDOW_MS: i64 = 30_000;
        let mut empty_audit = Vec::new();
        for (key, window) in self.audit_windows.iter_mut() {
            while let Some(front) = window.front() {
                if now - front.time_ms > AUDIT_WINDOW_MS {
                    window.pop_front();
                } else {
                    break;
                }
            }
            if window.is_empty() {
                empty_audit.push(key.clone());
            }
        }
        for key in empty_audit {
            self.audit_windows.remove(&key);
        }

        if strict_pickup_window_ms > 0 {
            let mut empty_strict = Vec::new();
            for (key, window) in self.strict_pickup_windows.iter_mut() {
                while let Some(front) = window.front() {
                    if now - front.time_ms > strict_pickup_window_ms {
                        window.pop_front();
                    } else {
                        break;
                    }
                }
                if window.is_empty() {
                    empty_strict.push(key.clone());
                }
            }
            for key in empty_strict {
                self.strict_pickup_windows.remove(&key);
            }
        }
    }
}

fn is_world_pickup(event: &IngestEvent, origin_type: &str) -> bool {
    if origin_type == "world_pickup" {
        return true;
    }
    matches!(event.storage_id.as_deref(), Some("world"))
}

#[derive(Clone, Copy, Debug)]
struct AuditRecord {
    time_ms: i64,
    count: i64,
}

#[derive(Clone, Copy, Debug)]
struct CountRecord {
    time_ms: i64,
    count: i64,
}

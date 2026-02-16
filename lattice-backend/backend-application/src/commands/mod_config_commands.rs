use chrono::Utc;
use sha2::{Digest, Sha256};

use crate::{AppError, AppState};
use backend_domain::{ModConfigAck, ModConfigEnvelope, ModConfigPutRequest};

pub async fn put_mod_config(
    state: &AppState,
    query_server_id: Option<String>,
    payload: ModConfigPutRequest,
) -> Result<ModConfigEnvelope, AppError> {
    let server_id = resolve_server_id(query_server_id, payload.server_id);
    let updated_by = normalize_text(payload.updated_by).unwrap_or_else(|| "desktop".to_string());
    let config_value = payload.config;
    if config_value.is_null() {
        return Err(AppError::BadRequest("config must not be null".to_string()));
    }

    let previous = {
        let cache = state.mod_configs.read().await;
        cache.get(&server_id).cloned()
    };
    let previous = if previous.is_some() {
        previous
    } else {
        state
            .config_repo
            .load_mod_config(&server_id)
            .await
            .map_err(|err| AppError::Internal(err.into()))?
    };

    let revision = previous.as_ref().map(|item| item.revision + 1).unwrap_or(1);
    let updated_at_ms = Utc::now().timestamp_millis();
    let checksum_sha256 = checksum_sha256(&config_value)?;

    let envelope = ModConfigEnvelope {
        server_id: server_id.clone(),
        revision,
        updated_at_ms,
        updated_by,
        checksum_sha256,
        config: config_value,
    };

    state
        .config_repo
        .save_mod_config(&envelope)
        .await
        .map_err(|err| AppError::Internal(err.into()))?;
    {
        let mut cache = state.mod_configs.write().await;
        cache.insert(server_id.clone(), envelope.clone());
    }
    state.mod_config_stream_hub.publish(&envelope).await;
    Ok(envelope)
}

pub async fn save_mod_config_ack(state: &AppState, mut ack: ModConfigAck) -> Result<(), AppError> {
    if ack.server_id.trim().is_empty() {
        return Err(AppError::BadRequest("server_id must not be empty".to_string()));
    }
    ack.server_id = ack.server_id.trim().to_lowercase();
    ack.status = ack.status.trim().to_uppercase();
    if ack.applied_at_ms <= 0 {
        ack.applied_at_ms = Utc::now().timestamp_millis();
    }
    if let Some(message) = ack.message.take() {
        ack.message = normalize_text(Some(message));
    }
    ack.changed_keys = ack
        .changed_keys
        .into_iter()
        .map(|item| item.trim().to_string())
        .filter(|item| !item.is_empty())
        .collect();

    state
        .config_repo
        .save_mod_config_ack(&ack)
        .await
        .map_err(|err| AppError::Internal(err.into()))?;
    let mut acks = state.mod_config_acks.write().await;
    acks.insert(ack.server_id.clone(), ack);
    Ok(())
}

fn resolve_server_id(query_server_id: Option<String>, payload_server_id: Option<String>) -> String {
    normalize_text(query_server_id)
        .or_else(|| normalize_text(payload_server_id))
        .unwrap_or_else(|| "server-01".to_string())
}

fn normalize_text(value: Option<String>) -> Option<String> {
    value.and_then(|raw| {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            None
        } else {
            Some(trimmed.to_lowercase())
        }
    })
}

fn checksum_sha256(value: &serde_json::Value) -> Result<String, AppError> {
    let bytes = serde_json::to_vec(value)
        .map_err(|err| AppError::Internal(anyhow::anyhow!("serialize config checksum failed: {err}")))?;
    let digest = Sha256::digest(bytes);
    let mut out = String::with_capacity(digest.len() * 2);
    for byte in digest {
        out.push_str(&format!("{:02x}", byte));
    }
    Ok(out)
}

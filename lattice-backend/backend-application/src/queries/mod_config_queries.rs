use crate::{AppError, AppState};
use backend_domain::{ModConfigAck, ModConfigEnvelope};

pub async fn get_mod_config(
    state: &AppState,
    server_id: &str,
) -> Result<Option<ModConfigEnvelope>, AppError> {
    let server_id = normalize_server_id(server_id);
    if server_id.is_empty() {
        return Err(AppError::BadRequest("server_id must not be empty".to_string()));
    }
    let cached = {
        let cache = state.mod_configs.read().await;
        cache.get(&server_id).cloned()
    };
    if cached.is_some() {
        return Ok(cached);
    }
    let loaded = state
        .config_repo
        .load_mod_config(&server_id)
        .await
        .map_err(|err| AppError::Internal(err.into()))?;
    if let Some(ref envelope) = loaded {
        let mut cache = state.mod_configs.write().await;
        cache.insert(server_id.clone(), envelope.clone());
    }
    Ok(loaded)
}

pub async fn pull_mod_config(
    state: &AppState,
    server_id: &str,
    after_revision: Option<u64>,
) -> Result<Option<ModConfigEnvelope>, AppError> {
    let envelope = get_mod_config(state, server_id).await?;
    let Some(item) = envelope else {
        return Ok(None);
    };
    let revision = after_revision.unwrap_or(0);
    if item.revision <= revision {
        return Ok(None);
    }
    Ok(Some(item))
}

pub async fn get_mod_config_ack(
    state: &AppState,
    server_id: &str,
) -> Result<Option<ModConfigAck>, AppError> {
    let server_id = normalize_server_id(server_id);
    if server_id.is_empty() {
        return Err(AppError::BadRequest("server_id must not be empty".to_string()));
    }
    let cached = {
        let cache = state.mod_config_acks.read().await;
        cache.get(&server_id).cloned()
    };
    if cached.is_some() {
        return Ok(cached);
    }
    let loaded = state
        .config_repo
        .load_mod_config_ack(&server_id)
        .await
        .map_err(|err| AppError::Internal(err.into()))?;
    if let Some(ref ack) = loaded {
        let mut cache = state.mod_config_acks.write().await;
        cache.insert(server_id.clone(), ack.clone());
    }
    Ok(loaded)
}

fn normalize_server_id(value: &str) -> String {
    value.trim().to_lowercase()
}

use crate::AppState;
use backend_domain::{KeyItemRule, KeyItemRuleApi};
use crate::AppError;

pub async fn update_key_items(
    state: &AppState,
    incoming_rules: Vec<KeyItemRuleApi>,
) -> Result<(), AppError> {
    let mut rules = Vec::new();
    for rule in incoming_rules.into_iter() {
        let normalized = rule.normalized();
        if normalized.item_id.is_empty() {
            return Err(AppError::BadRequest("item_id is required".to_string()));
        }
        if !normalized.item_id.contains(':') {
            return Err(AppError::BadRequest(format!(
                "invalid item_id '{}'",
                normalized.item_id
            )));
        }
        if normalized.threshold == 0 {
            return Err(AppError::BadRequest(format!(
                "threshold must be > 0 for '{}'",
                normalized.item_id
            )));
        }
        let risk = normalized.risk_level.as_str();
        if risk != "LOW" && risk != "MEDIUM" && risk != "HIGH" {
            return Err(AppError::BadRequest(format!(
                "invalid risk_level '{}' for '{}'",
                normalized.risk_level, normalized.item_id
            )));
        }
        rules.push(KeyItemRule::from(normalized));
    }
    rules.sort_by(|a, b| a.item_id.cmp(&b.item_id));
    state.config_repo.save_key_items(&state.config.key_items_path, &rules).await.map_err(|err| AppError::Internal(err.into()))?;

    let map = rules
        .into_iter()
        .map(|rule| (rule.item_id.clone(), rule))
        .collect();
    *state.key_rules.write().await = map;
    Ok(())
}

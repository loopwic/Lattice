use crate::AppState;
use backend_domain::KeyItemRuleApi;
use crate::AppError;

pub async fn list_key_items(state: &AppState) -> Result<Vec<KeyItemRuleApi>, AppError> {
    let rules = state.key_rules.read().await;
    let mut list = rules.values().map(KeyItemRuleApi::from).collect::<Vec<_>>();
    list.sort_by(|a, b| a.item_id.cmp(&b.item_id));
    Ok(list)
}

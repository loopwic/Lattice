use crate::AppState;
use backend_domain::{ItemRegistryEntry, ItemRegistryQuery};
use crate::AppError;

pub async fn list_item_registry(
    state: &AppState,
    query: ItemRegistryQuery,
) -> Result<Vec<ItemRegistryEntry>, AppError> {
    let limit = query.limit.unwrap_or(50).clamp(1, 200);
    let query_text = query.query.unwrap_or_default().trim().to_lowercase();
    let lang = query.lang.unwrap_or_else(|| "zh_cn".to_string()).to_lowercase();
    let items = state.item_registry.read().await;
    let mut results = Vec::new();
    for entry in items.iter() {
        if query_text.is_empty()
            || entry.item_id.to_lowercase().contains(&query_text)
            || entry
                .name
                .as_ref()
                .map(|name| name.to_lowercase().contains(&query_text))
                .unwrap_or(false)
            || entry
                .names
                .as_ref()
                .and_then(|names| names.get(&lang))
                .map(|name| name.to_lowercase().contains(&query_text))
                .unwrap_or(false)
            || entry
                .names
                .as_ref()
                .map(|names| {
                    names
                        .values()
                        .any(|name| name.to_lowercase().contains(&query_text))
                })
                .unwrap_or(false)
        {
            results.push(entry.clone());
            if results.len() >= limit {
                break;
            }
        }
    }
    Ok(results)
}

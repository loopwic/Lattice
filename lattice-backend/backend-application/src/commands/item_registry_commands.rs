use std::collections::HashMap;

use crate::AppState;
use backend_domain::{ItemRegistryPayload, ItemRegistryUpdateQuery};
use crate::AppError;

pub async fn update_item_registry(
    state: &AppState,
    query: ItemRegistryUpdateQuery,
    payload: ItemRegistryPayload,
) -> Result<(), AppError> {
    let mut incoming = payload
        .items
        .into_iter()
        .filter_map(|mut item| {
            item.item_id = item.item_id.trim().to_lowercase();
            if item.item_id.is_empty() || !item.item_id.contains(':') {
                return None;
            }
            if let Some(name) = &item.name {
                let trimmed = name.trim();
                item.name = if trimmed.is_empty() {
                    None
                } else {
                    Some(trimmed.to_string())
                };
            }
            if let Some(names) = &mut item.names {
                names.retain(|_, v| !v.trim().is_empty());
                if names.is_empty() {
                    item.names = None;
                } else {
                    let normalized = names
                        .iter()
                        .map(|(k, v)| (k.trim().to_lowercase(), v.trim().to_string()))
                        .collect::<HashMap<_, _>>();
                    item.names = Some(normalized);
                }
            }
            if item.namespace.is_none() || item.path.is_none() {
                let mut parts = item.item_id.splitn(2, ':');
                let namespace = parts.next().unwrap_or("").to_string();
                let path = parts.next().unwrap_or("").to_string();
                if item.namespace.is_none() && !namespace.is_empty() {
                    item.namespace = Some(namespace);
                }
                if item.path.is_none() && !path.is_empty() {
                    item.path = Some(path);
                }
            }
            Some(item)
        })
        .collect::<Vec<_>>();
    incoming.sort_by(|a, b| a.item_id.cmp(&b.item_id));

    let mode = query.mode.unwrap_or_else(|| "replace".to_string());
    let mut merged = if mode == "append" {
        state.item_registry.read().await.clone()
    } else {
        Vec::new()
    };
    if !incoming.is_empty() {
        let mut map = HashMap::new();
        for entry in merged.into_iter() {
            map.insert(entry.item_id.clone(), entry);
        }
        for entry in incoming.into_iter() {
            map.insert(entry.item_id.clone(), entry);
        }
        merged = map.into_values().collect::<Vec<_>>();
        merged.sort_by(|a, b| a.item_id.cmp(&b.item_id));
    }

    state.config_repo.save_item_registry(&state.config.item_registry_path, &merged).await.map_err(|err| AppError::Internal(err.into()))?;
    *state.item_registry.write().await = merged;
    Ok(())
}

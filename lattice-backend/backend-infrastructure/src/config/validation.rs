use anyhow::{anyhow, Result};

pub fn validate_item_id(value: &str) -> Result<()> {
    if value.trim().is_empty() {
        return Err(anyhow!("item id is empty"));
    }
    if !value.contains(':') {
        return Err(anyhow!("item id must be namespace:path"));
    }
    Ok(())
}

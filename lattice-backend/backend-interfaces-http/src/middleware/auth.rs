use std::io::Read;

use anyhow::{anyhow, Result};
use axum::http::HeaderMap;
use flate2::read::GzDecoder;

use backend_domain::{IngestEnvelope, IngestEvent, RuntimeConfig};

pub fn authorize(config: &RuntimeConfig, headers: &HeaderMap) -> bool {
    if let Some(api_token) = &config.api_token {
        return extract_bearer(headers)
            .map(|v| v == *api_token)
            .unwrap_or(false);
    }
    true
}

pub fn parse_events(headers: &HeaderMap, body: &[u8]) -> Result<Vec<IngestEvent>> {
    let content = maybe_gunzip(headers, body)?;
    let mut envelope: IngestEnvelope = serde_json::from_str(&content)?;
    if envelope.schema_version.trim() != "v2" {
        return Err(anyhow!(
            "unsupported schema_version '{}', expected 'v2'",
            envelope.schema_version
        ));
    }
    let inherited_server_id = envelope.server_id.clone();
    for event in &mut envelope.events {
        if event.server_id.is_none() {
            event.server_id = inherited_server_id.clone();
        }
    }
    Ok(envelope.events)
}

fn maybe_gunzip(headers: &HeaderMap, body: &[u8]) -> Result<String> {
    if let Some(encoding) = headers.get("Content-Encoding") {
        if encoding.to_str().unwrap_or("") == "gzip" {
            let mut decoder = GzDecoder::new(body);
            let mut out = String::new();
            decoder.read_to_string(&mut out)?;
            return Ok(out);
        }
    }
    Ok(String::from_utf8(body.to_vec())?)
}

fn extract_bearer(headers: &HeaderMap) -> Option<String> {
    let value = headers.get("Authorization")?.to_str().ok()?.trim();
    let prefix = "Bearer ";
    if !value.starts_with(prefix) {
        return None;
    }
    let token = value[prefix.len()..].trim();
    if token.is_empty() {
        return None;
    }
    Some(token.to_string())
}

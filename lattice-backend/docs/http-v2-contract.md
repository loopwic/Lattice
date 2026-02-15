# HTTP v2 Contract (Lattice)

This document defines the stable `/v2` API contract used by:
- Minecraft mod (`lattice`)
- Desktop app (`lattice-desktop`)

## Authentication
- Header: `Authorization: Bearer <token>`
- If backend `api_token` is empty/unset, auth is optional.
- If set, endpoints requiring auth return `401` when token mismatches.

## Content Encoding
- `POST /v2/ingest/events` accepts:
  - `Content-Type: application/json`
  - optional `Content-Encoding: gzip`

## Envelope
```json
{
  "schema_version": "v2",
  "server_id": "server-01",
  "events": [ ... ]
}
```
Rules:
- `schema_version` must be `v2`
- if an event omits `server_id`, backend inherits envelope `server_id`

## Endpoints

### Ingest
- `POST /v2/ingest/events`
- Body: `IngestEnvelope`
- Responses:
  - `200` accepted
  - `204` all events filtered invalid
  - `400` invalid payload/schema

### Detect
- `GET /v2/detect/anomalies?date=YYYY-MM-DD&player=<optional>`
- `GET /v2/detect/storage-scan?date=YYYY-MM-DD&item=<optional>&limit=<optional>`
- `GET /v2/detect/rules`
- `PUT /v2/detect/rules`
  - body: `{ "rules": [{"item_id":"mod:item","threshold":1,"risk_level":"LOW|MEDIUM|HIGH"}] }`

### Query
- `GET /v2/query/item-registry?query=<optional>&limit=<optional>&lang=<optional>`
- `PUT /v2/query/item-registry?mode=replace|append`
  - body: `{ "items": [ ... ] }`

### Ops
- `GET /v2/ops/rcon-config`
- `PUT /v2/ops/rcon-config`
- `GET /v2/ops/task-progress`
- `PUT /v2/ops/task-progress`
- `GET /v2/ops/alert-target/check`
- `GET /v2/ops/health/live`
- `GET /v2/ops/health/ready`
- `GET /v2/ops/metrics/prometheus`

## Error Contract
- JSON error body:
```json
{ "error": "<message>" }
```
- status mapping:
  - `400` bad request
  - `401` unauthorized
  - `404` not found
  - `500` internal error

## Compatibility Rules
- Field names are backward-compatible and must not be renamed.
- Existing `/v2` routes are stable and must not be removed.
- New fields may be added only as optional fields.

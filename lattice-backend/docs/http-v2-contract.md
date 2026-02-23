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
- `GET /v2/detect/anomalies?date=YYYY-MM-DD&player=<optional>&page=<optional>&page_size=<optional>`
- `GET /v2/detect/storage-scan?date=YYYY-MM-DD&item=<optional>&page=<optional>&page_size=<optional>`
- `GET /v2/detect/rules`
- `PUT /v2/detect/rules`
  - body: `{ "rules": [{"item_id":"mod:item","threshold":1,"risk_level":"LOW|MEDIUM|HIGH"}] }`

`anomalies` and `storage-scan` return the same paged envelope:

```json
{
  "items": [],
  "page": 1,
  "page_size": 50,
  "total_items": 0,
  "total_pages": 1
}
```

Paging constraints:
- `page >= 1`
- `page_size` 仅允许 `25 | 50 | 100 | 200`

### Query
- `GET /v2/query/item-registry?query=<optional>&limit=<optional>&lang=<optional>`
- `PUT /v2/query/item-registry?mode=replace|append`
  - body: `{ "items": [ ... ] }`

### Ops
- `GET /v2/ops/rcon-config`
- `PUT /v2/ops/rcon-config`
- `GET /v2/ops/task-progress`
- `PUT /v2/ops/task-progress`
  - payload:
    - `task: "audit" | "scan"`
    - `state: "IDLE" | "RUNNING" | "SUCCEEDED" | "FAILED"`
    - `stage: "INDEXING" | "OFFLINE_WORLD" | "OFFLINE_SB" | "OFFLINE_RS2" | "RUNTIME" | null`
    - `counters: { total, done, targets_total_by_source, done_by_source }`
    - `failure: { code, message } | null`
    - `trace_id: string | null`
    - `throughput_per_sec: number | null`
- `POST /v2/ops/op-token/issue`
  - body:
    - `server_id: string | null` (default `server-01`)
    - `player_uuid: string` (32-char no-dash hex or 36-char canonical UUID)
    - `operator_id: string` (requester ID from robot platform)
    - `group_id: string | null` (requesting group ID)
  - access control:
    - request is accepted when `operator_id` is in backend `op_token_admin_ids`
    - or when `group_id` is in backend `op_token_allowed_group_ids`
    - otherwise returns `401`
  - prerequisites:
    - target server mod-config must have `op_command_token_required = true`
    - target server mod-config must have non-empty `op_command_token_secret`
  - response:
    - `token: string` (`lattice.v1.yyyyMMdd.playerUuidNoDash.signatureHex`)
    - `day: string` (`yyyyMMdd`, server local timezone)
    - `player_uuid: string` (normalized no-dash lowercase UUID)
    - `expires_at: string` (RFC3339 timestamp of next local day `00:00`)
- `GET /v2/ops/alert-target/check`
- `GET /v2/ops/alert-deliveries?limit=<optional>`
- `GET /v2/ops/alert-deliveries/last`
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

## Contract Rules
- `/v2` field semantics follow this document as the single source of truth.
- Client and server must use the same field model; legacy field aliases are not supported.

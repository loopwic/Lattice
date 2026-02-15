# Alert Delivery Receipt

The backend stores in-memory delivery receipts for anomaly alerts.

## Trigger Rules

Alert delivery is triggered for anomaly rule IDs:

- `R4`
- `R10`
- `R12`

## Retry Policy

Each delivery uses up to 3 attempts with exponential backoff.

## Receipt APIs

- `GET /v2/ops/alert-deliveries?limit=50`
- `GET /v2/ops/alert-deliveries/last`

Receipt fields:

- `timestamp_ms`
- `status` (`success` or `failed`)
- `mode` (`http`, `ws`, or `unset`)
- `attempts`
- `alert_count`
- `rule_ids`
- `error` (optional)

## Notes

- Receipts are stored in memory (bounded queue).
- Query endpoints are protected by the same Bearer auth rule as other `/v2/ops/*` endpoints.

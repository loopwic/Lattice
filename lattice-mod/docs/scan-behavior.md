# Lattice Scan Behavior

`/lattice scan` now means a full asset scan across four sources:

1. `world_containers`: offline world-region indexing + chunk container snapshots.
2. `sb_offline`: SophisticatedBackpacks-related offline data files.
3. `rs2_offline`: Refined Storage 2 related offline data files.
4. `online_runtime`: currently loaded runtime storages (containers + RS2 network snapshots).

## Progress Contract

The mod reports scan progress to `PUT /v2/ops/task-progress` with strict fields:

- `state`: `IDLE | RUNNING | SUCCEEDED | FAILED`
- `stage`: `INDEXING | OFFLINE_WORLD | OFFLINE_SB | OFFLINE_RS2 | RUNTIME`
- `counters`: `total`, `done`, `targets_total_by_source`, `done_by_source`
- `failure`: `{ code, message }` when failed

Supported reason codes:

- `NO_TARGETS`
- `WORLD_INDEX_FAILED`
- `SB_DATA_UNAVAILABLE`
- `RS2_DATA_UNAVAILABLE`
- `HEALTH_GUARD_BLOCKED`
- `WORLD_DIR_SUBMIT_FAILED`
- `WORLD_REGION_SUBMIT_FAILED`
- `WORLD_RESULT_FAILED`
- `WORLD_QUEUE_OVERFLOW`
- `OFFLINE_TASK_SUBMIT_FAILED`
- `OFFLINE_RESULT_FAILED`
- `SB_PARSE_FAILED`
- `SB_NESTED_TRUNCATED`
- `NBT_DEPTH_TRUNCATED`

## Config Keys

The following config keys are enabled by default:

- `scan_world_offline_enabled = true`
- `scan_sb_offline_enabled = true`
- `scan_rs2_offline_enabled = true`
- `scan_offline_chunks_per_tick = 8`
- `scan_offline_sources_per_tick = 2`
- `scan_include_online_runtime = true`

## Runtime Rules

- Event ingest protocol remains `/v2/ingest/events`.
- Scan output uses `STORAGE_SNAPSHOT` events only.
- Scan execution is fail-fast: any source failure transitions to `FAILED` and stops the run.

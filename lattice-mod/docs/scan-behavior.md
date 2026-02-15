# Lattice Scan Behavior

`/lattice scan` now means a full asset scan across four sources:

1. `world_containers`: offline world-region indexing + chunk container snapshots.
2. `sb_offline`: SophisticatedBackpacks-related offline data files.
3. `rs2_offline`: Refined Storage 2 related offline data files.
4. `online_runtime`: currently loaded runtime storages (containers + RS2 network snapshots).

## Progress Contract

The mod reports scan progress to `PUT /v2/ops/task-progress` with backward-compatible optional fields:

- `reason_code`
- `reason_message`
- `targets_total_by_source`

Supported reason codes:

- `NO_TARGETS`
- `WORLD_INDEX_FAILED`
- `SB_DATA_UNAVAILABLE`
- `RS2_DATA_UNAVAILABLE`
- `HEALTH_GUARD_BLOCKED`
- `PARTIAL_COMPLETED`

## Config Keys

The following config keys are enabled by default:

- `scan_world_offline_enabled = true`
- `scan_sb_offline_enabled = true`
- `scan_rs2_offline_enabled = true`
- `scan_offline_chunks_per_tick = 8`
- `scan_offline_sources_per_tick = 2`
- `scan_include_online_runtime = true`

## Compatibility

- Event ingest protocol remains `/v2/ingest/events`.
- Scan output still uses `STORAGE_SNAPSHOT` events only.
- Existing consumers can ignore new optional progress fields.

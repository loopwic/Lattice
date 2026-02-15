# Lattice Backend - Strict DDD Multi-Crate Architecture

## Architecture Overview

This backend follows strict Domain-Driven Design principles with compile-time dependency enforcement through Rust's crate system.

## Crate Structure

```
lattice-backend/
├── backend-domain/              # Pure domain logic (NO external business deps)
│   ├── entities/                # Domain entities (AnomalyRow, IngestEvent, etc.)
│   ├── value_objects/           # Immutable value objects (RiskLevel, OriginType)
│   ├── services/                # Domain services (Analyzer)
│   └── ports/                   # Repository & service traits (interfaces)
│
├── backend-application/         # Use cases & orchestration (depends ONLY on domain)
│   ├── commands/                # Write operations (CQRS)
│   ├── queries/                 # Read operations (CQRS)
│   ├── dtos/                    # Data transfer objects
│   └── {ingest,detect,query,ops}/ # 4 Bounded Contexts
│
├── backend-infrastructure/      # Implementations of domain ports
│   ├── repositories/            # ClickHouse, Config files
│   ├── services/                # Alert, Health, Report services
│   └── config/                  # Config loading & validation
│
├── backend-interfaces-http/     # HTTP API layer (depends ONLY on application)
│   ├── routes/                  # v2 API routes
│   ├── handlers/                # Request handlers
│   ├── middleware/              # Auth, logging
│   └── error/                   # HTTP error mapping
│
└── backend-bootstrap/           # Composition root & server lifecycle
    ├── context/                 # Dependency injection container
    ├── lifecycle/               # Server startup & shutdown
    └── main.rs                  # Binary entry point
```

## Dependency Rules (Enforced by Cargo)

```
interfaces-http --> application --> domain
infrastructure --> application --> domain
bootstrap --> all layers
```

- **domain**: Zero dependencies on infrastructure (only serde, chrono, async-trait)
- **application**: Depends only on domain
- **infrastructure**: Implements domain & application ports
- **interfaces-http**: Depends only on application (calls commands/queries)
- **bootstrap**: Wires everything together

## Current Status

- [x] Domain entities/services/ports implemented from production model
- [x] Infrastructure repositories/services/config implemented
- [x] Application commands/queries/state implemented
- [x] HTTP `/v2` routes and handlers implemented
- [x] Bootstrap standalone + embedded runtime APIs implemented
- [x] Desktop now directly depends on `backend-bootstrap` from this workspace
- [x] `cargo check --workspace` passes

## Building

```bash
# Check all crates
cargo check --workspace

# Build all crates
cargo build --workspace

# Run backend server
cargo run -p backend-bootstrap

# Run tests
cargo test --workspace
```

## Migration from Old Structure

The old monolithic `lattice-backend/src/` is now a frozen migration reference.  
All active backend development must happen in the 5 workspace crates above.

The desktop app no longer consumes an embedded backend copy. It links directly to this repository (`backend-bootstrap`) as the single source of truth.

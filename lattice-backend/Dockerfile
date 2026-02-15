FROM rust:1.85-bookworm AS builder

WORKDIR /app
COPY Cargo.toml Cargo.lock ./
COPY src ./src

RUN cargo build --release

FROM debian:bookworm-slim

RUN useradd -m appuser
WORKDIR /app

COPY --from=builder /app/target/release/lattice-backend /app/lattice-backend

USER appuser
EXPOSE 3234

CMD ["/app/lattice-backend"]

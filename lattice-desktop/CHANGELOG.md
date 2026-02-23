## [0.2.10](https://github.com/loopwic/Lattice/compare/v0.2.9...v0.2.10) (2026-02-23)

### Bug Fixes

* **alert:** wait NapCat ws action ack before closing socket ([1e2bb2f](https://github.com/loopwic/Lattice/commit/1e2bb2fb4cb73f4f8d9eebf17836572906335815))

## [0.2.9](https://github.com/loopwic/Lattice/compare/v0.2.8...v0.2.9) (2026-02-23)

### Bug Fixes

* **backend:** handle NapCat group /申请 command for op token issue ([3a75c5f](https://github.com/loopwic/Lattice/commit/3a75c5ff17e097c5fadffc25d0befecdb48347b1))

## [0.2.8](https://github.com/loopwic/Lattice/compare/v0.2.7...v0.2.8) (2026-02-23)

### Bug Fixes

* **op-token:** enforce v2 bind-and-revoke flow with misuse alerts ([a502f26](https://github.com/loopwic/Lattice/commit/a502f26e2a92463e5023631df2db4347985ffd08))

## [0.2.7](https://github.com/loopwic/Lattice/compare/v0.2.6...v0.2.7) (2026-02-23)

### Bug Fixes

* **auth:** add op token issuance API and bypass FTB automation gating ([03fb7fa](https://github.com/loopwic/Lattice/commit/03fb7fa4cae90b1fe306250df7ef35fce29d3a74))

## [0.2.6](https://github.com/loopwic/Lattice/compare/v0.2.5...v0.2.6) (2026-02-23)

### Bug Fixes

* **auth:** gate all player commands requiring op level >=2 ([9c10a3b](https://github.com/loopwic/Lattice/commit/9c10a3b52f623933b08f469e44a76e75fb61fb6e))

## [0.2.5](https://github.com/loopwic/Lattice/compare/v0.2.4...v0.2.5) (2026-02-23)

### Bug Fixes

* **release:** align mod build version with release tag ([89c1b23](https://github.com/loopwic/Lattice/commit/89c1b239ad53f9671873a417a28857a095fd49b8))

## [0.2.4](https://github.com/loopwic/Lattice/compare/v0.2.3...v0.2.4) (2026-02-23)

### Bug Fixes

* **release:** build and publish assets in semantic release pipeline ([e1018cd](https://github.com/loopwic/Lattice/commit/e1018cd6d12e4c9270ad43a5c1cd58cb8edec7eb))

## [0.2.3](https://github.com/loopwic/Lattice/compare/v0.2.2...v0.2.3) (2026-02-23)

### Bug Fixes

* **release:** trigger packaging workflow only on release tags ([a95d797](https://github.com/loopwic/Lattice/commit/a95d7976e92569a03c8cc454f35e667a9c34fef2))

## [0.2.2](https://github.com/loopwic/Lattice/compare/v0.2.1...v0.2.2) (2026-02-23)

### Bug Fixes

* **ci:** do not skip release workflows on semantic release commit ([6942e48](https://github.com/loopwic/Lattice/commit/6942e48e8d465c5e20fda4488456ab1f0c89df80))

## [0.2.1](https://github.com/loopwic/Lattice/compare/v0.2.0...v0.2.1) (2026-02-23)

### Bug Fixes

* **lattice-desktop:** sync pnpm lockfile for release deps ([bf3cb36](https://github.com/loopwic/Lattice/commit/bf3cb361e43470acc9442edef85eb11a5a3f0342))

## [0.2.0](https://github.com/loopwic/Lattice/compare/v0.1.3...v0.2.0) (2026-02-23)

### Features

* add optional global OP token gate ([f75226d](https://github.com/loopwic/Lattice/commit/f75226d4400080e0a74f1ffbb8bf2071b91edae9))
* expose OP token gate config in desktop app ([f4b5887](https://github.com/loopwic/Lattice/commit/f4b5887e02b830842b1417fe78033d617b445ded))
* **lattice:** enforce fail-fast contracts and automate semantic releases ([12b9ef7](https://github.com/loopwic/Lattice/commit/12b9ef7c227c79f880d789d97445c14e560aa99e))

### Bug Fixes

* stabilize date input and table pagination ([7013b37](https://github.com/loopwic/Lattice/commit/7013b3767fe74c5c0aaa9deffb8b014c7f3d0dc4))

### Performance Improvements

* cap in-memory event queue backlog ([19ffda0](https://github.com/loopwic/Lattice/commit/19ffda00c3be0df90ad5017f2bf7b80c72d7fe12))

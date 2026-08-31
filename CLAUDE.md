# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test

Multi-module Gradle project (Kotlin DSL). 

- Java `z:\jdk\jdk-21.0.2`
- Build everything: `gradle build`
- Run all tests: `gradle test`
- Test one module: `gradle :db-sqlite:test`
- Run one test class: `gradle :events:test --tests "su.nepom.budget.events.synchronizer.EventSynchronizerTest"`
- Run one test method: `gradle :db-sqlite:test --tests "*SqliteAccountsDaoTest.some test name"`
- Run the desktop app: `gradle :desktop:run`
- Run the Access importer: `gradle :access-ingester:run` (reads `access-ingester/src/main/resources/application.yml`)

All modules use JUnit 5 (`useJUnitPlatform()`) and JVM toolchain / JavaFX version `21` (see
`gradle/libs.versions.toml`). Kotlin `2.4.0`.

Dependencies are declared through the `libs` version catalog in `gradle/libs.versions.toml`.

### Not part of the build

- `utils/` - has a `build.gradle.kts` but is **not** in `settings.gradle.kts`; ignore it.
- `demo/` - a separate standalone Gradle project (`leetcode.demo`, a JavaFX hello-world scratchpad),
  unrelated to budget.
- `budget.sqlite`, `logs/`, `_data/`, `db-sqlite/test-db.sqlite` - local runtime artifacts, not sources.

## Architecture

This is a personal double-entry budgeting app. Data lives in a local **SQLite** file, but the source of
truth is an **append-only event log** that can be synced between machines ("places") through a shared
folder of JSON files. SQLite is a materialized projection of the events.

### Modules (dependency order)

- **common** - domain model and DB-facing interfaces, no implementation. Key types:
  - `event/` - `Event<T>` (immutable, with `EventCoords` = `Place` + sequence `no`, `EventType.NEW/UPDATE`,
    `basedOn`/`conflictResolve` links), `StorableContent` / `ActualVersionContent` sealed hierarchies,
    per-kind content (`CurrencyContent`, `AccountContent`, `TransactionContent`).
  - `db/` - `Db`, `Session`, per-entity `*Dao` (extending `CrudDao<T>`), `DbListener` subscription API.
  - `model/` - value classes: `Uuid`, `Place`, `Id`/`ObjectKind`, `AccountCode`, `CurrencyCode`,
    `RawMoney`/`RawTurnover` (integer minor units), `AccountKind`.
  - `Global` - process-wide mutable `currentUser` and `currentPlace`. Must be set before DB/event work
    (`Global.setCurrentPlace(...)`); tests and `main` set it explicitly.

- **db-sqlite** - the only `Db` implementation. `createSqliteDatabase(path)` -> `SqliteDatabase`.
  - Flyway migrations in `src/main/resources/db/migration/` (`FlywayShouldRunFirst` base class runs them
    before anything else). One `SqliteDatabase` per absolute path is enforced globally.
  - Only **one write transaction** at a time (SQLite limit); `blockingMode` sessions get exclusive write
    access. Sessions are thread-bound; coroutine callers must wrap every call (and `close()`) in
    `session.coroDbOp { ... }` without switching dispatcher.
  - `EventProcessor` assigns local sequence numbers (`event.no`) to current-place events on commit.
  - `impl/` caches (`TableCopy`, `AccountRestCache`) and `EventsNotifier` / `ListenersStorage` implement
    the `Db.subscribe` change-notification feature, flushed on transaction commit.
  - `impl/EventProcessor` + `SqliteEventDao` maintain the `event` table; `account_rest` and
    `transaction_item` denormalize balances for querying.

- **events** - the file-based event store and cross-place synchronization.
  - `EventStoreWriter` / `EventStoreReader` - serialize events to
    `<root>/<place>/<yyyy>/<mm>/<dd>/<00000001-NNN>.json` (`FileContent` = list of `StorableEvent`),
    paged by `maxEventsPerFile`.
  - `synchronizer/` - `EventSynchronizer` replays remote events into the DB in a blocking, no-new-events
    session, per `ObjectKind` in order (CURRENCY -> ACCOUNT -> TRANSACTION), delegating conflicts to a
    `ConflictResolver`. `EventStorage` (currently `InMemoryEventStorage`) buffers events during a sync.

- **access-ingester** - one-shot tool that reads a legacy MS Access `.mdb` (UCanAccess driver) plus a
  "processed" SQLite bookkeeping DB, and generates the initial event stream. `Reader` + per-table
  readers under `access/dao/`, `EventsGenerator` + `generator/mapper/` produce events. Config is YAML.

- **desktop** - JavaFX UI, wired with **Dagger** (`BudgetComponent`, `@Component.Builder` takes the main
  `Stage`; modules `UiModule`, `UtilModule`, and per-dialog modules). `kapt` runs the Dagger compiler.
  - `service/` - `DbService` opens `./budget.sqlite` and holds the UI `Session` (`autoCommit = true`) as
    a JavaFX property; `EventStoreService` builds reader/writer from settings + `Global.currentPlace`;
    `SettingsService` persists app settings via `PropertyDao`.
  - `util/db/` - `ObservableEntity` / `ObservableEntitiesList` bridge DB rows and JavaFX observable
    collections, updating live from `Db.subscribe` callbacks (marshalled onto the FX thread).
  - `util/fx/` - reusable form/table plumbing (`FormDriver`, `MasterDetailFormDriver`, `FxmlService`,
    `Controller`/`ControllerMap`, `ValidatorHelper` using validatorfx). FXML lives in
    `src/main/resources/fxml/`.

### Working with the domain

- Money is always integer minor units (`RawMoney`); `desktop/util/money.kt` handles formatting/parsing.
- To change data, save `ActualVersionContent` through a `Session` (`session.save(...)` dispatches by
  `ObjectKind`); with `createEvents = true` (the default) the DB writes a corresponding `Event`.
- Entity identity is a `Uuid`; codes like `AccountCode`/`CurrencyCode` are deterministic UUID sources
  used for imported/seed data.

## Conventions

- Comments only when they add understanding, do not prefix with `TODO`; English at <= B1 level; use `-` not
  `–`/`—`. UI strings are in Russian.
- When you create a new file for a task, `git add` it immediately (stage only) so it shows in diffs.

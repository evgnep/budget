# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test

Multi-module Gradle project (Kotlin DSL). 

- Java `z:\jdk\jdk-21.0.2`, add `JAVA_HOME='...'` to every gradle command below 
- Build everything: `./gradlew gradlew build`
- Run all tests: `./gradlew test`
- Test one module: `./gradlew :db-sqlite:test`
- Run one test class: `./gradlew :events:test --tests "su.nepom.budget.events.synchronizer.EventSynchronizerTest"`
- Run one test method: `./gradlew :db-sqlite:test --tests "*SqliteAccountsDaoTest.some test name"`
- Run the desktop app: `./gradlew :desktop:run`
- Run the Access importer: `./gradlew :access-ingester:run` (reads `access-ingester/src/main/resources/application.yml`)

All modules use JUnit 5 (`useJUnitPlatform()`) and JVM toolchain / JavaFX version `21` (see
`gradle/libs.versions.toml`). Kotlin `2.4.0`. Base package for every module is `su.nepom.budget`
(group `su.nepom`), so test filters look like `--tests "su.nepom.budget.<...>"`.

Dependencies are declared through the `libs` version catalog in `gradle/libs.versions.toml`.

CI (`.github/workflows/build.yml`) runs `./gradlew build --stacktrace` on push to `master` and on
every PR (Temurin JDK 21), and uploads `**/build/reports/tests/` as an artifact.

### Packaging the desktop app

`gradle :desktop:jpackageImage` -> `desktop/build/jpackage/budget/` - a self-contained app-image
(trimmed JRE via the `org.beryx.runtime` plugin, no installer, windowless `budget.exe` launcher).
Main class is `su.nepom.budget.desktop.BudgetApplicationKt`. The explicit JVM `modules` / `runtime`
option list lives in `desktop/build.gradle.kts`; re-check it with `gradle :desktop:suggestModules`.

### Not part of the build

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

- **desktop** - JavaFX UI, wired with **Dagger** (`kapt` runs the compiler). Entry point
  `BudgetApplication` (a JavaFX `Application`); `main()` sets `Global.setCurrentUser("")` then builds
  `DaggerBudgetComponent` with the main `Stage` (`@Component.Builder.mainStage(...)`). The main window
  is a `BorderPane` with a vertical nav `ToolBar`; the accounts / currencies / sync screens swap into
  its center, while "Операции", "Остатки", history and dialogs open as separate windows. Closing the
  main window calls `Platform.exit()`; `BudgetApplication.stop()` calls `EventStoreService.onStop()`.

  - **DI shape.** `BudgetComponent` (`@Singleton`) pulls in `UiModule` (which `includes` one module
    per feature package - `configuration`, `currency`, `account`, `conflict`, `balance`, `transaction`,
    `history`, `sync`) and `UtilModule`. Each feature `*Module` binds its controllers `@IntoMap
    @ClassKey(...)` into a `ControllerMap` (`Map<Class<*>, Provider<Controller>>`). `FxmlService.load(
    "<pkg>/<file>.fxml", stage, stageOwner, controllerSetup)` loads from `/fxml/`, resolves the
    controller from that map (never `fx:controller` in FXML), runs `controllerSetup`, then calls
    `StageAwareController` / `StageOwnerAwareController.initialize(...)` if implemented.

  - `service/` (all `@Singleton`):
    - `DbService` - opens `./budget.sqlite` (absolute, normalized) and exposes the UI `Session`
      (`autoCommit = true`) as a `ReadOnlyObjectProperty<Session?>`; `openExisting()` / `create()`.
    - `SettingsService` - `creator` and `place` are `CheckableDatabaseStringProperty`s (persisted via
      `session.propertyDao`); on change they push into `Global.setCurrentUser` / `setCurrentPlace`.
    - `EventStoreService` - the autosave + sync engine. Runs on a background `CoroutineScope(
      Dispatchers.Default)` under a `Mutex`; opens its own `createEvents = false` session for writing
      the file store. Subscribes to `Db` changes and, after local (non-`AccountRest`) events, schedules
      a debounced flush (10 min "if no new events", hard 30 min cap); `saveNow()` / `syncNow()` force
      it. Sync delegates to `events`' `EventSynchronizer`. Publishes read-only FX properties
      `hasEventsToSync`, `syncInProgress`, `savingGap`, `storeInfo`, `lastSyncResult`; `eventStoreFolder`
      is a checkable DB property; `onStop()` does a final flush with `runBlocking`.
    - `WindowStateService` - persists each window's bounds + maximized flag to `./settings.json`
      (debounced 400 ms). Call `bind(stage, key)` before `stage.show()`; off-screen positions are not
      restored.
    - `AccountService` / `CurrencyService` - in-memory lookups used by pickers and formatters.

  - `ui/WindowManager` (`@Singleton`) - opens detached, ownerless `Stage`s. Every call makes a fresh
    instance, so the same screen can be open several times; titles are auto-numbered ("Операции (2)");
    controllers implementing `Disposable` are disposed on hide. `openTransactions(initialFilter?)`,
    `openBalances()`, `openHistory(uuid, kind, title)`.

  - `ui/conflict/DesktopConflictResolver` - implements `events`' `ConflictResolver`; the sync loop runs
    off the FX thread, so it marshals a modal dialog per conflict via `Platform.runLater` +
    `CompletableDeferred`.

  - `util/db/` - `ObservableEntity<C>` wraps one DB row as JavaFX `Observable`s plus its `content`.
    `ObservableEntitiesList<T>` is an `ObservableListWrapper` kept live from the DB: it loads initial
    rows from the `Session`, subscribes per `Db.SubscribeKind`, applies change events on the FX thread,
    and only mutates during internal ops (keyed by `Uuid`). The concrete entities are
    `model/{Account,Currency,Transaction}Observable`. `CheckableDatabaseProperty<T>` is a single value
    stored through `propertyDao`, validated on set and reloaded when the session changes.

  - `util/fx/` - reusable form/table plumbing. `FormDriver` is a state machine
    (`EMPTY`/`VIEW`/`EDIT`/`NEW`) over validatorfx-checked fields: OK validates, shows error/warning
    alerts, then either `builder.saveAndUpdate(session)` or (conflict-resolution mode) hands the built
    `ActualVersionContent` to a `contentSink`; `editItem` / `showReadOnly` cover the history form.
    `MasterDetailFormDriver` ties a table `SelectionModel` + a "new" button to a `FormDriver`, reverting
    the selection when the form refuses to leave edit state. Also `ValidatorHelper`, `runAndShowError`,
    `WeakListeners`. FXML lives in `src/main/resources/fxml/`.

  - Runtime files land next to the working dir: `./budget.sqlite`, `./settings.json`, `logs/`
    (logback).

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

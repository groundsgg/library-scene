# scene-minestom Runtime Design

**Status:** PROPOSED — approved in conversation, awaiting review of this written specification  
**Parent:** [Scene, NPC & Resource Pack Platform — Concept and Master Plan](https://grounds.atlassian.net/wiki/spaces/GARCHITECT/pages/257622018)  
**Target repository:** [groundsgg/library-scene](https://github.com/groundsgg/library-scene)  
**Target artifact:** `gg.grounds:scene-minestom`  
**Runtime baseline:** Kotlin/JVM 25 and the Grounds Minestom dependency baseline

## Outcome

`scene-minestom` instantiates one already decoded and catalog-validated `SceneDocument` in one
Minestom `Instance`. It renders active props, composite props, NPC bodies, labels, and interaction
bounds; evaluates look, hover, proximity, conditions, debounce, and cooldowns; and executes the
complete Scene v1 action model without mutating the immutable document.

The module is a generic runtime. It does not know Grounds model naming, custom-model identifiers,
resource-pack layout, lobby menus, map-service APIs, or application behavior. Hosts supply asset
renderers, effects, player policy, runtime identity, and application actions through explicit
interfaces.

This is the first of two Phase 7 subprojects. A separate design will integrate the released runtime
into `minestom-lobby`, register the first Grounds actions and renderers, and add one real lobby Scene.

## Goals

* Create and close a complete Scene runtime atomically for one Minestom Instance.
* Keep entity work proportional to active spatial cells, active handles, and nearby players rather
  than every element in the document.
* Preserve the format's world/local transform, trigger, condition, action-order, and per-viewer
  semantics.
* Support asynchronous application actions without blocking the Instance thread or applying stale
  completions.
* Leave concrete asset and application behavior in the consuming application.
* Publish one focused runtime artifact without changing Scene JSON or the `scene-format` API.

## Non-goals

* Concrete Grounds item models, NPC skins, animations, sounds, particles, or resource-pack bytes.
* `minestom-lobby` wiring, lobby actions, or a production lobby Scene.
* Reading `scene.json`, map bundles, catalogs, pins, or configuration from disk or a network.
* Paper support, editor previews, service-maps integration, or PackSet delivery.
* A second runtime document model, arbitrary scripts, console commands, or platform callbacks in
  Scene JSON.
* Nested composite props, group transforms, whole-Scene transforms, physics, pathfinding, or a
  general entity-component framework.
* Microbenchmarks or a large synthetic performance suite. Performance is protected through bounded
  algorithms and focused work-count assertions.

## Module and dependency boundary

The repository adds `scene-minestom` beside `scene-format` and `scene-testkit`.

`scene-minestom` depends on:

* `scene-format` as its immutable domain and validation boundary;
* Minestom and Adventure from the Grounds dependency baseline;
* SLF4J for structured operational logging.

It must not depend on resourcepacks, service-maps, plugin APIs, lobby code, Paper/Bukkit, network
clients, Jackson, or `scene-testkit` at runtime. Minestom types are allowed in this module's public
API because the artifact is the platform adapter.

`scene-format` remains semantically and binary unchanged. No new JSON field, asset metadata field,
or application-action representation is introduced by this work.

## Runtime ownership and creation

One `SceneRuntime` belongs to exactly one tuple of:

* immutable `SceneDocument`;
* exact `AssetCatalog` and `ActionCatalog` referenced by that document;
* Minestom `Instance`;
* host-provided runtime identity, registries, policy, effects, clock, and configuration.

Creation is result-based. A factory performs readiness before it registers a listener, schedules a
task, or creates an entity. Readiness includes:

1. `SceneValidation.validateCatalogs` succeeds for the exact catalogs.
2. Every Prop and NPC-body asset used by the Scene has exactly one compatible renderer factory.
3. Every Sound and Particle used by an action is supported by the supplied effect sink.
4. Every referenced Application Action has exactly one registered handler.
5. Runtime configuration is internally valid.
6. Runtime-specific references and target capabilities are complete.

Failure returns a deterministic, non-empty list of `SceneRuntimeProblem` values containing a stable
code, document path where applicable, element identity where applicable, and message. Creation
never returns a partially installed runtime.

Successful creation installs one child event node and one shared scheduler task. `close()` is
idempotent and removes the event node, cancels the task, increments the runtime generation, closes
all active handles, and clears every player, binding, and animation state.

## Host interfaces

### Runtime identity

`SceneRuntimeIdentity` carries the Scene ID plus the host's immutable map identity and version. It
is copied into application-action and diagnostic contexts. The runtime treats map identity as an
opaque printable value and never parses a Grounds address.

### Asset renderer registry

`SceneAssetRendererRegistry` resolves an `AssetKey` and expected `AssetKind` to a renderer factory.
There is no fallback based on key spelling or resource-pack path.

A factory activates one asset in an Instance and returns a `RenderedAssetHandle`. The handle owns
all concrete visual entities for that asset and supports:

* applying its current world transform;
* applying or clearing per-viewer scale and highlight state;
* starting, stopping, and advancing a catalog-approved animation from a supplied logical elapsed
  time;
* deterministic, idempotent close.

The handle may implement per-viewer state using packet overrides or viewer-specific replicas. The
runtime does not expose or assume that choice. Renderer callbacks run on the Instance thread and
must not block.

### Effect sink

`SceneEffectSink` declares support for catalog-backed Sound and Particle keys during readiness. At
runtime it plays a sound for one triggering player or emits a particle at a resolved world target.
The sink decides how an asset key maps to Minestom data.

### Player policy

`ScenePlayerPolicy` answers whether a player participates in the Scene and whether that player has
a requested permission. Sneaking, hand, and game mode come from the Minestom event/player state;
permission integration remains host-owned.

### Application action registry

`SceneActionRegistry` resolves each `ActionKey` to one handler. A handler receives an immutable
`SceneActionContext` containing runtime identity, player, element ID, trigger, hand when present,
accepted timestamp, immutable arguments, and a snapshot of relevant viewer state.

Handlers return `CompletionStage<SceneActionResult>` and may complete on any thread. They must not
receive mutable runtime internals. Results are `Success`, `Rejected` with a safe diagnostic, or
`Failure` with a safe diagnostic and optional cause for logging.

## Spatial activation

The immutable Scene is indexed once during creation. Root element positions determine their cells;
Composite Parts stay with their root and never receive independent activation cells.

The default runtime configuration is:

* cell edge: 32 blocks;
* activation distance: 64 blocks;
* deactivation distance: 80 blocks;
* deactivation grace period: 5 seconds;
* spatial membership evaluation: every 10 server ticks;
* look, hover, proximity, and animation update: every server tick, with no work for empty active
  sets;
* at most 256 handle activation/deactivation transitions per tick.

Hosts may replace these values with positive finite distances, a deactivation distance not smaller
than activation distance, a non-negative grace period, positive tick intervals, and a positive
transition budget.

`ActivationPolicy.ALWAYS` elements activate during installation and bypass spatial deactivation.
`AUTOMATIC` elements activate when at least one eligible player is within activation distance.
They become eligible for deactivation only when no eligible player is within deactivation distance;
the grace period must then elapse continuously. Re-entry cancels pending deactivation.

Transitions beyond the per-tick budget remain queued deterministically by cell, element ID, then
transition kind. Closing the runtime bypasses the budget and closes all handles immediately.

## Element rendering and transforms

### Props

An active Prop owns one renderer handle. Its world transform is the document transform. Its initial
animation becomes its initial logical animation state.

### Composite Props

An active Composite Prop owns one renderer handle per part. The runtime combines the root world
transform and part local transform using the Scene v1 order `yaw → pitch → roll`, including
non-uniform scale. Parts are activated and closed atomically with their root.

### NPCs

The NPC body is a renderer handle. The runtime itself owns the Minestom label and interaction
representation so trigger geometry and label behavior are consistent across hosts. Label position
is the transformed NPC position plus the transformed label offset. Interaction bounds begin as the
NPC's authored local bounds and are transformed for queries without changing the document.

`LookBehavior.Fixed` preserves the authored rotation. `TrackNearest` chooses the closest eligible
player inside `maxDistance`, uses stable player-UUID ordering for equal distances, optionally limits
rotation to yaw, and advances by no more than `maxTurnDegreesPerSecond` for the supplied clock
delta. Losing the target leaves the last runtime rotation in place until a new target appears or the
handle is reactivated, at which point it starts from the authored rotation.

## Logical state across activation

Deactivating an element destroys only its concrete rendering handles. Logical state remains in the
runtime:

* current animation and its logical start/stop time;
* per-viewer scale and highlight values;
* binding debounce, in-flight, and cooldown state;
* proximity membership for connected eligible players.

Reactivation creates fresh handles and reapplies current logical state. Animation elapsed time is
derived from the injected monotonic clock, so visual animation can resume rather than silently
restart. Viewer state persists until an inverse Scene action, player disconnect, player-policy
exclusion, or runtime close. The format contains no implicit timeout, so the runtime invents none.

## Viewer state, hover, and proximity

Viewer state is keyed by player UUID and element ID. No Minestom `Player` is retained after a
disconnect.

Hover uses a server-authoritative ray against the NPC's transformed authored bounds. Only active,
visible NPCs in spatially relevant cells are candidates. A stable nearest-distance then element-ID
order resolves overlaps. State transitions emit `HOVER_ENTER` and `HOVER_LEAVE`; remaining over
the same element emits no repeated trigger.

Proximity uses the authored enter and exit radii. Crossing inward emits `PROXIMITY_ENTER`; crossing
outward past the larger exit radius emits `PROXIMITY_LEAVE`. The two radii provide hysteresis and
prevent boundary flapping.

Scale and highlight changes are applied only to the targeted player's visual state. They never
change the shared transform, another player's view, or the Scene document.

## Trigger ingestion and conditions

The runtime listens only to the Minestom events needed for Scene v1 clicks and player lifecycle.
Tick-derived hover and proximity events enter the same trigger pipeline as click events.

Each candidate binding is evaluated in document order. Conditions are an AND-list:

* `HandCondition` matches the event hand;
* `SneakingCondition` matches current player state;
* `PermissionCondition` calls `ScenePlayerPolicy`;
* `GameModeCondition` matches the neutral Scene game-mode value.

Multiple bindings remain the format's OR mechanism. A player excluded by policy produces no Scene
trigger and has any existing hover/proximity/view state cleared.

## Debounce, in-flight work, and cooldown

State is per player UUID, NPC element ID, and binding index.

After trigger and conditions match, debounce suppresses a second matching input inside
`debounceMillis`. At most one action chain for that state key may be in flight. A successful chain
completion starts `cooldownMillis`. Rejected or failed chains do not start cooldown, but the debounce
window still prevents an immediate failure storm.

Disconnect, policy exclusion, element deactivation, or runtime close invalidates the associated
generation. Element deactivation preserves cooldown/debounce timestamps but invalidates in-flight
side effects and concrete handles. A later reactivation does not resurrect an old continuation.

## Ordered action execution

Actions execute strictly in document order. The next action begins only after the previous action
has completed successfully.

The runtime implements safe actions as follows:

* start/stop animation updates logical animation state and applies it to an active target handle;
* play sound delegates to `SceneEffectSink` for the triggering player;
* set viewer scale/highlight updates logical viewer state and an active target handle;
* message, action bar, and title use Minestom's Adventure audience methods;
* particle resolves the target's current world transform and delegates to `SceneEffectSink`;
* application action invokes the pre-resolved host handler.

An action may target an inactive element. State-changing actions update logical state and are
applied on later activation. A one-shot particle action against an inactive target still uses its
deterministically resolved document transform; it does not activate the element.

Every asynchronous completion is marshalled back to the Instance thread before advancing the
chain. It captures runtime, element, viewer, and chain generations. A mismatched generation becomes
an observable stale completion and performs no further side effect.

## Threading and time

All runtime state, Minestom entities, listeners, renderer handles, and effect calls are owned by the
Instance thread. Public lifecycle entry points reject calls from an invalid lifecycle state rather
than trying to synchronize mutable entity state.

Durations use an injected monotonic `SceneClock`; wall-clock time is never used for debounce,
cooldown, grace, animation, or turn-rate calculations. Production uses a system monotonic clock;
tests use a manually advanced clock.

Application handlers are the only deliberately asynchronous boundary. Completion callbacks never
touch runtime state until scheduled back onto the Instance thread.

## Error handling and observability

Readiness failures are structured return values. Runtime failures are isolated to their current
element, player, or action chain and logged with runtime identity, Scene ID, element ID, player UUID,
trigger, binding index, and stable failure code. Authored Adventure text and application arguments
are not dumped wholesale into logs.

An exception from a renderer, effect sink, or application handler aborts the current action chain.
It does not stop the shared scheduler or another player's work. A handle that fails during activation
is closed best-effort and the element enters a failed inactive state with a diagnostic; it is not
left partially visible. Repeated automatic retry is not part of v1 because it could create an
unbounded failure loop.

Stale completions are debug-observable but are not operational errors. Repeated scheduler-level
exceptions are guarded at the per-item boundary so one bad item cannot cancel the scheduler task.

## Focused verification strategy

Verification is deliberately risk-based rather than exhaustive.

1. Pure tests cover transform composition and transformed bounds with one Prop, Composite Prop,
   and NPC fixture.
2. Spatial tests cover activation, hysteresis, grace, `ALWAYS`, and the transition budget with a
   manually advanced clock.
3. One trigger/action suite covers conditions, document order, debounce, successful cooldown, one
   failed action, and an asynchronous stale completion after disconnect/close.
4. One viewer suite covers hover overlap ordering, proximity hysteresis, and per-viewer state
   isolation.
5. One Minestom integration test proves install, click dispatch, entity/listener creation, and full
   close cleanup against a real test Instance.
6. Dependency and public-API gates prove the new artifact does not pull in Grounds applications,
   services, Paper, Jackson, or test frameworks.
7. A focused work-count assertion proves a distant inactive population is not visited by the
   per-tick element-update loop. No wall-clock benchmark is required.

Tests use small fixtures, fake host interfaces, and a manual clock. There is no separate runtime
testkit artifact in this release.

## Delivery

The implementation uses a dedicated feature worktree and conventional commits. CI runs the normal
repository build plus the focused runtime checks. The README documents module consumption, host
interfaces, lifecycle, defaults, and a minimal fake-renderer example.

After merge, Release Please owns the version PR, tag, GitHub Release, and Maven publication. No tag
or release is created manually. Because this is a backward-compatible feature after `0.1.0`, the
expected next library release is `0.2.0`; Release Please remains authoritative.

The Phase 7 masterplan is updated only after the artifact resolves remotely and its published bytes
match the reviewed build. That update records this runtime subproject as delivered while leaving
the separate `minestom-lobby` vertical integration open.

## Acceptance criteria

* A valid Scene and exact catalogs create one atomic runtime for one Minestom Instance.
* Missing renderer, effect, or application capabilities fail before any side effect.
* `AUTOMATIC` elements use bounded spatial activation with hysteresis and grace; `ALWAYS` elements
  remain active.
* Props, Composite Parts, NPC bodies, labels, transformed interaction bounds, and look behavior have
  deterministic lifecycle and transforms.
* Hover, proximity, click conditions, debounce, in-flight exclusion, cooldown, and all Scene v1
  actions have a tested execution path.
* Viewer scale and highlight are isolated per player.
* Ordered asynchronous action chains cannot block the Instance thread or apply stale completions.
* Closing the runtime removes listeners, scheduler work, entities, handles, and retained players.
* Per-tick work excludes distant inactive elements.
* `scene-format` and Scene JSON remain unchanged.
* `scene-minestom` contains no Grounds application, service, resource-pack, or Paper dependency.


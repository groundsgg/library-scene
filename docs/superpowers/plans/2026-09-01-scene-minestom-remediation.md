# scene-minestom Runtime Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve the remaining scoped-review findings with Minestom chunk tracking, generation-safe activation dispatch, consistent root/part state, finite transition work, complete diagnostics, and fail-fast readiness.

**Architecture:** Replace the custom sensor grid with Minestom `EntityTracker` queries plus a small oversized-hover exception set. Make automatic activation transitions epoch-owned from queue through claim, make renderer ownership terminal on every scheduler path, and make root/part state use one effective-state calculation for live and reactivated handles. Keep the existing public runtime shape; update only the already-introduced transition and Java bridge surface where the corrected state semantics require it.

**Tech Stack:** Kotlin 2.2.20, Java 25, Minestom 2026.07.12-26.2 through `gg.grounds:grounds-dependencies:1.0.0`, Gradle, JUnit 5, SLF4J.

**Spec:** `docs/superpowers/specs/2026-09-01-scene-minestom-remediation-design.md`

## Global Constraints

- Use Minestom chunk/entity tracking; do not add a custom AABB tree, quadtree, or general-purpose spatial dependency.
- A normal hover query uses the five-block interaction reach plus one 16-block chunk of bound extent; larger/off-center current bounds use an explicit oversized-hover set.
- Proximity queries Minestom's `PLAYERS` tracker from each active NPC with its authored `exitRadius`; do not create a global maximum-radius player query.
- Invisible elements create no renderer, label, or Interaction; invisible NPCs may emit proximity but never hover or click.
- Queued and pending automatic activation is desire-epoch owned and cannot dispatch or publish after eligibility disappears.
- All normal Minestom/resource mutation stays on the Instance thread; scheduler rejection must still resolve public operations, retain the cause, and lose no delivered handle.
- Root actions clear property-specific part overrides; later part actions override only that part.
- Only actively transitioning viewer keys are pumped; completed transitions are finalized and retired.
- Scene ID mismatch returns before any host capability lookup.
- No cluster deployment, manual tag, or manual release. Release Please owns version PRs, tags, and releases.
- Keep tests risk-focused. Do not add a benchmark suite or `net.minestom:testing`.

---

### Task 1: Replace the custom sensor grid with Minestom chunk queries

**Files:**
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/view/MinestomSensorQueries.kt`
- Modify: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/view/NpcSensorEngine.kt`
- Modify: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/DefaultSceneRuntime.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/internal/view/ViewerStateTest.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/SceneRuntimeLifecycleIntegrationTest.kt`

**Interfaces:**
- Consumes: Minestom `Instance.getEntityTracker()`, `EntityTracker.Target.ENTITIES`, `EntityTracker.Target.PLAYERS`, runtime-owned Interaction UUIDs, `ActiveElement.currentBounds()`, authored `Npc.proximity`, and `ScenePlayerPolicy`.
- Produces: `MinestomSensorQueries`, a chunk-backed `NpcSensorEngine` with unchanged `SensorTransition` output, deterministic candidate counters, and no custom grid/global-radius state.

```kotlin
internal class MinestomSensorQueries(
    private val instance: Instance,
    private val interactionReach: Double = 5.0,
    private val normalHoverExtent: Double = 16.0,
) {
    fun hoverInteractions(eye: Point): List<Entity>
    fun proximityPlayers(point: Point, radius: Double): List<Player>
}

internal class NpcSensorEngine(
    private val instance: Instance,
    private val playerPolicy: ScenePlayerPolicy,
    private val interactionReach: Double = 5.0,
    private val raycaster: BoundsRaycaster = BoundsRaycaster(),
    private val queries: MinestomSensorQueries = MinestomSensorQueries(instance, interactionReach),
) {
    fun activate(element: ActiveElement)
    fun deactivate(elementId: LocalId)
    fun update(players: List<Player>): List<SensorTransition>
    fun visitedNpcCount(): Int
}
```

- [ ] **Step 1: Write failing chunk-query sensor tests**

Add focused cases with real Minestom entities:

```kotlin
@Test fun `hover uses nearby Minestom Interaction chunks and exact ray ordering`()
@Test fun `large off-center current bounds remain hoverable through overflow candidates`()
@Test fun `proximity queries Minestom players per npc and retains membership while inactive`()
@Test fun `sensor candidate work excludes distant Interaction chunks`()
```

The overflow test uses bounds extending more than 16 blocks from the Interaction center and a ray that intersects only that extension. The work test places 10 Interaction entities in nearby chunks and 1,000 in distant chunks and asserts `visitedNpcCount() == 10`.

- [ ] **Step 2: Run the focused suite and verify RED**

Run:

```bash
./gradlew :scene-minestom:test --tests '*ViewerStateTest' --tests '*SceneRuntimeLifecycleIntegrationTest'
```

Expected: compilation or assertions fail because `NpcSensorEngine` still uses its private cell map and global maximum radius.

- [ ] **Step 3: Implement the Minestom query adapter**

Use `nearbyEntitiesByChunkRange` for hover. Derive the fixed range as:

```kotlin
val hoverChunkRange = ceil((interactionReach + normalHoverExtent) / 16.0).toInt()
```

Filter entities through the runtime's active Interaction UUID map. Deduplicate candidates by element ID and exact-test them afterward. Use `nearbyEntities(point, radius, Target.PLAYERS, ...)` for proximity and return stable UUID order.

- [ ] **Step 4: Implement active/overflow ownership**

On activation, record the current Interaction UUID and place visible NPCs whose horizontal current bounds exceed `normalHoverExtent` from the Interaction position into `oversizedHover`. On every runtime transform update, recompute overflow membership. Deactivation removes active/Interaction/overflow ownership but retains `nearby` membership. Invisible NPCs enter only the proximity path.

For each player, hover candidates are the union of nearby Interaction entities and `oversizedHover`, followed by the existing exact ray/distance/ID ordering. For each active NPC with a proximity sensor, query nearby Minestom players and reconcile existing membership so exit, policy exclusion, and disconnect remain correct.

- [ ] **Step 5: Run focused and module tests**

Run:

```bash
./gradlew :scene-minestom:test --tests '*ViewerStateTest' --tests '*SceneRuntimeLifecycleIntegrationTest'
./gradlew :scene-minestom:test :scene-minestom:spotlessCheck
```

Expected: PASS; no custom `SensorCellKey`, `cells`, or `maximumRadius` remains.

- [ ] **Step 6: Commit**

```bash
git add scene-minestom/src/main scene-minestom/src/test
git commit -S -m "fix(scene): use Minestom chunks for npc sensing"
```

### Task 2: Make activation dispatch and renderer completion terminally generation-safe

**Files:**
- Modify: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/spatial/ActivationController.kt`
- Modify: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/runtime/ElementActivator.kt`
- Modify: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/DefaultSceneRuntime.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/internal/spatial/SpatialActivationTest.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/internal/runtime/ElementActivatorTest.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/SceneRuntimeLifecycleIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 1 sensors, current `ElementActivation` claim/abort ownership, transition budget, player-policy lifecycle, and Instance scheduler.
- Produces: epoch-bearing `ActivationTransition`, atomic `beginActivation`, deterministic scheduler-rejection completion, and no queued/pending activation after lost eligibility.

```kotlin
internal data class ActivationTransition(
    val elementId: LocalId,
    val kind: ActivationTransitionKind,
    val epoch: Long,
)

internal class ActivationController {
    fun reevaluate(eligiblePlayerPositions: List<Point>): List<LocalId>
    fun drainTransitions(): List<ActivationTransition>
    fun beginActivation(transition: ActivationTransition): Boolean
    fun isActivationCurrent(elementId: LocalId, epoch: Long): Boolean
    fun markActive(elementId: LocalId, epoch: Long): Boolean
}
```

- [ ] **Step 1: Write failing epoch and scheduler tests**

Add:

```kotlin
@Test fun `budget queued activation is discarded after last eligible player leaves`()
@Test fun `dispatch rejects an activation transition with a stale desire epoch`()
@Test fun `scheduler rejection after handle delivery closes the handle and completes activation`()
@Test fun `creation continuation rejection completes failure instead of hanging`()
```

The budget test queues more than `transitionBudgetPerTick`, removes the player before the target transition drains, and proves no renderer factory is invoked. The handle test completes a renderer stage on a foreign thread while the injected owner scheduler throws and asserts close count one plus exceptional activation completion.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew :scene-minestom:test --tests '*SpatialActivationTest' --tests '*ElementActivatorTest' --tests '*SceneRuntimeLifecycleIntegrationTest'
```

Expected: the delayed transition starts later and the delivered handle is absent from activation ownership when scheduler submission throws.

- [ ] **Step 3: Add desire epochs to the activation state machine**

Maintain `desireEpoch: MutableMap<LocalId, Long>` and `desired: MutableSet<LocalId>`. Increment the epoch whenever automatic activation desire changes. Queue `ACTIVATE` with the current epoch; cancel queued/pending activation when desire becomes false. `beginActivation` atomically validates kind, epoch, desire, and current state before moving to `activating`.

Runtime dispatch must call `beginActivation(transition)` before invoking the renderer. Store `(epoch, ElementActivation)` in `pendingActivations`. Completion and claim require `isActivationCurrent`; disconnect/policy reconciliation calls `reevaluate` immediately and aborts returned activating IDs.

- [ ] **Step 4: Record delivered handles before scheduling**

Split renderer observation into delivery and owner-thread completion:

```kotlin
stage.whenComplete { handle, error ->
    val delivery = activation.recordDelivery(index, handle, error)
    try {
        activation.schedule { activation.finishDelivery(delivery) }
    } catch (scheduleError: Throwable) {
        activation.failDelivery(delivery, scheduleError)
    }
}
```

`recordDelivery` synchronizes ownership before scheduling. `failDelivery` terminally fails exactly once and closes the delivered handle even when it was never placed into an owner-thread callback. Guard registration and callback bodies. Preserve original error as primary and scheduler/cleanup errors as suppressed.

- [ ] **Step 5: Resolve runtime continuation rejection**

Replace log-only continuation scheduling with a rejection callback:

```kotlin
private fun scheduleContinuation(
    onRejected: (Throwable) -> Unit,
    action: () -> Unit,
)
```

Initial creation rejection aborts activation and completes `creation` with `RUNTIME_FAILURE`. Automatic rejection aborts the owner and prevents publication. The rejection callback may complete thread-safe futures and activation ownership but must not read Instance-owned maps.

- [ ] **Step 6: Run focused and module verification**

Run:

```bash
./gradlew :scene-minestom:test --tests '*SpatialActivationTest' --tests '*ElementActivatorTest' --tests '*SceneRuntimeLifecycleIntegrationTest'
./gradlew :scene-minestom:test :scene-minestom:spotlessCheck :scene-minestom:build
```

Expected: PASS; late handles close once, public stages never hang, stale budget transitions never invoke a renderer.

- [ ] **Step 7: Commit**

```bash
git add scene-minestom/src/main scene-minestom/src/test
git commit -S -m "fix(scene): invalidate stale activation dispatch"
```

### Task 3: Unify root/part effective state and retire completed transitions

**Files:**
- Modify: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/runtime/LogicalElementState.kt`
- Modify: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/view/ViewerStateStore.kt`
- Modify: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/action/SceneActionExecutor.kt`
- Modify: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/runtime/ElementActivator.kt`
- Modify: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/DefaultSceneRuntime.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/internal/action/SceneActionExecutorTest.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/internal/runtime/ElementActivatorTest.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/internal/view/ViewerStateTest.kt`

**Interfaces:**
- Consumes: resolved `(element, optionalPart)` targets, existing public transition snapshots, active handle entries, and injected `SceneClock`.
- Produces: one effective-state calculation used by live actions, tick progression, and reactivation; an active-transition key set whose work reaches zero.

```kotlin
internal class LogicalElementState {
    fun animationFor(partId: LocalId?): LogicalAnimationState
    fun setAnimation(partId: LocalId?, value: LogicalAnimationState)
}

internal class ViewerStateStore {
    fun setScale(key: ViewerElementKey, multiplier: Double, transitionMillis: Long)
    fun setHighlight(key: ViewerElementKey, enabled: Boolean, transitionMillis: Long)
    fun effectiveState(playerId: UUID, elementId: LocalId, partId: LocalId?): SceneViewerVisualState
    fun activeTransitionCount(): Int
}
```

- [ ] **Step 1: Write failing precedence and quiescence tests**

Add:

```kotlin
@Test fun `root animation clears part overrides live and after reactivation`()
@Test fun `root scale clears only part scale overrides and retains part highlights`()
@Test fun `root highlight clears only part highlight overrides and retains part scales`()
@Test fun `later part action overrides root only for that part`()
@Test fun `completed viewer transition sends one final update then retires`()
@Test fun `zero duration viewer state never enters transition pump`()
```

Use two composite part handles and a manual clock. Assert live handle calls and a newly activated replacement receive identical effective states.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
./gradlew :scene-minestom:test --tests '*SceneActionExecutorTest' --tests '*ElementActivatorTest' --tests '*ViewerStateTest'
```

Expected: root live updates all handles but stale part overrides win after reactivation; completed transitions remain non-null and continue callbacks.

- [ ] **Step 3: Implement property-specific override clearing**

For animation, `setAnimation(null, value)` clears the animation map before writing the root. Part writes preserve the root.

Store scale and highlight logical values independently so root scale can clear only part scale entries and root highlight can clear only part highlight entries. Compose `effectiveState` from part override or root fallback per property. After a root mutation, apply each active handle's effective state rather than broadcasting the raw root state.

- [ ] **Step 4: Maintain only active transition keys**

Add `activeTransitions: MutableSet<ViewerElementKey>`. Nonzero scale/highlight writes add the key; zero duration removes it if no other transition exists. The tick copies only this set in deterministic order. Evaluation that reaches duration stores the target, clears that transition field, sends one final update, and removes the key when both fields are complete.

Player removal, state clear, and root property override removal must remove corresponding active keys. `activeTransitionCount()` remains internal test evidence.

- [ ] **Step 5: Reuse effective state during activation and tick**

`ElementActivator` reapplies `animationFor(partId)` only. `DefaultSceneRuntime` and `SceneActionExecutor` call `effectiveState` for each concrete handle entry. No path may interpret `handlesFor(null)` as a raw-state broadcast that bypasses part precedence.

- [ ] **Step 6: Run focused and module verification**

Run:

```bash
./gradlew :scene-minestom:test --tests '*SceneActionExecutorTest' --tests '*ElementActivatorTest' --tests '*ViewerStateTest'
./gradlew :scene-minestom:test :scene-minestom:spotlessCheck :scene-minestom:build
```

Expected: PASS; `activeTransitionCount()` returns zero after completion and callback counts stop increasing.

- [ ] **Step 7: Commit**

```bash
git add scene-minestom/src/main scene-minestom/src/test
git commit -S -m "fix(scene): unify resolved viewer state"
```

### Task 4: Fail readiness early, preserve scheduler diagnostics, and close delivery gates

**Files:**
- Modify: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/SceneReadiness.kt`
- Modify: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/action/SceneActionExecutor.kt`
- Modify: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/DefaultSceneRuntime.kt`
- Modify: `scene-minestom/src/test/resources/abi/public-api.txt` only if Task 1–3 intentionally changed a supported public signature
- Modify: `README.md` only if corrected behavior changes documented host usage
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/SceneReadinessTest.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/internal/action/SceneActionExecutorTest.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/SceneRuntimeLifecycleIntegrationTest.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/SceneMinestomDependencyBoundaryTest.kt`
- Test: `scene-minestom/src/test/java/gg/grounds/scene/minestom/SceneMinestomJavaApiTest.java`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/SceneMinestomApiManifestTest.kt`

**Interfaces:**
- Consumes: completed Tasks 1–3, immutable readiness snapshots, `SceneActionDiagnostic`, Java bridge/API manifest, and Release Please-owned versioning.
- Produces: zero capability calls after Scene ID mismatch, cause-retaining fallback diagnostics without off-thread state reads, and a verified merge candidate.

- [ ] **Step 1: Write failing readiness and diagnostic tests**

Add:

```kotlin
@Test fun `scene id mismatch returns before every capability lookup`()
@Test fun `application scheduler rejection reports the original cause without current-state read`()
@Test fun `runtime creation scheduler rejection completes a structured failure`()
```

The readiness test uses renderer/effect/action registries whose methods increment counters and throw if called; all counters remain zero. The action test injects a scheduler exception, a current predicate that counts calls, and a recording reporter; assert `FAILED`, unchanged post-completion current count, code `APPLICATION_CALLBACK_SCHEDULE_FAILED`, and identical cause.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
./gradlew :scene-minestom:test --tests '*SceneReadinessTest' --tests '*SceneActionExecutorTest' --tests '*SceneRuntimeLifecycleIntegrationTest'
```

Expected: mismatch still invokes capability methods and scheduler rejection completes silently.

- [ ] **Step 3: Return immediately on identity mismatch**

At the start of `SceneReadiness.check`, before iterating catalogs/elements or calling a host object, return `SceneReadinessResult` with the existing deterministic `INVALID_CONFIG` problem at `identity/sceneId` and empty immutable capability maps.

- [ ] **Step 4: Report scheduler rejection without runtime reads**

Capture only immutable `PendingActionChain` fields before registration. When callback scheduler submission throws, invoke the thread-safe fallback reporter directly with code `APPLICATION_CALLBACK_SCHEDULE_FAILED`, outcome `FAILED`, stable safe text, and the original cause; then complete `FAILED`. Do not call `isCurrent`, access players, or inspect runtime ownership on that thread.

`DefaultSceneRuntime` supplies an Instance-thread reporter for normal paths and a thread-safe SLF4J fallback for marshal rejection. Reporter failure remains isolated from the chain outcome.

- [ ] **Step 5: Reconcile API manifest and documentation**

Run the manifest test. If Tasks 1–3 changed only internal types, `public-api.txt` must remain byte-identical. If the transition/Java public surface changed intentionally, regenerate the deterministic manifest and update README examples to match. Do not change release version files or generated Release Please artifacts.

- [ ] **Step 6: Run the complete delivery gate**

Run:

```bash
./gradlew clean check publishToMavenLocal
./gradlew :scene-minestom:test --tests '*SceneMinestomDependencyBoundaryTest' --tests '*SceneMinestomJavaApiTest' --tests '*SceneMinestomApiManifestTest'
./gradlew :scene-minestom:dependencies --configuration runtimeClasspath
git diff --check
git status --short
```

Inspect the production JAR with the available JDK `jar tf`. Expected: all tests pass, Maven Local contains all three same-version modules, no test/ABI resource in the production JAR, no forbidden runtime dependency, no Release Please-owned file changed, and only intended tracked edits.

- [ ] **Step 7: Commit**

```bash
git add README.md scene-minestom
git commit -S -m "test(scene): close runtime remediation gates"
```

After this task, the controller performs task-scoped reviews, one strongest-model whole-branch review, verification-before-completion, protected push/merge, and Release Please-only delivery. No implementation agent performs external delivery.

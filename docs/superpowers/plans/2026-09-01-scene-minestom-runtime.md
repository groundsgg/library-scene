# scene-minestom Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish a generic `gg.grounds:scene-minestom` runtime that atomically instantiates validated Scene v1 documents in one Minestom Instance with bounded spatial activation, per-viewer interactions, and generation-safe ordered actions.

**Architecture:** Add one platform adapter module to `library-scene`. Public host interfaces supply asset rendering, effects, permissions/player eligibility, identity, and asynchronous application actions; private components own affine transform math, spatial activation, runtime handles, viewer state, trigger state, and action sequencing. One `SceneRuntime` attaches one child event node and one task to an Instance and tears both down deterministically.

**Tech Stack:** Kotlin/JVM 25, `scene-format`, Minestom `2026.07.12-26.2` through `gg.grounds:grounds-dependencies:1.0.0`, Adventure, SLF4J 2.0.18, Gradle, JUnit 5.11.4, Release Please.

**Spec:** `docs/superpowers/specs/2026-09-01-scene-minestom-runtime-design.md`

## Global Constraints

- `scene-format` and Scene JSON remain semantically and binary unchanged.
- The new published coordinate is exactly `gg.grounds:scene-minestom`.
- The runtime must not depend on resourcepacks, service-maps, plugin APIs, lobby code, Paper/Bukkit, network clients, Jackson, or `scene-testkit` at runtime.
- Asset keys are resolved only through `SceneAssetRendererRegistry` and `SceneEffectSink`; never infer model, sound, or particle data from key spelling.
- One runtime owns exactly one immutable Scene document and one Minestom Instance.
- Readiness completes before listeners, tasks, or entities are installed; failed creation leaves no side effect.
- `AUTOMATIC` uses cells, distance hysteresis, grace, and a transition budget; `ALWAYS` bypasses spatial deactivation.
- Preserve root/local transform stacks for rotated non-uniform Composite Props; do not collapse them into a lossy position/rotation/scale tuple.
- All mutable runtime and Minestom state stays on the Instance thread. Only application handlers may complete elsewhere.
- Every asynchronous continuation re-enters through `Instance.scheduler()` and checks runtime, element, viewer, and chain generations.
- Release Please exclusively owns version PRs, tags, GitHub Releases, and Maven publication; never create a tag or release manually.
- Keep verification focused on boundary risks; do not add a runtime testkit, wall-clock benchmark, or combinatorial test matrix.

---

### Task 1: Scaffold the module and define the host/readiness boundary

**Files:**
- Modify: `settings.gradle.kts`
- Create: `scene-minestom/build.gradle.kts`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/SceneRuntimeConfig.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/SceneRuntimeIdentity.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/SceneClock.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/SceneRendering.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/SceneEffects.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/ScenePlayerPolicy.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/SceneActions.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/SceneRuntimeProblem.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/SceneReadiness.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/SceneRuntimeConfigTest.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/SceneReadinessTest.kt`

**Interfaces:**
- Consumes: all immutable Scene v1 types and `SceneValidation.validateCatalogs` from `scene-format`.
- Produces: the complete public host SPI and deterministic readiness problems used by every later task.

Public types must use these signatures:

```kotlin
package gg.grounds.scene.minestom

import gg.grounds.scene.format.*
import java.util.concurrent.CompletionStage
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

data class SceneRuntimeConfig(
    val cellEdge: Double = 32.0,
    val activationDistance: Double = 64.0,
    val deactivationDistance: Double = 80.0,
    val deactivationGraceMillis: Long = 5_000,
    val spatialIntervalTicks: Int = 10,
    val transitionBudgetPerTick: Int = 256,
)

data class SceneRuntimeIdentity(
    val sceneId: SceneId,
    val mapId: String,
    val mapVersion: Long,
)

fun interface SceneClock {
    fun nanoTime(): Long
}

data class SceneRenderTransform(val root: Transform, val local: Transform?)
data class SceneViewerVisualState(val scaleMultiplier: Double = 1.0, val highlighted: Boolean = false)

data class SceneAssetRenderContext(
    val instance: Instance,
    val elementId: LocalId,
    val partId: LocalId?,
    val asset: AssetKey,
    val transform: SceneRenderTransform,
)

fun interface SceneAssetRendererFactory {
    fun create(context: SceneAssetRenderContext): CompletionStage<RenderedAssetHandle>
}

fun interface SceneAssetRendererRegistry {
    fun rendererFor(asset: AssetKey, kind: AssetKind): SceneAssetRendererFactory?
}

interface RenderedAssetHandle : AutoCloseable {
    fun applyTransform(transform: SceneRenderTransform)
    fun applyViewerState(player: Player, state: SceneViewerVisualState)
    fun clearViewerState(player: Player)
    fun startAnimation(animation: LocalId, elapsedMillis: Long)
    fun stopAnimation(animation: LocalId?)
    fun advanceAnimation(elapsedMillis: Long)
    override fun close()
}

interface SceneEffectSink {
    fun supports(asset: AssetKey, kind: AssetKind): Boolean
    fun playSound(player: Player, sound: AssetKey, volume: Double, pitch: Double)
    fun emitParticle(instance: Instance, particle: AssetKey, point: Point, count: Int, offset: Vec3, speed: Double)
}

interface ScenePlayerPolicy {
    fun isEligible(player: Player): Boolean
    fun hasPermission(player: Player, permission: String): Boolean
}

fun interface SceneActionHandler {
    fun execute(context: SceneActionContext): CompletionStage<SceneActionResult>
}

fun interface SceneActionRegistry {
    fun handlerFor(key: ActionKey): SceneActionHandler?
}

data class SceneActionContext(
    val identity: SceneRuntimeIdentity,
    val player: Player,
    val elementId: LocalId,
    val trigger: SceneTrigger,
    val hand: SceneHand?,
    val acceptedNanos: Long,
    val arguments: Map<LocalId, ApplicationArgument>,
    val viewerState: SceneViewerVisualState,
)

sealed interface SceneActionResult {
    data object Success : SceneActionResult
    data class Rejected(val diagnostic: String) : SceneActionResult
    data class Failure(val diagnostic: String, val cause: Throwable? = null) : SceneActionResult
}

enum class SceneRuntimeProblemCode {
    INVALID_SCENE,
    INVALID_CONFIG,
    MISSING_RENDERER,
    MISSING_EFFECT,
    MISSING_ACTION_HANDLER,
    ACTIVATION_FAILED,
    RUNTIME_FAILURE,
}

data class SceneRuntimeProblem(
    val code: SceneRuntimeProblemCode,
    val path: String,
    val elementId: LocalId?,
    val message: String,
)
```

- [ ] **Step 1: Add the module include and dependency boundary**

Add `include("scene-minestom")` to `settings.gradle.kts`. Use this exact dependency shape in the new build file so public Minestom signatures and their authoritative BOM travel together:

```kotlin
dependencies {
    api(project(":scene-format"))
    api(platform("gg.grounds:grounds-dependencies:1.0.0"))
    api("net.minestom:minestom")
    implementation("org.slf4j:slf4j-api")

    testImplementation(project(":scene-testkit"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
```

- [ ] **Step 2: Write failing configuration and readiness tests**

Test defaults and reject non-finite/non-positive distances, deactivation below activation, negative grace, and non-positive intervals/budgets. Build one valid fixture, then independently omit its Prop renderer, NPC renderer, Sound support, Particle support, and Application Action handler. Assert stable sorted codes:

```kotlin
assertEquals(
    listOf(
        SceneRuntimeProblemCode.MISSING_ACTION_HANDLER,
        SceneRuntimeProblemCode.MISSING_EFFECT,
        SceneRuntimeProblemCode.MISSING_RENDERER,
    ),
    SceneReadiness.check(request).map(SceneRuntimeProblem::code).distinct(),
)
```

- [ ] **Step 3: Run the focused tests and verify RED**

Run: `./gradlew :scene-minestom:test --tests '*SceneRuntimeConfigTest' --tests '*SceneReadinessTest'`  
Expected: compilation fails because the public contracts and `SceneReadiness` do not exist.

- [ ] **Step 4: Implement immutable contracts, validation, and ordered diagnostics**

Use constructor checks in `SceneRuntimeConfig`. Define `SceneRuntimeProblemCode` with at least `INVALID_SCENE`, `INVALID_CONFIG`, `MISSING_RENDERER`, `MISSING_EFFECT`, `MISSING_ACTION_HANDLER`, `ACTIVATION_FAILED`, and `RUNTIME_FAILURE`. `SceneReadiness.check` must collect all pre-installation problems, then sort by path, code name, element ID, and message.

Renderer readiness scans Prop assets, Composite Part assets as `PROP`, and NPC bodies as `NPC_BODY`. Effect readiness scans `PlaySoundAction` and `EmitParticleAction`. Application readiness scans `ApplicationAction` values. Do not invoke a factory, sink effect, or action handler during readiness.

- [ ] **Step 5: Run module tests and dependency insight**

Run: `./gradlew :scene-minestom:test :scene-minestom:dependencyInsight --dependency net.minestom:minestom --configuration runtimeClasspath`  
Expected: tests pass and Minestom resolves to `2026.07.12-26.2` through `grounds-dependencies:1.0.0`.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts scene-minestom
git commit -S -m "feat(scene): define Minestom runtime boundary"
```

### Task 2: Implement exact transform math and bounded spatial activation

**Files:**
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/geometry/AffineTransform.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/geometry/TransformedBounds.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/spatial/SpatialIndex.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/spatial/ActivationController.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/internal/geometry/SceneTransformsTest.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/internal/spatial/SpatialActivationTest.kt`

**Interfaces:**
- Consumes: `SceneRenderTransform`, Scene `Transform`, `LocalBounds`, root element IDs/policies, `SceneClock`, and `SceneRuntimeConfig` from Task 1.
- Produces: `AffineTransform`, `WorldBounds`, `SpatialIndex`, and deterministic `ActivationTransition` batches for Tasks 3, 4, and 7.

```kotlin
internal data class CellKey(val x: Int, val z: Int)
internal data class IndexedElement(val id: LocalId, val policy: ActivationPolicy, val position: Vec3)
internal enum class ActivationTransitionKind { ACTIVATE, DEACTIVATE }
internal data class ActivationTransition(val elementId: LocalId, val kind: ActivationTransitionKind)

internal class SpatialIndex(elements: List<IndexedElement>, private val cellEdge: Double) {
    fun candidates(point: Point, radius: Double): List<LocalId>
    fun visitedElementCount(): Int
}

internal class ActivationController(
    private val index: SpatialIndex,
    private val config: SceneRuntimeConfig,
    private val clock: SceneClock,
) {
    fun evaluate(eligiblePlayerPositions: List<Point>): List<ActivationTransition>
    fun markActive(elementId: LocalId)
    fun markInactive(elementId: LocalId)
    fun markFailed(elementId: LocalId)
}
```

- [ ] **Step 1: Write failing transform tests**

Use fixed numeric fixtures for negative coordinates, `yaw → pitch → roll`, root translation, root scale, and a rotated local part under non-uniform root scale. Assert transformed corners rather than decomposed Euler values. The composite test must fail if implementation merely adds rotations or multiplies positions without applying root rotation/scale.

- [ ] **Step 2: Run transform tests and verify RED**

Run: `./gradlew :scene-minestom:test --tests '*SceneTransformsTest'`  
Expected: compilation fails because affine helpers do not exist.

- [ ] **Step 3: Implement affine composition and bounds**

Build the root matrix as `translation * yawY * pitchX * rollZ * scale`; multiply the optional local matrix on the right. Transform all eight local-bound corners. Keep the original `SceneRenderTransform` for renderers and use the affine matrix only for queries.

```kotlin
internal fun SceneRenderTransform.affine(): AffineTransform =
    AffineTransform.from(root).let { rootMatrix ->
        local?.let { rootMatrix * AffineTransform.from(it) } ?: rootMatrix
    }
```

- [ ] **Step 4: Write failing spatial tests**

Cover negative cell flooring, `ALWAYS` immediate activation, `AUTOMATIC` activation at 64 blocks, no deactivation inside 80 blocks, continuous five-second grace, re-entry cancellation, stable transition ordering, and a 256-transition batch with leftovers returned next evaluation. Add one work-count assertion where 10 nearby elements are visited and 1,000 distant elements are not.

- [ ] **Step 5: Implement the immutable grid and activation state machine**

Use `floor(coordinate / cellEdge).toInt()` for cell keys. Query only intersecting cells. Sort candidates and transitions by cell coordinates, element ID value, then transition kind. Store timestamps in monotonic nanoseconds with overflow-safe elapsed subtraction.

- [ ] **Step 6: Run focused tests**

Run: `./gradlew :scene-minestom:test --tests '*SceneTransformsTest' --tests '*SpatialActivationTest'`  
Expected: PASS; the work-count assertion reports only nearby candidates.

- [ ] **Step 7: Commit**

```bash
git add scene-minestom/src/main scene-minestom/src/test
git commit -S -m "feat(scene): add bounded spatial activation"
```

### Task 3: Activate and close renderer handles atomically

**Files:**
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/runtime/LogicalElementState.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/runtime/ActiveElement.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/runtime/ElementActivator.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/runtime/NpcPlatformEntities.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/internal/runtime/ElementActivatorTest.kt`

**Interfaces:**
- Consumes: renderer SPI/readiness from Task 1 and affine/render-transform results from Task 2.
- Produces: generation-owned `ActiveElement` records, Interaction-entity lookup, label lifecycle, and logical animation state used by Tasks 4–7.

```kotlin
internal data class LogicalAnimationState(val animation: LocalId?, val startedNanos: Long?)
internal data class LogicalElementState(
    val element: SceneElement,
    var animation: LogicalAnimationState,
    var generation: Long,
)

internal data class ActiveElement(
    val elementId: LocalId,
    val generation: Long,
    val handles: List<RenderedAssetHandle>,
    val npcEntities: NpcPlatformEntities?,
) : AutoCloseable {
    override fun close() {
        npcEntities?.close()
        handles.asReversed().forEach(RenderedAssetHandle::close)
    }
}

internal data class NpcPlatformEntities(
    val label: Entity?,
    val interaction: Entity,
) : AutoCloseable {
    override fun close() {
        label?.remove()
        interaction.remove()
    }
}

internal class ElementActivator(
    private val instance: Instance,
    private val renderers: SceneAssetRendererRegistry,
    private val clock: SceneClock,
) {
    fun activate(state: LogicalElementState): CompletionStage<ActiveElement>
    fun deactivate(active: ActiveElement)
}
```

- [ ] **Step 1: Write failing activation/rollback tests**

Use fake factories returning controllable futures. Assert one Prop creates one handle, a Composite Prop creates parts in part-ID order, and an NPC creates body plus runtime-owned label/Interaction entities. Fail the second Composite factory and assert the first handle closes. Complete a factory after generation invalidation and assert its handle closes without becoming active.

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew :scene-minestom:test --tests '*ElementActivatorTest'`  
Expected: compilation fails because the activation types do not exist.

- [ ] **Step 3: Implement logical state and renderer activation**

Call renderer factories on the Instance thread. Preserve root/local stacks in `SceneAssetRenderContext`. Combine factory futures without blocking. When all complete, re-check element generation before publishing `ActiveElement`; otherwise close all completed handles.

Resume a logical animation with:

```kotlin
val elapsedMillis = state.animation.startedNanos?.let { (clock.nanoTime() - it) / 1_000_000 } ?: 0
state.animation.animation?.let { animation -> handle.startAnimation(animation, elapsedMillis) }
```

- [ ] **Step 4: Implement runtime-owned NPC platform entities**

Create `Entity(EntityType.TEXT_DISPLAY)` for a non-null label and `Entity(EntityType.INTERACTION)` for all NPCs. Configure `TextDisplayMeta.setText`, `InteractionMeta.setWidth`, `setHeight`, and `setResponse(true)`. Use the transformed center and an axis-aligned enclosing width/height only as the client click target; exact authored transformed bounds remain the server-side acceptance check. Track Interaction entity UUID to NPC ID. Entity `setInstance` futures participate in the same atomic activation and rollback.

- [ ] **Step 5: Run activation tests**

Run: `./gradlew :scene-minestom:test --tests '*ElementActivatorTest'`  
Expected: PASS, including partial-failure and stale-future cleanup.

- [ ] **Step 6: Commit**

```bash
git add scene-minestom/src/main scene-minestom/src/test
git commit -S -m "feat(scene): manage Minestom element handles"
```

### Task 4: Implement viewer state, hover, proximity, and look

**Files:**
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/view/ViewerStateStore.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/view/BoundsRaycaster.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/view/NpcSensorEngine.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/view/LookController.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/internal/view/ViewerStateTest.kt`

**Interfaces:**
- Consumes: active NPCs/transformed bounds from Tasks 2–3, `ScenePlayerPolicy`, `SceneClock`, and renderer viewer-state methods.
- Produces: stable hover/proximity transitions, per-viewer visual state, and bounded NPC rotations consumed by Task 5 and Task 7.

```kotlin
internal data class ViewerElementKey(val playerId: UUID, val elementId: LocalId)
internal data class SensorTransition(val playerId: UUID, val elementId: LocalId, val trigger: SceneTrigger)

internal class ViewerStateStore {
    fun visualState(key: ViewerElementKey): SceneViewerVisualState
    fun setScale(key: ViewerElementKey, multiplier: Double): SceneViewerVisualState
    fun setHighlight(key: ViewerElementKey, enabled: Boolean): SceneViewerVisualState
    fun removePlayer(playerId: UUID)
    fun clear()
}

internal class NpcSensorEngine {
    fun update(players: List<Player>, activeNpcs: List<ActiveElement>): List<SensorTransition>
    fun removePlayer(playerId: UUID): List<SensorTransition>
}
```

- [ ] **Step 1: Write one focused viewer/sensor suite**

Cover two overlapping NPC bounds resolving by ray distance then element ID, no repeated hover event while unchanged, hover leave, proximity enter/exit hysteresis, two-player visual isolation, and disconnect cleanup. Add one look case for UUID tie-breaking, yaw-only mode, and maximum turn delta from the manual clock.

- [ ] **Step 2: Run the suite and verify RED**

Run: `./gradlew :scene-minestom:test --tests '*ViewerStateTest'`  
Expected: compilation fails because view components do not exist.

- [ ] **Step 3: Implement exact ray and sensor behavior**

Transform the player ray into each NPC's local bounds using the inverse affine transform and run a slab intersection. Reject hits behind the eye or outside interaction reach. Query only active spatial candidates. Preserve proximity membership by UUID/element and emit transitions in player UUID, element ID, trigger order.

- [ ] **Step 4: Implement visual state and bounded look**

Store only UUID keys. Resolve live players through the Instance before applying handle state. Calculate the desired yaw/pitch from NPC position to player eye position; clamp angular change to `maxTurnDegreesPerSecond * elapsedSeconds`. Equal-distance target selection uses UUID string order.

- [ ] **Step 5: Run the focused suite**

Run: `./gradlew :scene-minestom:test --tests '*ViewerStateTest'`  
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add scene-minestom/src/main scene-minestom/src/test
git commit -S -m "feat(scene): add per-viewer NPC sensing"
```

### Task 5: Evaluate conditions, debounce, in-flight state, and cooldown

**Files:**
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/trigger/SceneTriggerInput.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/trigger/ConditionEvaluator.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/trigger/BindingStateStore.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/trigger/TriggerEngine.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/internal/trigger/SceneTriggerPipelineTest.kt`

**Interfaces:**
- Consumes: neutral Scene bindings/conditions, policy from Task 1, sensor transitions from Task 4, and manual clock.
- Produces: accepted ordered `PendingActionChain` values and completion/invalidation operations for Task 6.

```kotlin
internal data class SceneTriggerInput(
    val playerId: UUID,
    val npcId: LocalId,
    val trigger: SceneTrigger,
    val hand: SceneHand?,
    val acceptedNanos: Long,
)

internal data class BindingKey(val playerId: UUID, val npcId: LocalId, val bindingIndex: Int)
internal data class PendingActionChain(
    val key: BindingKey,
    val generation: Long,
    val input: SceneTriggerInput,
    val actions: List<SceneAction>,
)

internal class TriggerEngine {
    fun accept(input: SceneTriggerInput): List<PendingActionChain>
    fun complete(key: BindingKey, generation: Long, succeeded: Boolean, completedNanos: Long)
    fun invalidatePlayer(playerId: UUID)
    fun invalidateElement(elementId: LocalId)
    fun clear()
}
```

- [ ] **Step 1: Write the failing trigger suite**

In one parameterized condition test cover main/off hand, sneaking, permission, and game mode. Then assert document-order bindings, debounce from accepted input, one in-flight chain, cooldown only after success, no cooldown after failure, player invalidation, and element-generation invalidation. Left-click inputs use `SceneHand.MAIN`; hover/proximity inputs use null and cannot satisfy `HandCondition`.

- [ ] **Step 2: Run the suite and verify RED**

Run: `./gradlew :scene-minestom:test --tests '*SceneTriggerPipelineTest'`  
Expected: compilation fails because trigger components do not exist.

- [ ] **Step 3: Implement deterministic condition and binding state**

Resolve the live Player once per input and reject absent, wrong-Instance, or policy-ineligible players. Evaluate every condition without side effects. Key state by UUID/NPC/binding index; increment generation for every newly accepted chain and every invalidation. Preserve cooldown/debounce timestamps over spatial deactivation while invalidating in-flight generation.

- [ ] **Step 4: Run the trigger suite**

Run: `./gradlew :scene-minestom:test --tests '*SceneTriggerPipelineTest'`  
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scene-minestom/src/main scene-minestom/src/test
git commit -S -m "feat(scene): enforce trigger execution state"
```

### Task 6: Execute every Scene v1 action in strict asynchronous order

**Files:**
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/action/ActionTargetResolver.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/action/SceneActionExecutor.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/internal/action/SceneActionExecutorTest.kt`

**Interfaces:**
- Consumes: `PendingActionChain` from Task 5, logical/active targets from Task 3, viewer state from Task 4, effects/action handlers from Task 1, and `Instance.scheduler()`.
- Produces: generation-checked `CompletionStage<ChainOutcome>` and logical target updates for Task 7.

```kotlin
internal enum class ChainOutcome { SUCCEEDED, REJECTED, FAILED, STALE }

internal class SceneActionExecutor(
    private val instance: Instance,
    private val identity: SceneRuntimeIdentity,
    private val elements: Map<LocalId, LogicalElementState>,
    private val viewers: ViewerStateStore,
    private val effects: SceneEffectSink,
    private val actions: SceneActionRegistry,
    private val isCurrent: (PendingActionChain) -> Boolean,
) {
    fun execute(chain: PendingActionChain): CompletionStage<ChainOutcome>
}
```

- [ ] **Step 1: Write the failing ordered-action suite**

Use one compact test per behavior category, not per concrete argument combination:

1. animation start/stop and inactive-target persistence;
2. viewer scale/highlight isolation;
3. sound/particle delegation and message/action-bar/title delivery;
4. Application Action success/rejection/failure;
5. a controllable asynchronous first action proving the second does not start early;
6. completion after runtime/player/element invalidation proving `STALE` and no later side effect.

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew :scene-minestom:test --tests '*SceneActionExecutorTest'`  
Expected: compilation fails because the executor does not exist.

- [ ] **Step 3: Implement target resolution and safe actions**

Resolve `ElementTarget.part` only inside the named Composite Prop and fail closed for impossible targets. Update logical animation/viewer state before applying to an optional active handle. Resolve particle points from the target affine transform without activating it. Convert title millisecond durations to Minestom/Adventure durations without truncating negative values because format validation has already rejected them.

- [ ] **Step 4: Implement asynchronous application actions and sequencing**

Build `SceneActionContext` from immutable snapshots. Catch synchronous handler exceptions and failed stages. After every stage, use `instance.scheduler().execute { ... }`, re-check `isCurrent(chain)`, and only then start the next action. Complete once with `ChainOutcome`; never block with `join()` or `get()`.

- [ ] **Step 5: Run the action suite**

Run: `./gradlew :scene-minestom:test --tests '*SceneActionExecutorTest'`  
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add scene-minestom/src/main scene-minestom/src/test
git commit -S -m "feat(scene): execute ordered runtime actions"
```

### Task 7: Compose the atomic SceneRuntime and Minestom event lifecycle

**Files:**
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/SceneRuntime.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/SceneRuntimeFactory.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/DefaultSceneRuntime.kt`
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/MinestomSceneEvents.kt`
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/SceneRuntimeLifecycleIntegrationTest.kt`

**Interfaces:**
- Consumes: every component from Tasks 1–6.
- Produces: the supported public creation/lifecycle API and one fully integrated Runtime.

```kotlin
data class SceneRuntimeRequest(
    val scene: SceneDocument,
    val assets: AssetCatalog,
    val actions: ActionCatalog,
    val identity: SceneRuntimeIdentity,
    val instance: Instance,
    val renderers: SceneAssetRendererRegistry,
    val effects: SceneEffectSink,
    val playerPolicy: ScenePlayerPolicy,
    val actionRegistry: SceneActionRegistry,
    val clock: SceneClock = SceneClock { System.nanoTime() },
    val config: SceneRuntimeConfig = SceneRuntimeConfig(),
)

sealed interface SceneRuntimeCreationResult {
    data class Success(val runtime: SceneRuntime) : SceneRuntimeCreationResult
    data class Failure(val problems: List<SceneRuntimeProblem>) : SceneRuntimeCreationResult
}

interface SceneRuntime {
    val identity: SceneRuntimeIdentity
    val isClosed: Boolean
    fun close(): CompletionStage<Void>
}

object SceneRuntimeFactory {
    fun create(request: SceneRuntimeRequest): CompletionStage<SceneRuntimeCreationResult>
}
```

- [ ] **Step 1: Write one real lifecycle integration test**

Follow the established lobby pattern: call `MinecraftServer.init()` once in `@BeforeAll`, create one registered `InstanceContainer`, create `Player(FakeConnection(), GameProfile(...))`, and set the player into the Instance. Build one NPC with right- and left-click bindings and fake host interfaces.

Assert:

* creation installs exactly one child node and one live task;
* `PlayerEntityInteractEvent` maps to `RIGHT_CLICK` with its actual hand;
* `EntityAttackEvent` from the Player maps to `LEFT_CLICK` with `SceneHand.MAIN`;
* `PlayerDisconnectEvent` clears viewer/binding state;
* `close()` completes only after it removes the child node, cancels the task, removes NPC platform entities, closes the body handle, and is idempotent.

- [ ] **Step 2: Run the integration test and verify RED**

Run: `./gradlew :scene-minestom:test --tests '*SceneRuntimeLifecycleIntegrationTest'`  
Expected: compilation fails because factory/runtime/event adapter do not exist.

- [ ] **Step 3: Implement transactional creation**

Call `SceneReadiness.check` first. If it fails, return `Failure` immediately. Prepare logical/index state without side effects. Activate `ALWAYS` elements and await their non-blocking stages. If any activation fails, close all prepared handles/entities and return `ACTIVATION_FAILED`. Only after successful preparation attach the event node and schedule the tick task.

- [ ] **Step 4: Implement the shared task and event adapter**

Attach a named child to `request.instance.eventNode()`. Register only `PlayerEntityInteractEvent`, `EntityAttackEvent`, and `PlayerDisconnectEvent`. Maintain Interaction-entity-to-NPC lookup and run the exact transformed-bounds check before accepting a click.

Schedule exactly one task:

```kotlin
task = instance.scheduler().buildTask(::tick).repeat(TaskSchedule.tick(1)).schedule()
```

Every tenth tick evaluates spatial transitions. Every tick updates only active animations, active NPC look/sensors, queued transitions up to budget, and completed action continuations. Catch failures per element/player/chain so the task itself survives.

- [ ] **Step 5: Implement deterministic close**

On the Instance thread: mark closed, increment runtime generation, cancel task, remove child event node, invalidate chains/viewers, close active elements in element-ID order, and clear maps. `close()` returns a `CompletionStage<Void>` that completes after cleanup. A second close returns the same completed/in-flight stage. If requested off-thread, schedule cleanup through the Instance scheduler; never mutate Minestom state from the caller thread.

- [ ] **Step 6: Run all runtime tests**

Run: `./gradlew :scene-minestom:test`  
Expected: PASS with one Minestom bootstrap integration class and no `net.minestom:testing` dependency.

- [ ] **Step 7: Commit**

```bash
git add scene-minestom/src/main scene-minestom/src/test
git commit -S -m "feat(scene): compose the Minestom scene runtime"
```

### Task 8: Lock the artifact boundary and document host usage

**Files:**
- Create: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/SceneMinestomDependencyBoundaryTest.kt`
- Create: `scene-minestom/src/test/java/gg/grounds/scene/minestom/SceneMinestomJavaApiTest.java`
- Create: `scene-minestom/src/test/resources/abi/public-api.txt`
- Create: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/SceneMinestomApiManifestTest.kt`
- Modify: `scene-minestom/build.gradle.kts`
- Modify: `README.md`

**Interfaces:**
- Consumes: final public API from Task 7.
- Produces: stable Java/Kotlin consumption, runtime dependency guard, reviewed API manifest, and operator-facing lifecycle/default documentation.

- [ ] **Step 1: Write failing boundary tests**

The dependency test reads the resolved runtime coordinates supplied through a Gradle system property and rejects fragments for `paper`, `bukkit`, `resourcepacks`, `service-maps`, `plugin-`, `portal`, `jackson`, `testkit`, `junit`, and `kotest`.

The Java test constructs config/identity/request, provides anonymous renderer/effect/policy/action implementations, calls `SceneRuntimeFactory.create`, and closes a successful runtime. The API manifest test records only supported public `gg.grounds.scene.minestom` classes and public members; it also rejects a public signature containing an `.internal.` type.

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew :scene-minestom:test --tests '*BoundaryTest' --tests '*JavaApiTest' --tests '*ApiManifestTest'`  
Expected: failure because Gradle does not provide the runtime graph/JAR properties and the reviewed manifest is absent.

- [ ] **Step 3: Wire exact-artifact boundary inputs**

Make tests depend on `jar`. Replace raw main output with the built JAR on the test classpath, matching `scene-format`. Supply `sceneMinestomRuntimeComponents` and `sceneMinestomJar` system properties from `runtimeClasspath` and `jar.archiveFile`.

- [ ] **Step 4: Add the reviewed manifest and README usage**

Document:

```kotlin
implementation("gg.grounds:scene-minestom:0.2.0")
```

Then show a minimal renderer registry and `SceneRuntimeFactory.create` flow, the exact defaults, Instance-thread rule, asynchronous action rule, and idempotent close. State that host code loads/validates files and catalogs; this module performs no I/O. The expected Release Please version is `0.2.0`; a different generated version blocks delivery until the versioning cause is resolved outside the generated release PR.

- [ ] **Step 5: Run the full repository verification and local publication**

Run: `./gradlew clean check publishToMavenLocal`  
Expected: all three modules pass in one invocation and Maven Local contains `scene-format`, `scene-testkit`, and `scene-minestom` at the same project version.

Inspect:

```bash
jar tf scene-minestom/build/libs/scene-minestom-*.jar
./gradlew :scene-minestom:dependencies --configuration runtimeClasspath
git diff --check
git status --short
```

Expected: no test classes/resources in the production JAR, no forbidden Grounds/runtime dependencies, no whitespace errors, and only intended tracked changes.

- [ ] **Step 6: Request independent whole-branch review and address findings**

Review against the written spec with emphasis on Instance-thread confinement, transactional activation, stale futures, transformed bounds, per-viewer isolation, action order, close cleanup, and dependency leakage. For every finding, reproduce it with a focused failing test before changing production code, rerun the focused test, then rerun `./gradlew clean check`.

- [ ] **Step 7: Commit**

```bash
git add README.md scene-minestom
git commit -S -m "docs(scene): lock Minestom runtime delivery"
```

### Task 9: Merge through protected CI and let Release Please publish

**Files:**
- Update after delivery: `docs/superpowers/specs/2026-09-01-scene-minestom-runtime-design.md`
- Update after delivery: Confluence page `257622018`

**Interfaces:**
- Consumes: reviewed clean implementation branch and existing repository workflows.
- Produces: merged runtime, Release Please-owned release/Maven artifact, immutable verification evidence, and updated Phase 7 status.

- [ ] **Step 1: Push the implementation branch and open the conventional PR**

Push only after `./gradlew clean check`, JAR inspection, dependency inspection, `git diff --check`, and clean status pass. The PR describes host boundaries, spatial defaults, async generation safety, focused tests, and the fact that lobby integration remains separate.

- [ ] **Step 2: Wait for all required CI and review gates**

Do not merge around missing runners or failing jobs. Address code findings with focused RED/GREEN evidence and rerun the full build. Merge only when required CI, publication-shape checks, and review are green.

- [ ] **Step 3: Review and merge the generated Release Please PR**

Verify it changes only the expected manifest/changelog/version lifecycle and includes the `scene-minestom` feature. Merge the generated PR after its gates pass. Do not create a tag or GitHub Release manually.

- [ ] **Step 4: Verify the Release Please release and Maven bytes**

Confirm the Release Please workflow created the expected release (normally `v0.2.0`) and the package workflow published all three artifacts. Download `gg/grounds/scene-minestom/<version>/scene-minestom-<version>.jar` through a fresh authenticated Maven/HTTP consumer, record size and SHA-256, and compare it with the reviewed release build.

- [ ] **Step 5: Record delivery and leave lobby integration open**

Update the spec delivery record and Confluence Phase 7 with PR, merge commit, release, workflow IDs, Maven coordinate, size, and SHA-256. Check off only the generic `scene-minestom` runtime. Leave concrete Grounds renderers, lobby ActionRegistry, real lobby Scene, cluster deployment, and player acceptance open for the second Phase 7 subproject.

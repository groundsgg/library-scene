# library-scene

`library-scene` is the platform-neutral, strict JSON interchange format for Grounds scenes. Version `0.1.0` publishes exactly two artifacts:

```kotlin
dependencies {
    implementation("gg.grounds:scene-format:0.1.0")
    // Tests only; never an application runtime dependency.
    testImplementation("gg.grounds:scene-testkit:0.1.0")
}
```

`scene-format` is the consumer API. `scene-testkit` is only for tests and fixtures; applications must not depend on it at runtime. Both target JVM 25. The format API exposes its domain model plus Adventure `Component`, never Paper/Bukkit, Minestom, service-maps, resource-pack, or Jackson types.

## Loading, validation, and canonical saves

There is literally zero or one `scene.json` per map: a missing `scene.json` means “no scene”; one file is decoded strictly; more than one candidate is a caller configuration error. Keep the decision about where a scene is stored in the host application—this library does not scan, select, or write platform paths.

```kotlin
import gg.grounds.scene.format.SceneDecodeResult
import gg.grounds.scene.format.SceneEncodeResult
import gg.grounds.scene.format.SceneJson
import gg.grounds.scene.format.SceneValidation
import java.nio.file.Files
import java.nio.file.Path

fun loadAndCanonicalize(
    scenePath: Path,
    assetCatalog: gg.grounds.scene.format.AssetCatalog,
    actionCatalog: gg.grounds.scene.format.ActionCatalog,
) {
    require(scenePath.fileName.toString() == "scene.json")
    if (!Files.exists(scenePath)) return // zero scene.json files: no scene

    val decoded = SceneJson.decode(Files.readAllBytes(scenePath))
    val scene = (decoded as? SceneDecodeResult.Success)?.scene
        ?: error((decoded as SceneDecodeResult.Failure).problems.joinToString("\n"))
    val validation = SceneValidation.validateCatalogs(scene, assetCatalog, actionCatalog)
    check(validation.isValid) { validation.problems.joinToString("\n") }
    val canonical = (SceneJson.encode(scene) as SceneEncodeResult.Success).bytes
    Files.write(scenePath, canonical) // canonical JSON is the only save representation
}
```

Catalog pins are exact: `SceneDocument.catalogs.assets` must match the supplied `AssetCatalog.id` and `version`, and `catalogs.actions` must match the supplied `ActionCatalog.id` and `version`. Validate with both catalogs before using a decoded scene; `validateIntrinsic` alone does not establish asset/action compatibility.

## Consumer flows

Paper owns file discovery and platform event translation. It decodes and validates a single file at startup/reload, maps Bukkit events to the neutral `SceneTrigger`, `SceneHand`, and `SceneGameMode` values, then executes the resulting `SceneAction` values in its own adapter.

```kotlin
fun loadPaperScene(
    scenePath: Path,
    assetCatalog: gg.grounds.scene.format.AssetCatalog,
    actionCatalog: gg.grounds.scene.format.ActionCatalog,
) = loadAndCanonicalize(scenePath, assetCatalog, actionCatalog)
```

service-maps owns catalog construction and resource-pack compatibility. It supplies the pinned `AssetCatalog` and `ActionCatalog`, calls `SceneValidation.validateCatalogs`, and hands the validated domain scene to the owning runtime; it does not need a renderer or a server API dependency.

```kotlin
fun validateServiceMapsScene(
    scene: gg.grounds.scene.format.SceneDocument,
    assetCatalog: gg.grounds.scene.format.AssetCatalog,
    actionCatalog: gg.grounds.scene.format.ActionCatalog,
) {
    val result = SceneValidation.validateCatalogs(scene, assetCatalog, actionCatalog)
    check(result.isValid) { result.problems.joinToString("\n") }
}
```

Minestom support is deliberately later. There is no `scene-minestom` artifact in `0.1.0`; a future adapter follows the same neutral-domain flow without changing scene JSON.

## Minestom runtime

Minestom hosts use the runtime adapter at the expected Release Please version `0.2.0`:

```kotlin
dependencies {
    implementation("gg.grounds:scene-minestom:0.2.0")
}
```

The host owns all file and catalog work: load the chosen scene file, decode it, validate it against its asset and action catalogs, and then pass the already validated values to the runtime. `scene-minestom` performs no file, catalog, or network I/O.

```kotlin
val renderers = SceneAssetRendererRegistry { asset, kind ->
    rendererFactories[asset to kind] // SceneAssetRendererFactory?, owned by the host
}

val request = SceneRuntimeRequest(
    scene = validatedScene,
    assets = assetCatalog,
    actions = actionCatalog,
    identity = SceneRuntimeIdentity(validatedScene.id, mapId, mapVersion),
    instance = instance,
    renderers = renderers,
    effects = effectSink,
    playerPolicy = playerPolicy,
    actionRegistry = actionRegistry,
)
SceneRuntimeFactory.create(request).thenAccept { result ->
    when (result) {
        is SceneRuntimeCreationResult.Success -> activeRuntimes += result.runtime
        is SceneRuntimeCreationResult.Failure -> logger.warn("Scene runtime rejected: {}", result.problems)
    }
}
```

`SceneRuntimeConfig()` defaults to a 32-block cell edge, 64-block activation distance, 80-block deactivation distance, 5,000 ms deactivation grace, a 10-tick spatial interval, and a 256-transition per-tick budget. Call runtime APIs that touch Minestom state from the `Instance` thread. Action handlers are asynchronous: return a `CompletionStage` and do not block the Instance tick waiting for host work. `close()` is asynchronous and idempotent; retain and await its returned stage during host shutdown, and repeated calls share the same cleanup operation.

Renderer factories and application handlers are resolved once during readiness. A successful runtime retains immutable capability snapshots and does not query either registry again, so registry implementations may safely be one-shot. Invisible Props and NPCs remain logical and spatial participants and can still emit proximity triggers, but they create no renderer, label, or Interaction resources and never participate in hover or click targeting.

Animation and viewer state are keyed by the resolved element plus optional Composite Part. A Part-targeted action therefore survives reactivation and is reapplied only to that Part's fresh handle. `SceneViewerVisualState` exposes scale and highlight transition snapshots with monotonic start time, duration, start, target, and current values; renderers should apply `current` while retaining the transition metadata needed by viewer-specific implementations. Zero-duration changes are immediate. `TrackNearest` updates the generation-owned runtime transform used consistently by renderer handles, labels, interaction geometry, clicks, queries, and effect targets; reactivation resets it to the authored transform.

Java hosts retain the typed Kotlin SPI and can subclass `JavaSceneAssetRendererRegistry`, `JavaRenderedAssetHandle`, `JavaSceneEffectSink`, and `JavaSceneActionRegistry` to implement String-keyed callbacks. Render contexts, action contexts, runtime identities, and diagnostics expose `get...Value()` String accessors for Scene value-class identifiers. Use `SceneRuntimeJava.identity(sceneId, mapId, mapVersion)` to create an identity where Kotlin's `SceneId` constructor is not directly callable from Java.

## Format constraints

Schema version is exactly `1`. Decoding accepts at most 16 MiB of JSON, nesting depth 64, strings of 65,536 characters, and numeric tokens of 128 characters. Intrinsic limits are 4,096 groups, 100,000 elements, 4,096 composite parts, 128 bindings per NPC, 128 actions per binding, and 64 application-action arguments.

Text components may not contain click events, hover events, or insertion. Only the documented neutral triggers and action variants are representable; platform commands, arbitrary event payloads, resource-pack operations, and platform objects are prohibited. Treat decode failures and validation problems as untrusted input errors, not as partially usable scenes.

## Delivery

CI builds all modules. Release Please is authoritative for release versions and tags; do not create either manually. The generated release version for this adapter must be `0.2.0`; any other generated version blocks delivery until its versioning cause is resolved outside the generated release PR. Tagged releases publish the Maven artifacts through the repository’s GitHub Packages workflow. Local consumers can smoke-test an unreleased build with `./gradlew publishToMavenLocal -PversionOverride=0.2.0-SNAPSHOT` and `mavenLocal()`; publishing is never performed by the normal build.

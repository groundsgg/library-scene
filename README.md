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

## Format constraints

Schema version is exactly `1`. Decoding accepts at most 16 MiB of JSON, nesting depth 64, strings of 65,536 characters, and numeric tokens of 128 characters. Intrinsic limits are 4,096 groups, 100,000 elements, 4,096 composite parts, 128 bindings per NPC, 128 actions per binding, and 64 application-action arguments.

Text components may not contain click events, hover events, or insertion. Only the documented neutral triggers and action variants are representable; platform commands, arbitrary event payloads, resource-pack operations, and platform objects are prohibited. Treat decode failures and validation problems as untrusted input errors, not as partially usable scenes.

## Delivery

CI builds both modules. Release Please creates releases from conventional commits, and tagged releases publish the two Maven artifacts through the repository’s GitHub Packages workflow. Local consumers can smoke-test an unreleased build with `./gradlew publishToMavenLocal -PversionOverride=0.1.0-SNAPSHOT` and `mavenLocal()`; publishing is never performed by the normal build.

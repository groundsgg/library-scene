# NPC world bounds cache implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Avoid recalculating unchanged NPC world bounds during sensor refresh.

**Architecture:** Keep one immutable `WorldBounds` and its complete `SceneRenderTransform` in each `ActiveElement`. Reuse only when the root and local transform compare equal. Compute misses with the existing corner/affine implementation. Cache ownership follows the existing active element and generation; no shared index or global lifecycle is introduced.

**Tech Stack:** Kotlin, existing Scene geometry and Gradle/JUnit.

**Spec:** The user approved profiling the current complete Stage build, selecting one demonstrated cost, testing preservation of behavior, and qualifying separately before Stage. The new profiles identify repeated `ActiveElement.currentBounds` allocation beneath the per-tick sensor refresh. This plan implements that isolated candidate.

## Global constraints

- Preserve existing geometry arithmetic, native SOLID/BOUNCE, table/anvil colliders, hover/clicks, scale and animation behavior.
- Root/local translation, rotation and all scale axes belong to the cache key.
- Preserve missing-NPC/transform errors and validation before a transform is committed.
- Keep equal-value transform copies reusable; bounds remain immutable and element-owned.
- No changes to packets, interpolation, view range, routing, external identities or multiproxy delivery.
- Do not import the previously rejected renderer-math or common-visual candidates.
- Keep raw recordings local. A correct implementation is not a qualified performance result.

## Review focus

- Root-only or local-only changes must replace warm bounds immediately.
- Nonuniform scale and shear must use exactly the existing geometry path. Authored Scene transforms require positive scales; rejection of zero/negative scales remains intact.
- Failed SOLID geometry validation must leave the prior transform/cache usable.
- Replaced elements with the same ID must retain their own authored bounds.
- No in-flight object mutation or cache sharing between active elements.

### Task 1: Element-owned bounds reuse

**Files:**
- Modify: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/runtime/ActiveElement.kt`
- Create: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/internal/runtime/ActiveElementBoundsTest.kt`

**Interfaces:** Consumes the existing `currentTransform`, immutable NPC bounds and `LocalBounds.transformed`; preserves `currentBounds(): WorldBounds`.

- [ ] Write real-element regressions for repeated queries and equal transform copies, asserting shared bounds identity and numerical extrema; root/local moves, rotations and positive nonuniform scales must produce fresh correct bounds.
- [ ] Include independent-element/replacement ownership and failed SOLID transform validation cases.
- [ ] Run `./gradlew :scene-minestom:test --tests '*ActiveElementBoundsTest'`; observe the reuse contract fail before implementation.
- [ ] Add two private cache fields and equality-gated reuse in `currentBounds`. Assign the new value/key only after successful calculation. Leave transform validation and all other paths unchanged.
- [ ] Run the targeted tests, complete `./gradlew test` and formatting checks. Review the diff for unrelated edits, then commit.

### Task 2: Complete source distribution and qualification

**Files:** Experiment tools and receipts stay in the local NPC artifact directory; no generated assets are committed.

**Interfaces:** Consumes the Scene candidate commit; produces an independently reviewed complete source-built Host JAR plus controlled comparison evidence, or an explicit rejection.

- [ ] Freeze source archives for the actual current Host, resourcepacks, Lobby and Player plugins; substitute Scene from source for both reference and candidate builds. Preserve the current addon/catalog/Map contract and verify the production loader.
- [ ] Compare complete JAR entries. Only the intended Scene class may change between matched variants; no production class overlays.
- [ ] Obtain one independent whole-change correctness review; fix Important/Critical findings with RED/GREEN regressions.
- [ ] Reuse the verified fixed451-display fixture, 20/60 animation clients plus a fixed observer, genuine motion and visibility exit, and safe owned cleanup.
- [ ] Complete an unprofiled ABBA comparison. Retain every phase, repeated direction, startup/transition tail and failure. Report allocation and TCP payload separately.
- [ ] Qualify or reject before publication/deployment. Any Stage trial preserves concurrent approved functionality and needs separate native-client acceptance.

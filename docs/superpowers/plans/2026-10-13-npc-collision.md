# Optional NPC collision implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Author and execute per-NPC none/solid/bounce collision with configurable horizontal bounce speed.

**Architecture:** Extend the immutable scene model and strict JSON contract first. Attach movement validation to the existing Minestom runtime-owned event node, using native entity tracking rather than another index. Extend existing editor mutation/command flows, then release and update consumers before publishing new-format scenes.

**Tech Stack:** Kotlin, JVM25, Gradle, Jackson, Minestom, Paper.

**Spec:** `docs/superpowers/specs/2026-10-13-npc-collision-design.md`

## Global constraints

- Modes are `none`, `solid`, `bounce`; missing collision means `none`.
- Bounce speed defaults to **6.0 blocks/second**, finite **0.0 through 20.0**, inclusive.
- Preserve existing public NPC constructor JVM descriptors and first thirteen public component accessors.
- Omit the collision object canonically only for the full default (`none`, 6.0).
- No new custom index, world edits, solid proxy mobs, repeating NPC tasks or all-NPC movement scans.
- One impulse per continuous contact and a **250 ms** minimum between impulses; retain vertical velocity.
- Only active visible owned NPCs and eligible same-instance non-spectator players collide.
- Existing scenes/pins are not silently migrated; Release Please only; no normal occupied-fleet, map-pin or PackSet mutation.

## Review focus

1. Old compiled Kotlin/Java NPC construction still links after adding collision (Task1 ABI tests).
2. Non-default strength survives unrelated editor edits and mode switches (Task3 mutation/history tests).
3. A movement segment crosses a collider although its destination is outside (Task2 sweep test).
4. Spawn-inside and stale delayed impulses do not trap or move the wrong player (Task2 lifecycle tests).
5. Readers are upgraded before a collision-bearing scene reaches derivation or lobby (Task4 dependency/package evidence).

---

### Task 1: Additive scene collision contract

**Files:**
- Create: `scene-format/src/main/kotlin/gg/grounds/scene/format/NpcCollision.kt`.
- Modify: `scene-format/src/main/kotlin/gg/grounds/scene/format/Elements.kt`.
- Modify: `scene-format/src/main/kotlin/gg/grounds/scene/format/internal/{SceneWire,WireReader,WireMapping,CanonicalJson}.kt`.
- Test: `scene-format/src/test/kotlin/gg/grounds/scene/format/NpcCollisionTest.kt` and existing ABI manifests/tests as needed.

**Interfaces:**
- Produces `enum class NpcCollisionMode { NONE, SOLID, BOUNCE }`.
- Produces immutable `data class NpcCollision(val mode: NpcCollisionMode = NONE, val bounceStrength: Double = 6.0)` with constructor validation.
- Produces `Npc.collision: NpcCollision` and a new public constructor overload taking collision after bindings. Existing overloads delegate to `NpcCollision()` and retain their exact signatures/default masks.
- Wire object keys are exactly `mode` and `bounceStrength`; unknown keys/modes and invalid strength reject through existing decoding error conventions.

- [ ] Write real JSON decode/encode tests first: missing/default object omits collision canonically; literal bounce with speed `12.5` round-trips; none with speed `12.5` persists; `0.0` and `20.0` accept; `-0.1`, `20.1`, NaN/infinity programmatic construction reject; unknown modes/keys reject. Add ABI assertions retaining every prior NPC constructor and public component1..13 signature, including an old-signature Java construction call.
- [ ] Run `./gradlew :scene-format:test --tests '*NpcCollisionTest*' --console=plain`; observe expected missing contract/unknown-field failure before product edits.
- [ ] Implement the model and existing wire flow minimally. Keep bindings defensively copied; do not expose internal wire classes. Update additive ABI manifests only after checking no old descriptor was removed.
- [ ] Run focused tests, then `./gradlew test spotlessCheck --console=plain`; report any unrelated failures explicitly.
- [ ] Commit only Task1 files with `feat: add optional NPC collision settings` and report RED/GREEN evidence.

### Task 2: Runtime collision and bounce

**Files:**
- Create: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/collision/NpcCollisionController.kt`.
- Modify: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/{MinestomSceneEvents,DefaultSceneRuntime}.kt`.
- Modify: `scene-minestom/src/main/kotlin/gg/grounds/scene/minestom/internal/runtime/{ActiveElement,ElementActivator}.kt` as needed to align the owned interaction entity's physical bounding box.
- Test: `scene-minestom/src/test/kotlin/gg/grounds/scene/minestom/internal/collision/NpcCollisionControllerTest.kt` and runtime lifecycle integration tests.

**Interfaces:**
- Consumes Task1 `Npc.collision`, existing active-element lookup and interaction-UUID ownership map, `ScenePlayerPolicy`, runtime clock/scheduler and current bounds.
- Produces an internal movement controller with `move(event: PlayerMoveEvent)`, `disconnect(playerId: UUID)` and `close()` entry points called from the runtime-owned event/lifecycle flow. Keep additional helper APIs internal.

- [ ] Write native-instance movement tests first: none accepts; solid blocks and keeps requested yaw/pitch; swept fast crossing blocks; moving away and spawn-inside escape work; hidden/unowned/spectator/ineligible/cancelled/rotation-only cases are ignored. Bounce at strengths `0.0`, `6.0`, `12.0` gives no impulse or the exact corresponding horizontal speed while retaining vertical velocity. Repeated contact gives one impulse, genuine reapproach after 250 ms allows another. Disconnect/close/collider-generation change invalidate delayed impulses.
- [ ] Run the focused runtime tests and capture the expected missing-feature failures.
- [ ] Implement using existing native tracker and `BoundingBox.intersectBoxSwept` APIs, calculating the candidate radius from current active collider extents on activation/transform changes rather than scanning all NPCs per movement. `CollisionUtils.checkEntityCollisions` has incompatible return descriptors between the library's July baseline and the deployed October fork; do not call it directly or add reflection into the movement hot path. Native sweep gives a boolean but no public fraction/normal getter, so use a small internal AABB slab-entry helper to rank native-confirmed hits and choose outward horizontal direction. Filter owned current colliders, skip initial overlap for escape, choose earliest swept hit, correct to the old position with the requested rotation, and gate one-shot velocity by contact and lifecycle/generation checks.
- [ ] Run focused tests, full `./gradlew test spotlessCheck --console=plain`, and inspect that no new public scene-minestom API or repeating task was introduced.
- [ ] Commit Task2 with `feat: enforce optional NPC collision and bounce`; report exact native version used and test evidence.

### Task 3: In-game authoring

**Repository:** `plugin-scene-editor`, isolated feature branch from current main after the independent Stable catalog fix is integrated.

**Files:**
- Modify: `common/build.gradle.kts` for the released scene-format version.
- Modify: `common/src/main/kotlin/gg/grounds/scene/editor/mutation/SceneMutations.kt` and `common/src/main/kotlin/gg/grounds/scene/editor/tool/TransformMath.kt` if it reconstructs NPCs.
- Modify: `paper/src/main/kotlin/gg/grounds/scene/editor/paper/command/{SceneCommand,SceneTabCompleter}.kt` and parser/dispatcher only where existing routing requires it.
- Test: existing mutation, session/history and command tests; add focused collision cases.

**Interfaces:**
- Consumes released Task1 `NpcCollision`, `NpcCollisionMode` and new NPC constructor overload.
- Produces `SceneMutations.setNpcCollisionMode(actor: UUID, target: LocalId, mode: NpcCollisionMode): SceneMutation` and `setNpcBounceStrength(actor: UUID, target: LocalId, strength: Double): SceneMutation`.
- Commands exactly `/scene npc <id> collision set none|solid|bounce` and `/scene npc <id> collision strength <0..20>`.

- [ ] Write tests: mode edit retains strength `12.5`; strength edit retains solid mode; label/body/transform/action/duplicate operations preserve collision; undo/redo restores settings; selection and lease checks remain enforced; malformed/non-finite/out-of-range strength rejects without mutation. Exercise the existing save/JSON boundary with new settings.
- [ ] Run focused tests and capture expected failures before implementing.
- [ ] Implement through the existing mutation/history and command flow. Complete mode values and expose usage/help. Do not add Paper collision physics or new storage.
- [ ] Run `./gradlew test spotlessCheck --console=plain`, verify actual shadow-JAR dependency/resource contents, commit and request review.

### Task 4: Consumer integration and isolated acceptance

**Repositories:** existing Scene derivation worker and minestom-lobby consumers, then GitOps only for an isolated test host.

**Interfaces:** New settings must decode in the derivation worker and host before being authored/published; no manual versions/tags/releases.

- [ ] Review and merge green library PR, merge generated Release Please PR when checks pass, and verify published artifact coordinates and the additive model ABI.
- [ ] Resolve actual consumer dependency declarations with `rg 'scene-(format|minestom|testkit)'` in the existing worker and lobby worktrees; update to the generated released version only. Test a literal collision-bearing scene through production validation/preparation; test an unchanged old scene too. Verify packaged dependency classes in actual release artifacts.
- [ ] Integrate/release the editor and consumers through reviewed green PRs and generated Release Please PRs; record exact commits, artifact hashes and unresolved deployment gates. Do not claim active deployments from source merges alone.
- [ ] Use an isolated temporary map/host and existing zero-player/ownership guards for live checks. Give the user commands for mode/strength changes and verify walking, sprint crossing, jumping past/over, low/high strength, reapproach, two players, disconnect and NPC removal. No normal pins/global PackSets/occupied fleets change.
- [ ] Record actual client results and clean only owned temporary infrastructure after users leave. Keep aggregate Masterplan gates open until all required evidence exists.

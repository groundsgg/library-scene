# NPC Bounce Distance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fractional block-based authoring and a non-sticking outward bounce.
**Architecture:** Add a distinct distance representation without reinterpreting
legacy speed; derive a single native impulse. Correct the demonstrated native
movement/contact fault separately and prove it at the real packet boundary.
**Tech Stack:** Kotlin, Minestom, Paper editor, strict scene JSON, Gradle, GitOps.
**Spec:** `docs/superpowers/specs/2026-10-13-npc-bounce-distance-design.md`.

## Global Constraints

- Distance finite 0..20, fractions allowed; legacy strength finite 0..20 unchanged.
- Reject both wire fields together; omit distance when absent.
- Preserve public old NpcCollision constructor/copy/default masks and Npc ABI.
- Outward horizontal impulse, vertical velocity unchanged, ground drag 0.546.
- No repeated force task/spatial index/world mutation; retain query budgets.
- Release Please; readers before uploads; isolated/zero-player guarded rollout.

## Review Focus

- Rounded raw decimals beyond20 or negative-underflow: Task1 strict raw checks.
- Legacy compiled callers and new copy overload resolution: Task1 ABI fixture.
- Teleport confirmation/stale inbound positions: Task2 real listener packet test.
- Contact suppressed before delivery or frozen after cooldown: Task2 lifecycle test.
- Author reconstructions silently drop distance: Task3 mutation/history roundtrip.

### Task 1: Additive format and ABI

**Files:** `scene-format/src/main/kotlin/gg/grounds/scene/format/NpcCollision.kt`,
`internal/{SceneWire,WireReader,WireMapping,CanonicalJson}.kt`;
`scene-format/src/test/kotlin/gg/grounds/scene/format/NpcCollisionTest.kt`.
**Interfaces:** Produces nullable `NpcCollision.bounceDistance: Double?` and
three-argument `(mode, bounceStrength, bounceDistance)` construction/copy, while
retaining old two-argument/default interfaces and legacy semantics.

- [ ] Add tests: JSON distance0.5/1.5/2.25 roundtrip, zero/20, reject bothkeys,
  negative-underflow/roundedabove20/nonfinite, old bytes/ABI and copy calls.
- [ ] Run `./gradlew :scene-format:test --tests '*NpcCollisionTest'`; new cases RED.
- [ ] Implement optional distance with exact raw validation and canonical shape.
- [ ] Run full `./gradlew test spotlessCheck`; GREEN, then commit scoped format.

### Task 2: Native runtime contact and distance

Runtime presentation choice is pending the user's asynchronous answer. The
impulse steps below remain provisional; do not implement them before the choice
and the packet-flow findings have been reconciled into this task/spec.

**Files:** `scene-minestom/.../internal/collision/NpcCollisionController.kt`,
its existing native tests and one focused actual movement-listener regression.
**Interfaces:** Consumes Task1 distance; legacy speed path unchanged; distance
impulse speed = distance * TPS * 0.454, horizontal along current outward contact.

- [ ] Isolate contact/packet fault with actual deployed Minestom fork; record proof.
- [ ] Write failing release/reapproach and continuous-forward regression naming
  the fault; never assert only stored velocity as proof of client packet handling.
- [ ] Add distance0.5=>4.54 and1.5=>13.62 speed tests at20TPS, Y retention,
  direction, zero, collider lifecycle, cancellation and existing cooldown.
- [ ] Implement minimal demonstrated contact fix and distance conversion.
- [ ] Run focused October-fork cases and full test/Spotless; GREEN; commit.

### Task 3: Editor distance authoring

**Files:** existing editor command parser/dispatcher, common mutations and tests.
**Interfaces:** Consumes released Task1 API. Distance command enables nullable
distance and resets inert legacy strength to6; explicit strength command clears
distance. Mode changes retain both fields. No Paper collision runtime.

- [ ] RED fractional distance command, selection/lease/history, all reconstruction
  preservation and explicit legacy strength reset; implement minimal changes.
- [ ] Full editor test/Spotless and artifact inspection GREEN; commit/review.

### Task 4: Release, readers and isolated acceptance

**Files:** existing worker/lobby dependency pins, immutable builder editor pin,
targeted Maps config and owned scene-test-lobby fixture only.
**Interfaces:** New strict readers required before distance-bearing publication.

- [ ] Final independent branch review; exact-head green source CI; generated
  Release Please merges; verify actual published JAR/image classes and hashes.
- [ ] Focused worker/lobby literal-distance reader RED(old)/GREEN(new), full suites.
- [ ] Upgrade only required readers/empty builder; preserve world backups and
  zero-player guards. Never restart or replace occupied test host.
- [ ] Author0.5/1.5, upload normal pipeline (also fix reserved-manifest roundtrip),
  verify synthetic pin/hash; isolated client tests held-forward/release/reapproach,
  side approach/wall limitation/two players; cleanup only after empty guard.

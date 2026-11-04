# Primitive NPC Hover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove measured per-axis object churn from prepared hover rays while preserving exact results.

**Architecture:** Keep preparation and the public nullable-distance API. Evaluate the existing X/Y/Z slabs with primitive loop locals; no caches or shared scratch state.

**Tech Stack:** Kotlin2.2.20, Java25, Gradle9.5.1, JUnit5, existing offline protocol777 load harness.

**Spec:** `docs/superpowers/specs/2026-11-04-npc-primitive-hover-design.md`

## Global Constraints

- Keep X/Y/Z order, validation, normalization and arithmetic order unchanged.
- Preserve exact hit/miss and returned distance bits; no gameplay, packet or geometry changes.
- No unqualified matrix/SVD code or closed experimental candidates.
- Freeze the current accepted Stage JAR and Map9; compare with actual20/60clients.
- Report all adverse/cold windows; no CPU/TPS/FPS claim from allocation-only evidence.

## Review Focus

- Parallel and negative-zero directions on each slab: inclusive face membership.
- Inside/face/edge origins and exact maximum reach: preserve zero-distance and boundary hits.
- Nonuniform root/local scales plus rotation: preserve shear and world-distance normalization.
- Invalid maximum distance and degenerate/nonfinite directions: preserve rejection.
- Shared prepared object across interleaved rays/viewers: preserve immutable operation.

### Task 1: Scalar slabs and correctness/allocation evidence

**Files:** Modify `scene-interaction/src/main/kotlin/gg/grounds/scene/interaction/SceneHover.kt`; extend `scene-interaction/src/test/kotlin/gg/grounds/scene/interaction/SceneHoverTest.kt`. Keep comparison/allocation probes under `artifacts/npc-primitive-hover-2026-11-04/`.

**Interfaces:** Existing `prepare(bounds,root,local): PreparedBounds` and `distance(eye,direction,maximumDistance): Double?` stay unchanged.

- [ ] Run baseline repository tests. Expected: green before product edits.
- [ ] Add literal axis-parallel, signed-zero, inside/face/edge and exact-reach tests, plus invalid-input/interleaved-query coverage. Expected: characterize existing results.
- [ ] Write a bounded actual-method allocation probe with a256-byte/ray budget under deterministic tier1 compilation. Run against frozen Stage. Expected: RED because temporary axis objects exceed the budget.
- [ ] Replace the object iteration with primitive locals and unchanged arithmetic.
- [ ] Run tests, reference comparisons and the same allocation probe. Expected: exact results and GREEN allocation budget; preserve RED/GREEN logs.
- [ ] Run the repository test/check commands and formatting, then commit the implementation and tests.

### Task 2: Integration and connected qualification

**Files:** Exact-JAR assembly, diff proofs, frozen load configuration and results under `artifacts/npc-primitive-hover-2026-11-04/`; measured results in `docs/superpowers/plans/2026-11-04-npc-primitive-hover-results.md`.

**Interfaces:** The existing Map9/451-display fixture consumes baseline and Stage-derived candidate JARs with fixed SHA-256 values.

- [ ] Prove only intended interaction classes differ and preserve ZIP entry order/duplicates, Scene/catalogue/native SOLID/host classes and addon acceptance.
- [ ] Run fresh connected20/60baseline/candidate comparisons including motion, horizontal boundary and two full reentries. Repeat in reversed order when results warrant acceptance. Expected: complete counts, unchanged map, clean shutdown and a repeatable allocation benefit without material unresolved regressions.
- [ ] Review the whole branch with a fresh critical reviewer, fix any correctness findings with reproductions, and finalize the measured PR description.
- [ ] If qualified, prepare/review the exact Stage image/config, deploy to Stage, and verify healthy lobbies. Otherwise leave the candidate unactivated and document the decision.

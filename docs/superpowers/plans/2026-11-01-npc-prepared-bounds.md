# NPC Prepared Hover Bounds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove repeated inverse matrix allocations for unchanged NPC bounds across viewers.
**Architecture:** Immutable prepared query; element-owned cache keyed by current root/local transform; legacy public API remains available.
**Tech Stack:** Kotlin, Scene interaction/Minestom, JUnit5, connected20client comparison.
**Spec:** ../specs/2026-11-01-npc-prepared-bounds-design.md

## Global Constraints

- Preserve slab/ray normalization and maximum world reach.
- No changes to SOLID/BOUNCE/world collision geometry or authored assets.
- Cache belongs to one ActiveElement lifetime; root/local/bounds changes cannot reuse stale inverse.
- Freeze N20 and N32 artifacts separately; compare on same prelitMap9.

## Review Focus

- Equal-value transforms may reuse preparation; changed rotation/translation/scale/local cannot.
- Nonuniform root scaling plus local rotation introduces shear and must preserve exact hit distances.
- Authored-transform fallback still works when initial runtime transform is absent.
- Reloaded element/bounds/world cannot share old prepared state.
- Invalid rays/reach and singular transforms preserve rejection.

### Task1: Immutable preparation and element-owned reuse

Files: SceneHover.kt, ActiveElement.kt, BoundsRaycaster.kt, SceneHoverTest.kt, ViewerStateTest.kt.
Interfaces: SceneBoundsRaycaster.prepare(bounds:LocalBounds,root:Transform,local:Transform?):PreparedBounds; PreparedBounds.distance(eye:Vec3,direction:Vec3,maximumDistance:Double):Double?; ActiveElement.preparedInteractionBounds():PreparedBounds.

- [x] Add RED tests for prepared ray results/shear and reused preparation with invalidation under runtime transform and element replacement.
- [x] Run focused tests and observe missing behavior.
- [x] Extract immutable preparation while keeping slab calculations/legacy entry point; add element-owned cache and consume it only for hover.
- [x] Run entire Scene and licensedHost suites, format, and fresh correctness review.
- [ ] Freeze artifact and run matched20client N20/N32 performance pair; compare CPU/allocation and typed profiles.
- [ ] Commit/update backlog and activate only the independently measured result on Stage.

Validation:272Scene tests,170licensed Host tests,0failures/skips; format checks pass. Fresh independent reviewer found no blockers and suggested explicit rotation-only invalidation coverage; added root/local90degree warm-cache regression, full suite272green. Frozen N20baselinec481fd7 versus bounds candidatec3e662a9; matched20clientpair running. No collision/worldbounds code changed.

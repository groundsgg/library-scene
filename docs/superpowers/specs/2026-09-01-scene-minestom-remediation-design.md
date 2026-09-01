# scene-minestom Runtime Remediation Design

**Status:** USER REVIEW

**Date:** 2026-09-01

**Base:** `2ee9c284a1228968d5cad429a9bd14fa85e87fa8`

## Purpose

Close the remaining load-bearing findings from the final scoped review without replacing Minestom's own spatial infrastructure or adding a custom tree. The remediation preserves the published design goals: bounded normal-case work, exact authored geometry, transactional activation, Instance-thread ownership, persistent logical state, Java consumption, and Release Please-owned delivery.

## Non-goals

- No custom AABB tree, quadtree, or general-purpose spatial library.
- No new Scene format limits for otherwise valid finite bounds or proximity radii.
- No lobby, resource-pack, service-map, Paper, Bukkit, Jackson, or runtime testkit dependency.
- No deployment while the cluster is unavailable.
- No manual tag or release; Release Please remains authoritative.

## Minestom chunk-based sensing

### Hover

Visible active NPCs continue to own one Minestom `Interaction` entity positioned from their current generation transform. Hover broad-phase uses `Instance.getEntityTracker().nearbyEntitiesByChunkRange` around the player's eye and filters only Interaction UUIDs owned by this runtime.

Normal NPC bounds use a fixed search range derived from the five-block interaction reach plus one 16-block chunk of horizontal bound extent. The exact inverse-affine ray test remains authoritative, so chunk lookup can only add candidates, never accept a hit.

An active visible NPC whose current transformed horizontal bounds extend more than 16 blocks from its Interaction position is placed in a small `oversizedHover` set. Those exceptional NPCs are exact-tested in addition to the Minestom chunk candidates. A transform update moves the Interaction through Minestom and updates membership in this set. This preserves correctness for large or off-center authored bounds without indexing one NPC into every overlapped chunk.

Invisible NPCs have no Interaction entity, never enter hover candidates, and remain eligible only for authored proximity behavior.

### Proximity

Proximity is evaluated from each active NPC through Minestom's entity tracker: query `PLAYERS` around the NPC's current position with that NPC's authored `exitRadius`, then apply exact distance, policy, Instance, enter-radius, and existing-membership checks.

Existing membership entries are also reconciled against connected live players so crossing outside `exitRadius`, policy exclusion, and disconnect emit or clear the correct state. Spatial deactivation retains proximity membership, as already specified, and reactivation cannot emit a duplicate enter.

This performs no global `players × activeNPCs` scan and creates no custom cell rectangle. A legitimately huge authored proximity radius necessarily asks Minestom to examine the corresponding chunks; the runtime adds no second expansion or global maximum-radius query.

## Activation desire and dispatch

Every automatic element owns a monotonically increasing desire epoch. A queued `ACTIVATE` transition carries the epoch that created it.

- Spatial reevaluation cancels queued activation transitions whose epoch is no longer desired.
- Player disconnect or policy exclusion immediately reconciles affected desires rather than waiting for the configured spatial interval.
- Runtime dispatch checks the epoch and current eligibility before starting a renderer.
- Renderer completion checks the same epoch before claim and publication.
- Losing eligibility while a renderer is pending increments the epoch, aborts the activation owner, removes platform resources, and makes every late handle self-close.

The existing per-tick transition budget remains unchanged. A budget-delayed transition cannot outlive the desire that produced it.

## Renderer completion ownership

A renderer handle is inserted into synchronized activation ownership before any owner-scheduler submission. Callback registration, callback bodies, and scheduler submission are guarded.

If submission succeeds, all Minestom resource mutation remains on the Instance thread. If the owner scheduler itself rejects submission, the activation is terminally failed, its public stage completes exceptionally exactly once, the newly delivered handle is best-effort closed immediately, and the safe diagnostic retains the scheduler cause. No continuation may be left unresolved and no delivered handle may become unreachable.

Runtime creation and activation-continuation submission follow the same rule: scheduler rejection resolves the public operation deterministically instead of only logging and hanging.

## Root and part state precedence

Logical animation and viewer state remain keyed by `(elementId, optionalPartId)`.

- A part-target action changes only that part and establishes a part override.
- A root-target animation action updates the root and removes all part animation overrides.
- A root-target scale action updates the root scale and removes all part scale overrides while retaining unrelated highlight overrides.
- A root-target highlight action updates the root highlight and removes all part highlight overrides while retaining unrelated scale overrides.
- Live handles are always updated from the same effective state calculation used during reactivation.

Therefore a root action means "apply to the entire element now and on future activations"; a later part action may override only that part.

## Transition lifecycle

`ViewerStateStore` maintains a dedicated set of keys with an active scale or highlight transition. The per-tick pump visits only this set.

When a transition reaches its duration, the final target value is stored, the transition object is cleared, the key is removed when no other transition remains, and one final renderer update is sent. Zero-duration actions never enter the active-transition set. Completed transitions cannot cause permanent sorting or callbacks.

Player removal, policy exclusion, runtime close, and inverse actions remove the corresponding active-transition ownership while preserving the already specified logical-state rules.

## Diagnostics and readiness

Application callback scheduler-submission failures emit a safe diagnostic containing the original cause. Because the Instance scheduler is unavailable on that path, the fallback reporter must be thread-safe and must not read Instance-owned maps. Normal diagnostics remain marshalled to the Instance thread.

`SceneRuntimeIdentity.sceneId` is compared with `SceneDocument.id` before any renderer, effect, or action capability lookup. A mismatch returns the existing structured `INVALID_CONFIG` problem immediately and consumes no one-shot registry method.

## Verification

Focused regressions cover:

1. normal hover candidates through Minestom chunk tracking;
2. large and off-center bounds through the overflow set with exact ray acceptance;
3. proximity enter/leave and retained membership through Minestom player queries;
4. a budget-delayed activation invalidated before dispatch;
5. scheduler rejection after a handle has completed, proving handle cleanup and non-hanging creation;
6. root actions clearing property-specific part overrides with identical live/reactivated state;
7. transition completion retiring the active key and stopping callbacks;
8. scheduler-rejection diagnostics retaining the cause without an off-thread runtime-state read;
9. Scene ID mismatch performing zero capability lookups.

The final gate is `./gradlew clean check publishToMavenLocal`, followed by production-JAR, runtime dependency, Java API, ABI manifest, whitespace, signature, and clean-worktree inspection. Tests remain risk-focused; no benchmark suite or additional Minestom test framework is introduced.

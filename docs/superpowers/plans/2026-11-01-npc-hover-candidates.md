# NPC Hover Candidates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove measured nearby-decoration collection/sort cost from hover sensing.
**Architecture:** Resolve owned interaction anchors inside the tracker callback; sort once by final element identity.
**Tech Stack:** Kotlin, Minestom, JUnit5, connected20-client protocol777 harness.
**Spec:** ../specs/2026-11-01-npc-hover-candidates-design.md

## Global Constraints

- Preserve native SOLID and Scene corner-motion fix e70b000.
- Keep oversized hover fallback and deterministic distance/LocalId selection.
- No changes to proximity queries, authored geometry or view distances.

## Review Focus

- Nearby unowned Interaction entities must not become hover candidates.
- Hundreds of decorative displays must not enter the returned candidate collection.
- Owned anchors outside the tracker window remain excluded, except existing oversized fallback.
- Unregister/deactivate must stop returning stale owners immediately.
- Equal-distance hit selection must stay independent of tracker/UUID order.

### Task1: Filter during tracker collection

Files: MinestomSensorQueries.kt, NpcSensorEngine.kt, ViewerStateTest.kt.
Interface: `<T : Any> hoverInteractions(eye: Point, resolve: (Entity) -> T?): List<T>`.

- [x] Add real tracker test with300near displays, unowned Interaction, nearby owned anchors and distant owned anchor; expect only nearby owners and immediate removal on owner-map update.
- [x] Run focused test RED for missing resolver behavior.
- [x] Implement resolver collection and use current owner map in NpcSensorEngine; retain its final LocalId sort/oversized path.
- [x] Run entire Scene suite/format and independent correctness review.
- [x] Build isolated Host overlay on prelit22cab857; run matched20-client mixed comparison; retain only evidence-backed improvement.
- [ ] Commit, record evidence/backlog status and deploy only after the cold-start user test is stable.

## Ledger

BASE e70b000. Ruling: User authorized profile-backed backlog work; execute inline after prioritizing persisted lighting. N32 inverse caching remains a separate decision after this measurement, rather than stacking unmeasured changes.

Validation:268Scene tests pass,0failures/skips;170licensed Host tests pass; both formatting checks pass. Fresh independent correctness review: no blockers. Frozen prelit baseline22cab857 versus candidatec481fd7, same Map9/prelit source,20actual clients and7/7/6mixed zones. Full settled measurement+separate2msJFR pair running; no Stage activation before those results.

Acceptance complete: matched20clients/7-7-6layout,55ssettled window, separate60s/2msJFR. Mean1.802→1.107ms(-38.6%), CPU0.09755→0.07975cores(-18.2%), allocation30.255→28.090MB/s(-7.2%);0disconnects,0DataLoss. One controlled pair; packet-rate variation is not a policy change. SourcePR24 merged as19342662aa9af2ac4482097052c7e1e83243fab6. Raw evidence artifacts/npc-load-profile-2026-11-01/hover-summary.json. Stage activation waits for the separate bounds follow-up.

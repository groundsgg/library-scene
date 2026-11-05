# NPC collision candidate filtering implementation plan

Goal: avoid collecting unrelated entities for NPC collision sweeps, preserving existing geometry, contact, native SOLID corner and Bounce behavior.

Architecture: retain Minestom nearbyEntities and its conservative query radius. During its callback, admit only entities with a Scene interaction ownership entry. Existing candidate() revalidates identity, instance, active generation, visibility and collider registration before every sweep. No new cache or entity-type assumption.

Alternatives: a collider spatial index introduces lifecycle state; scanning the collider registry changes broad-phase geometry. Both are deferred until this small change is measured.

- [x] Add a real dense-entity regression that preserves SOLID contact but avoids consulting unrelated entities during candidate validation. Verify RED.
- [x] Filter unowned entities before list insertion. Verify GREEN against native movement, corner, stale/replaced collider, tall bounds and Bounce regressions.
- [ ] Run repository tests and format checks, package exact current-host comparison JAR, compare unprofiled full-physics clients, review diff.

Constraints: no public API or collision resolution changes; local instance ownership only; no change to Stage until qualification. Preserve all prior merged hover and native collision fixes. Do not touch held matrix work.

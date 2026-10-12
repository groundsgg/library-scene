# Optional NPC collision and configurable bounce

## Intent and scope

Authors can make an individual NPC impassable, optionally bouncing a player
away with configurable strength. The user approved three modes and requested
per-NPC strength in the in-game editor and scene JSON. Existing scenes must
keep their current behavior. This is an additive public-format and runtime
extension, not a general physics engine.

## Authoring contract

- Modes: `none`, `solid`, `bounce`. Missing collision means `none`.
- `solid` blocks traversal without applying a bounce impulse.
- `bounce` also blocks traversal and applies an outward horizontal impulse.
- Strength is a speed in blocks/second, not a multiplier of incoming speed.
  Initial default: **6.0**. Allowed values: finite **0.0 through 20.0**, inclusive.
  Zero still blocks traversal but adds no impulse. These numerical defaults
  are design choices for the first implementation, not measured client results.
- Strength has no effect in `none` or `solid`, but is retained when switching
  modes so an author does not lose the setting.
- Wire shape: optional NPC `collision` object with `mode` and `bounceStrength`.
  Example: `"collision":{"mode":"bounce","bounceStrength":6.0}`.
  Missing object or omitted fields use the defaults above. Unknown modes,
  non-finite strengths, out-of-range strengths and unknown object keys fail
  decoding/validation rather than silently changing behavior.
- Canonical JSON omits the entire object only for the full default
  (`none`, 6.0); non-default settings round-trip. Ordinary old scenes therefore
  retain their old wire shape. Old strict readers cannot read a new explicit
  collision object: upgrade consumers before publishing such scenes.
- Public API: immutable `NpcCollision(mode: NpcCollisionMode, bounceStrength:
  Double)` and `Npc.collision`. Preserve the existing public `Npc` constructor
  JVM descriptors and first thirteen public component accessors. Add a new
  overload accepting collision; existing constructors delegate to defaults.

## In-game editor

Extend the existing NPC command/mutation flow, including its selected-element
requirement, leases, undo/redo, validation and atomic saves:

```text
/scene npc <id> collision set none|solid|bounce
/scene npc <id> collision strength <0..20>
```

Selecting a mode does not reset strength. Changing strength does not change
mode. Every existing mutation that reconstructs an NPC must preserve collision.
Command completion/help exposes the modes. The Paper editor remains an
authoring/preview tool; this slice does not add a second Paper physics runtime.

## Minestom movement behavior

Reuse the transformed `interactionBounds` as the physical volume. This avoids
a second hidden box authoring system. The volume is the world-space AABB of
those bounds, consistent with existing click targeting, not mesh-accurate
collision or private viewer scaling. Labels and markers do not collide.

Handle movement through the runtime-owned instance event node. Only active,
visible, owned NPCs and eligible players in the same instance participate.
Ignore spectators, already-cancelled events and rotation-only updates.

Use Minestom's existing EntityTracker/chunk lookup for nearby candidates,
then native swept entity collision against the player's bounding box. The
NPC interaction entity's server bounding box follows its current transformed
bounds and participates in entity collision for non-`none` modes. Do not add
another custom spatial index, invisible shulkers, barrier blocks, world edits,
per-NPC repeating tasks or all-NPC scans on every movement packet.

The movement sweep must catch fast crossing even when the destination is
outside the NPC. Correct blocked movement back to the last accepted position,
preserving the requested view rotation. Collision is server-authoritative;
the existing display/interaction entities do not provide client-predicted
solid blocks, so small position corrections are possible.

For `bounce`, use an outward horizontal direction at the contact face and the
configured speed; retain vertical velocity rather than launch players upward.
Native `EntityCollisionResult.direction()` in the deployed Minestom fork is
**not a contact normal** (the inspected implementation stores contact-position
coordinates there). Determine outward direction from the collided bounds;
do not normalize that field as if it were a surface normal.

Apply at most one bounce per continuous contact, with a 250 ms minimum interval
between impulses. A later genuine approach may bounce again. Repeated blocked
packets must not stack impulses. If applying velocity must follow position
correction, use a guarded one-shot scheduled task, not a repeating timer.
Before a delayed impulse, recheck runtime lifetime, player instance/eligibility
and the collider generation. Disconnect and runtime close clear contact state.

A player who spawns or is teleported inside a collider must be allowed to exit
without becoming trapped or being repeatedly bounced. Teleports themselves
are not vetoed by the movement listener. NPC transforms/deactivation must not
leave stale barriers or delayed impulses behind.

### Native query performance boundary

Native nearby-entity lookup enumerates empty chunks too; an unrestricted
authored collider extent would turn every movement into unbounded work.
For non-`none` modes, the transformed collider's farthest-corner distance from
its bottom-center entity origin must be finite and at most **64 blocks**.
Readiness rejects unsupported geometry, and runtime transforms reject it before
changing the current valid rendered/physical state. Default `none` scenes keep
their previous geometry behavior and zero physical interaction box.

The movement query radius is finite and at most **112 blocks**, corresponding
to at most an eight-chunk search radius / 289 chunk visits in the supported
native tracker. Reject/correct a sweep exceeding that work budget without a
new bounce; do not clamp the radius and silently omit colliders. This does not
intercept server teleports. These are fixed implementation safety limits, not
additional authoring knobs or a general anti-cheat subsystem.

If a centered vertical hit has no horizontal approach/offset, it still blocks;
`bounce` uses a deterministic negative-X horizontal fallback. Cooldown is
measured between successful delivered impulses, not the time they were queued.

## Alternatives considered

1. **Native tracker plus movement validation (chosen):** reuses existing
   Minestom infrastructure and lifecycle. Server corrections require real
   client acceptance but do not modify the map or visible entity type.
2. **Hidden solid mobs/barriers:** possible client collision, but extra entity
   behavior, shape restrictions or world mutation complicate authoring and
   cleanup. Rejected for this feature.
3. **Periodic all-player/all-NPC collision loops:** easy to sketch, but adds
   unnecessary recurring work and misses packet-level sweeps. Rejected.

## Verification and rollout

Keep tests focused: default/legacy JSON shape and public constructor
compatibility; valid/invalid strength round-trip; editor mutation preservation,
selection, mode and strength undo/redo; swept blocking and escape; bounce
direction/strength and repeat-contact suppression; visibility, spectator and
disconnect/close guards. Prefer actual native collision tests where practical.

Release through Release Please only. Update scene-format consumers, derivation
worker, editor and lobby/runtime in dependency order before a collision-bearing
map is published. Do not silently repin existing asset catalogs. Test on an
isolated lobby, leaving normal occupied fleets, normal map pins and global
PackSets unchanged. Human checks cover walking/sprinting, jumping past/over,
low/high strength, reapproach, two players, disconnect and NPC removal. Ready
pods and passing tests are not proof of real-client bounce behavior.

## Explicit non-goals

No new permission boundary, PvP collision policy, moving rigid bodies, arbitrary
rebound curves, separate collision meshes, standing-platform guarantees,
strength UI sliders, private per-viewer physics, global republishing or normal
fleet restart. Core NetworkPolicy and Stage Config settings remain unchanged.

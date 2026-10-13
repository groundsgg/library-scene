# NPC bounce distance and continuous-contact correction

The user confirmed horizontal target distance in blocks, including arbitrary
finite decimal values such as 0.5, and an outward direction derived from the
NPC/contact rather than player view. No upward launch or passage through walls.
The current live bounce is delivered, but holding forward produces jitter and
rapid apparent sticking: this is not accepted behavior.

The user explicitly chose a soft impulse with approximate distance after real
packet-flow diagnosis. Do not replace it with a teleport-to-distance.

## Authoring and compatibility

Add optional `collision.bounceDistance`: finite 0..20 blocks, including fractions.
Keep `bounceStrength` as the legacy 0..20 blocks/second representation; never
reinterpret its value as distance. Reject a wire object containing both fields.
Absent distance preserves old decoding, defaults and canonical bytes. Explicit
distance overrides the legacy runtime speed, is retained through mode switches,
and is serialized without a redundant strength. Distance is chosen explicitly;
omission retains the legacy default speed6, not an implicit distance preset.
Zero remains solid without an impulse.

Keep old NpcCollision constructor/default-mask, copy/default-mask and component
descriptors, and the existing Npc descriptors. Add a nullable distance property
and an additive construction/copy path. Existing authored strength remains
unchanged until the author deliberately chooses distance.

The new command is `/scene npc <id> collision distance <0..20>`. Keep `strength`
as an explicit legacy command which clears distance. Leases, selection, history,
undo/redo, all NPC reconstructions, validation and atomic save remain unchanged.

## Runtime and performance

Distance is a horizontal target on free flat ordinary ground, not guaranteed
displacement against opposing player input, water, ice or obstacles. Convert
distance to one native impulse using ordinary-ground drag 0.6*0.91 = 0.546:
speed = distance * ticksPerSecond * (1 - 0.546). This makes the unforced geometric
series approach the requested distance; native physics/client input can shorten
or alter it. No repeated force loop, per-NPC timer, teleport-to-distance, block
mutation or custom spatial index. Preserve vertical velocity and bound queries
as in the original collision design. The contact face supplies outward direction;
centered vertical hits retain the deterministic horizontal fallback.

Resolve sticking from demonstrated packet/contact state failure, not by blindly
raising the impulse or weakening traversal validation. Keep the real movement
listener/teleport confirmation/packet ordering in the regression boundary, and
retain one impulse per continuous contact plus the 250ms delivery cooldown.
Only successful impulse delivery consumes that contact. A new contact reached
during the cooldown remains eligible after it expires; a queued impulse that
fails its lifecycle/position checks must not permanently latch the contact.
Keep at most one task pending for a contact and clear pending state on all exits.

## Verification and delivery

Focused strict JSON/fraction/boundary/legacy ABI tests; real listener contact and
release/reapproach tests; direction and derived impulse tests; editor command and
undo/redo persistence tests. Full existing suites once per deliverable, final
independent review. Release Please only, readers before new distance-bearing
uploads, isolated client acceptance only. No normal Fleet/map/globalPackSet or
NetworkPolicy/Stage Config change. Never restart the occupied test host.

# SOLID native corner movement

Minecraft Java26.3 resolves Y then one horizontal axis then the other. The requested
start/end chord can cross a Scene NPC even though the client's axis-separated route is
clear. The previous guard rejects that chord and resets X/Z, causing rubberbanding.

A reproduced builder corner uses a 1×1.9375×0.6875 authored box, 0.6×1.8 player,
start(-0.81,0,0.60), displacement(+0.10,0,+0.11), authored centerZ=-0.03125.
Straight sweep intersects; native Z then X motion clears the four-millimeter skin.

If the earliest direct hit is SOLID, accept a clear YXZ or YZX route. The same order must
clear all relevant blockers, including BOUNCE. Both orders are considered because the
requested post-collision displacement cannot recover the original desired axis order.
Otherwise retain the existing correction and contact/impulse lifecycle. Direct BOUNCE
behavior, ownership, eligibility, query budgets and initial-overlap escape remain guarded.

Validate mirrored/swapped corners, genuine crossings, multiple blockers that require
contradictory orders, mixed BOUNCE blockers and existing vertical/contact regressions.
This proves a collision mismatch, not the cause of every instance-specific report.
Actual Java-client validation on Stage remains necessary.

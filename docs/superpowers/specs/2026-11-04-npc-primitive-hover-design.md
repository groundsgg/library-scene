# Primitive NPC hover slabs

The accepted Stage build's `PreparedBounds.distance` allocates a temporary list,
three pairs, three triples and boxed axis values for every viewer ray. Two owned
20/60-client profiles attribute about10/17% of approximate allocation-sample
weight to this path. These are memory-pressure observations, not CPU percentages.

Replace only the three-axis slab iteration with primitive local values. Keep
X/Y/Z order, normalization, inverse transformation, bounds arithmetic, validation,
signed-zero comparisons, min/max operations and nullable hit distance unchanged.
No changes to public signatures, geometry, SOLID, visibility or player identity.

The considered alternatives are caching world bounds or redesigning ray batches.
Those have wider invalidation/lifecycle requirements. The selected scalar loop
directly removes the observed temporary objects with no new shared mutable state.
The user approved this focused approach and its20/60-client qualification.

Acceptance: exact reference hit/miss and distance bits across edge/transform cases;
allocation reduction on a bounded component probe; full repository checks; exact
Stage-derived JAR differing only in the intended interaction classes. Fresh
connected baseline/candidate comparisons must report CPU, allocation, ticks,
traffic, reentry and first-activation costs. Stage rollout and merge require a
qualified result; no claim that this fixes the separately observed43–67ms first
join/activation spikes or improves rendered client FPS.

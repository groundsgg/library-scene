# NPC hover candidate collection

Connected20-client profiles show hoverInteractions collects and UUID-sorts every nearby entity (including471 model displays) before NpcSensorEngine resolves the few owned interaction anchors, then sorts again by LocalId. Preserve hover selection, oversized bounds fallback, lifecycle and deterministic LocalId tie ordering while resolving ownership during tracker collection and removing the discarded UUID sort.

Use an internal generic query `hoverInteractions(eye: Point, resolve: (Entity) -> T?): List<T>` that only collects resolved candidates. NpcSensorEngine resolves its current interaction map inside that callback; its existing unique-element map, oversized fallback and final LocalId sort remain authoritative. Proximity queries are unchanged. Measure20 connected clients on the same prelit cold-fix Host before/after; retain only if CPU/allocation evidence is positive. No collision, geometry, view distance or packet cohort changes.

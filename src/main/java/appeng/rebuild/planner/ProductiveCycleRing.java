package appeng.rebuild.planner;

import java.util.List;
import java.util.Objects;

/** Ordered, proposed simple ring. The solver owns all bounded structural validation. */
public record ProductiveCycleRing(List<ProductiveCycleRingMember> members) {
    public ProductiveCycleRing {
        Objects.requireNonNull(members, "members");
        if (members.size() > PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS) {
            throw new IllegalArgumentException("Productive cycle ring has too many members");
        }
        members = List.copyOf(members);
    }
}

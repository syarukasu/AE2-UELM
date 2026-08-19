package appeng.rebuild.planner;

/** Finite bounds for immutable planning inputs and dependency metadata. */
public final class PlannerLimits {
    /** Maximum registry-scoped keys copied into one exact storage snapshot. */
    public static final int MAX_STORAGE_SNAPSHOT_KEYS = 1_048_576;

    private PlannerLimits() {
    }
}

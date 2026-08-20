package appeng.rebuild.planner;

/** Stable, planner-local identifier for one authoritative causal step. */
public record PlannedBatchId(long value) implements Comparable<PlannedBatchId> {
    public PlannedBatchId {
        if (value < 0) {
            throw new IllegalArgumentException("Planned batch id must be non-negative");
        }
    }

    @Override
    public int compareTo(PlannedBatchId other) {
        return Long.compare(value, other.value);
    }
}

package appeng.rebuild.planner;

/** One immutable, non-authoritative step in an exact craft's causal plan. */
public sealed interface PlannedCausalStep permits PlannedPatternBatch, PlannedCycleBatch {
    PlannedBatchId id();

    PlannedBatchCause cause();
}

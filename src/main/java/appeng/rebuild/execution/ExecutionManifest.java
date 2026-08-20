package appeng.rebuild.execution;

import java.util.List;

import appeng.rebuild.planner.PlannedBatchCause;
import appeng.rebuild.planner.PlannedBatchId;

/**
 * Immutable, recipe-reload-independent execution data sealed with an exact crafting plan.
 *
 * <p>
 * A manifest is an execution description, never permission to mutate a grid. It deliberately retains the compiled
 * pattern snapshot used during validation so a later executor cannot silently consult a newer recipe definition.
 */
public sealed interface ExecutionManifest permits NormalExecutionManifest, CycleExecutionManifest {
    /** Stable identity of the causal step represented by this manifest. */
    PlannedBatchId batchId();

    /** Causal demand that produced the represented step. */
    PlannedBatchCause cause();

    /** All exact compiled patterns sealed into this one causal step. */
    List<SealedPatternExecution> patternExecutions();
}

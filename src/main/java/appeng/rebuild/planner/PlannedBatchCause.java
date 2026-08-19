package appeng.rebuild.planner;

import java.util.Objects;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.quantity.AEAmount;

/** Immutable demand edge that explains why a causal step was planned. */
public sealed interface PlannedBatchCause permits PlannedBatchCause.Root, PlannedBatchCause.Input {
    /** Demand originating at the exact-craft request. */
    record Root(KeyId output, AEAmount demandedAmount) implements PlannedBatchCause {
        public Root {
            Objects.requireNonNull(output, "output");
            requirePositive(demandedAmount, "demandedAmount");
        }
    }

    /**
     * Demand from an input selection of a later consumer step.
     *
     * <p>
     * A {@code cycleMemberIndex} of {@value #NORMAL_CONSUMER_MEMBER} denotes a normal {@link PlannedPatternBatch}; a
     * non-negative value denotes that member of a {@link PlannedCycleBatch}. {@code demandedAmount} records this
     * producer's contextual demand; it is not aggregated or summed by draft validation.
     */
    record Input(PlannedBatchId consumerBatchId, int cycleMemberIndex, int inputIndex, int candidateIndex,
            KeyId key, AEAmount demandedAmount) implements PlannedBatchCause {

        public static final int NORMAL_CONSUMER_MEMBER = -1;

        public Input {
            Objects.requireNonNull(consumerBatchId, "consumerBatchId");
            if (cycleMemberIndex < NORMAL_CONSUMER_MEMBER
                    || cycleMemberIndex >= PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS
                    || inputIndex < 0 || inputIndex >= PatternLimits.MAX_INPUT_GROUPS
                    || candidateIndex < 0 || candidateIndex >= PatternLimits.MAX_CANDIDATES_PER_INPUT) {
                throw new IllegalArgumentException("Planned input cause index is out of bounds");
            }
            Objects.requireNonNull(key, "key");
            requirePositive(demandedAmount, "demandedAmount");
        }
    }

    private static void requirePositive(AEAmount amount, String name) {
        PlannerAmounts.requireWithinLimit(amount, name);
        if (amount.equals(AEAmount.ZERO)) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

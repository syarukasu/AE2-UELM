package appeng.rebuild.planner;

import java.util.Objects;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.quantity.AEAmount;

/** Exact per-turn output-to-input transfer within a productive cycle. */
public record PlannedCycleLink(int producerMemberIndex, int outputIndex, int consumerMemberIndex, int inputIndex,
        int candidateIndex, KeyId key, AEAmount amountPerTurn) {
    public PlannedCycleLink {
        if (producerMemberIndex < 0 || producerMemberIndex >= PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS
                || consumerMemberIndex < 0 || consumerMemberIndex >= PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS
                || outputIndex < 0 || outputIndex >= PatternLimits.MAX_OUTPUTS
                || inputIndex < 0 || inputIndex >= PatternLimits.MAX_INPUT_GROUPS
                || candidateIndex < 0 || candidateIndex >= PatternLimits.MAX_CANDIDATES_PER_INPUT) {
            throw new IllegalArgumentException("Planned cycle link index is out of bounds");
        }
        Objects.requireNonNull(key, "key");
        PlannedCycleMember.requirePositive(amountPerTurn, "amountPerTurn");
    }
}

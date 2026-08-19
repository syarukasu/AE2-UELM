package appeng.rebuild.execution;

import java.util.Objects;

import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/**
 * Immutable causal position of a sealed physical instruction.
 *
 * <p>
 * This is deliberately exact: a cycle command is bound to the causal step, ring member, and the remaining
 * repetition/member work that existed when it was issued. It prevents an acknowledgement for one turn from being
 * replayed against another turn with the same pattern and batch id.
 * </p>
 */
public record ExactWorkCommandLocation(int causalStepIndex, int cycleMemberIndex,
        AEAmount remainingRepetitions, AEAmount remainingMemberExecutions) {
    public ExactWorkCommandLocation {
        if (causalStepIndex < 0 || cycleMemberIndex < -1
                || cycleMemberIndex >= PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS) {
            throw new IllegalArgumentException("Invalid exact work command location index");
        }
        remainingRepetitions = bounded(remainingRepetitions, "remainingRepetitions");
        remainingMemberExecutions = bounded(remainingMemberExecutions, "remainingMemberExecutions");
        if (remainingMemberExecutions.equals(AEAmount.ZERO)) {
            throw new IllegalArgumentException("Command locations require positive member work");
        }
        if (cycleMemberIndex < 0 && !remainingRepetitions.equals(AEAmount.ZERO)) {
            throw new IllegalArgumentException("Normal command locations cannot retain cycle repetitions");
        }
        if (cycleMemberIndex >= 0 && remainingRepetitions.equals(AEAmount.ZERO)) {
            throw new IllegalArgumentException("Cycle command locations require positive exact progress");
        }
    }

    static ExactWorkCommandLocation normal(int causalStepIndex, AEAmount remainingExecutions) {
        return new ExactWorkCommandLocation(causalStepIndex, -1, AEAmount.ZERO, remainingExecutions);
    }

    static ExactWorkCommandLocation cycle(int causalStepIndex, int memberIndex, AEAmount remainingRepetitions,
            AEAmount remainingMemberExecutions) {
        return new ExactWorkCommandLocation(causalStepIndex, memberIndex, remainingRepetitions,
                remainingMemberExecutions);
    }

    boolean isCycle() {
        return cycleMemberIndex >= 0;
    }

    private static AEAmount bounded(AEAmount value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
            throw new IllegalArgumentException(name + " exceeds the exact planning bound");
        }
        return value;
    }
}

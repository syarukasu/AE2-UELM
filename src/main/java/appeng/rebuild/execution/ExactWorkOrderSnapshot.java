package appeng.rebuild.execution;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/**
 * Immutable bounded observation of one work order and the exact resources in its custody.
 *
 * <p>
 * While {@link #inFlightCommand()} is present, the physical input custody transferred to the executor is derived
 * exactly from {@code inFlightCommand().get().custodyInputs()}; it is intentionally not duplicated in this snapshot. A
 * fail-closed discrepancy or completed evidence retains actual returned goods separately when they cannot be merged
 * into bounded custody, and is the corresponding recovery authority.
 */
public record ExactWorkOrderSnapshot(ExactWorkOrderState state, ExactPlanId planId, CpuPlanHandle handle,
        ReservationId reservationId, UUID leaseIdentity, WorkOrderId workOrderId, Map<KeyId, AEAmount> custody,
        int causalStepIndex, AEAmount remainingExecutions, List<AEAmount> selectionRemaining,
        long nextGeneration, boolean generationExhausted,
        Optional<ExactWorkCommand> outstandingCommand, Optional<ExactWorkCommand> inFlightCommand,
        Optional<ExactWorkDiscrepancy> discrepancy, Optional<ExactCompletedCommandEvidence> completedEvidence,
        boolean completedEvidenceProgressApplied) {
    public ExactWorkOrderSnapshot {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(leaseIdentity, "leaseIdentity");
        Objects.requireNonNull(workOrderId, "workOrderId");
        custody = ExactReservationReceipt.copyDebitsOrEmpty(custody, "custody");
        if (causalStepIndex < 0) {
            throw new IllegalArgumentException("causalStepIndex must be non-negative");
        }
        remainingExecutions = Objects.requireNonNull(remainingExecutions, "remainingExecutions");
        if (remainingExecutions.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
            throw new IllegalArgumentException("remainingExecutions exceeds the bounded exact quantity limit");
        }
        Objects.requireNonNull(selectionRemaining, "selectionRemaining");
        if (selectionRemaining.size() > PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS) {
            throw new IllegalArgumentException("selectionRemaining exceeds the bounded selection limit");
        }
        selectionRemaining = List.copyOf(selectionRemaining);
        for (AEAmount amount : selectionRemaining) {
            if (Objects.requireNonNull(amount, "selectionRemaining entry").toBigInteger()
                    .bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
                throw new IllegalArgumentException("selectionRemaining entry exceeds the bounded exact quantity limit");
            }
        }
        outstandingCommand = Objects.requireNonNull(outstandingCommand, "outstandingCommand");
        inFlightCommand = Objects.requireNonNull(inFlightCommand, "inFlightCommand");
        discrepancy = Objects.requireNonNull(discrepancy, "discrepancy");
        completedEvidence = Objects.requireNonNull(completedEvidence, "completedEvidence");
        if (nextGeneration < 0) {
            throw new IllegalArgumentException("nextGeneration must be non-negative");
        }
        if (generationExhausted && nextGeneration != Long.MAX_VALUE) {
            throw new IllegalArgumentException("Exhausted command generation must be frozen at Long.MAX_VALUE");
        }
        int authorityCount = (outstandingCommand.isPresent() ? 1 : 0) + (inFlightCommand.isPresent() ? 1 : 0)
                + (discrepancy.isPresent() ? 1 : 0) + (completedEvidence.isPresent() ? 1 : 0);
        if (authorityCount > 1) {
            throw new IllegalArgumentException("A work order can retain at most one command evidence authority");
        }
        outstandingCommand.ifPresent(command -> requireCommandIdentity(command, planId, handle, reservationId,
                leaseIdentity, workOrderId));
        inFlightCommand.ifPresent(command -> requireCommandIdentity(command, planId, handle, reservationId,
                leaseIdentity, workOrderId));
        discrepancy.ifPresent(value -> requireCommandIdentity(value.command(), planId, handle, reservationId,
                leaseIdentity, workOrderId));
        completedEvidence.ifPresent(value -> requireCommandIdentity(value.command(), planId, handle, reservationId,
                leaseIdentity, workOrderId));
        validateLastGeneration(outstandingCommand, nextGeneration, generationExhausted);
        validateLastGeneration(inFlightCommand, nextGeneration, generationExhausted);
        discrepancy.ifPresent(value -> validateLastGeneration(Optional.of(value.command()), nextGeneration,
                generationExhausted));
        completedEvidence.ifPresent(value -> validateLastGeneration(Optional.of(value.command()), nextGeneration,
                generationExhausted));
        if (completedEvidence.isEmpty() && completedEvidenceProgressApplied) {
            throw new IllegalArgumentException("Completion-progress flag requires completed evidence");
        }
        switch (state) {
            case COMMAND_OUTSTANDING -> require(outstandingCommand.isPresent() && inFlightCommand.isEmpty()
                    && discrepancy.isEmpty() && completedEvidence.isEmpty() && !completedEvidenceProgressApplied,
                    "Outstanding state requires exactly its outstanding command");
            case IN_FLIGHT ->
                require(outstandingCommand.isEmpty() && inFlightCommand.isPresent() && discrepancy.isEmpty()
                        && completedEvidence.isEmpty() && !completedEvidenceProgressApplied,
                        "In-flight state requires exactly its in-flight command");
            case READY, SETTLEMENT_PENDING, RELEASE_PENDING, COMPLETED -> require(
                    outstandingCommand.isEmpty() && inFlightCommand.isEmpty() && discrepancy.isEmpty()
                            && completedEvidence.isEmpty() && !completedEvidenceProgressApplied,
                    "Non-active work state cannot retain command or discrepancy evidence");
            case CANCEL_PENDING -> require(outstandingCommand.isEmpty() && discrepancy.isEmpty()
                    && completedEvidence.isEmpty() && !completedEvidenceProgressApplied,
                    "Cancellation cannot retain an outstanding command or discrepancy evidence");
            case FAIL_CLOSED -> {
                // A closed order may retain either command custody linkage or immutable discrepancy evidence.
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireCommandIdentity(ExactWorkCommand command, ExactPlanId planId, CpuPlanHandle handle,
            ReservationId reservationId, UUID leaseIdentity, WorkOrderId workOrderId) {
        require(command.id().planId().equals(planId) && command.id().leaseIdentity().equals(leaseIdentity)
                && command.id().workOrderId().equals(workOrderId) && command.handle().equals(handle)
                && command.reservationId().equals(reservationId),
                "Command identity does not belong to this work order");
    }

    private static void validateLastGeneration(Optional<ExactWorkCommand> command, long nextGeneration,
            boolean generationExhausted) {
        if (command.isEmpty()) {
            return;
        }
        if (nextGeneration == 0) {
            throw new IllegalArgumentException("No command can exist before the first generation is issued");
        }
        long expected = generationExhausted ? Long.MAX_VALUE : nextGeneration - 1L;
        if (command.get().id().generation() != expected) {
            throw new IllegalArgumentException("Retained command must be the most recently issued generation");
        }
    }
}

package appeng.rebuild.execution;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.CompiledCandidateSpec;
import appeng.rebuild.pattern.CompiledInputSpec;
import appeng.rebuild.pattern.CompiledOutputSpec;
import appeng.rebuild.planner.PlannedInputSelection;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/**
 * Inert, validated crash-recovery authority. It cannot issue, release, acknowledge, or rebind any live service.
 * Activation into operational seams is deliberately a later recovery gate.
 */
public record ExactRecoveryCheckpoint(ExactCraftingPlan plan, ExactCpuLedgerSnapshot ledger,
        ExactTransferBrokerSnapshot broker, Optional<ExactWorkOrderSnapshot> workOrder,
        Optional<FailedHandoffRecovery> failedHandoff, boolean recoveryRequired) {

    /** Source-compatible construction for checkpoints without an irreversible materialization failure. */
    public ExactRecoveryCheckpoint(ExactCraftingPlan plan, ExactCpuLedgerSnapshot ledger,
            ExactTransferBrokerSnapshot broker, Optional<ExactWorkOrderSnapshot> workOrder, boolean recoveryRequired) {
        this(plan, ledger, broker, workOrder, Optional.empty(), recoveryRequired);
    }

    public ExactRecoveryCheckpoint {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(broker, "broker");
        workOrder = Objects.requireNonNull(workOrder, "workOrder");
        failedHandoff = Objects.requireNonNull(failedHandoff, "failedHandoff");
        if (ledger.state() == ExactCpuLedgerState.IDLE || broker.state() == ExactTransferBrokerState.IDLE
                || broker.state() == ExactTransferBrokerState.PREFLIGHT
                || broker.state() == ExactTransferBrokerState.EXTRACTING)
            throw new IllegalArgumentException("Resource-free or transient state is not a recoverable checkpoint");
        CheckpointClass checkpointClass = classify(ledger, broker, workOrder);
        if (checkpointClass != CheckpointClass.HANDED_OFF && failedHandoff.isPresent())
            throw new IllegalArgumentException("Only handoff recovery may retain a failed materialization lease");
        switch (checkpointClass) {
            case PREPARED -> validatePrepared(plan, ledger, broker, workOrder);
            case RESERVED -> validateReserved(plan, ledger, broker, workOrder);
            case RELEASE_PENDING -> validateReleasePending(plan, ledger, broker, workOrder);
            case HANDED_OFF -> validateHandedOff(plan, ledger, broker, workOrder, failedHandoff);
        }
        if (requiresRecovery(ledger, broker, workOrder) && !recoveryRequired)
            throw new IllegalArgumentException("Durable command evidence or a fail-closed authority requires recovery");
    }

    /**
     * Captures immutable observations only. Cross-object locking would deadlock against normal transitions, therefore
     * callers must invoke this from the server-thread, non-reentrant checkpoint boundary. The available gate and every
     * sampled identity are checked before and after one observation pass; no retry or operational mutation occurs.
     */
    static ExactRecoveryCheckpoint capture(ExactCpuLedger ledger, ExactTransferBroker broker,
            Optional<ExactWorkOrder> workOrder) {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(broker, "broker");
        workOrder = Objects.requireNonNull(workOrder, "workOrder");
        if (!broker.recoveryCaptureAllowed() || workOrder.isPresent() && !workOrder.get().recoveryCaptureAllowed())
            throw new IllegalStateException("Recovery capture requires the idle server-thread checkpoint boundary");
        ExactCpuLedgerSnapshot ledgerBefore = ledger.snapshot();
        ExactTransferBrokerSnapshot brokerBefore = broker.snapshot();
        ExactWorkOrderSnapshot workBefore = workOrder.map(ExactWorkOrder::snapshot).orElse(null);
        Optional<FailedHandoffRecovery> failedLeaseBefore = broker.handoffFailureLease()
                .map(FailedHandoffRecovery::capture);
        ExactCraftingPlan plan = ledger.recoveryPlan();
        if (plan == null)
            throw new IllegalArgumentException("Recovery ledger has no sealed plan authority");
        ExactCpuLedgerSnapshot ledgerAfter = ledger.snapshot();
        ExactTransferBrokerSnapshot brokerAfter = broker.snapshot();
        ExactWorkOrderSnapshot workAfter = workOrder.map(ExactWorkOrder::snapshot).orElse(null);
        Optional<FailedHandoffRecovery> failedLeaseAfter = broker.handoffFailureLease()
                .map(FailedHandoffRecovery::capture);
        if (!broker.recoveryCaptureAllowed() || workOrder.isPresent() && !workOrder.get().recoveryCaptureAllowed()
                || !ledgerBefore.equals(ledgerAfter) || !brokerBefore.equals(brokerAfter)
                || !Objects.equals(workBefore, workAfter) || !failedLeaseBefore.equals(failedLeaseAfter)
                || plan != ledger.recoveryPlan())
            throw new IllegalStateException("Recovery capture lost its server-thread/non-reentrant stability gate");
        Optional<ExactWorkOrderSnapshot> capturedWork = Optional.ofNullable(workAfter);
        return new ExactRecoveryCheckpoint(plan, ledgerAfter, brokerAfter, capturedWork, failedLeaseAfter,
                requiresRecovery(ledgerAfter, brokerAfter, capturedWork));
    }

    /** Compatibility overload for the handoff-only callers from the previous execution phase. */
    static ExactRecoveryCheckpoint capture(ExactCpuLedger ledger, ExactTransferBroker broker,
            ExactWorkOrder workOrder) {
        return capture(ledger, broker, Optional.of(Objects.requireNonNull(workOrder, "workOrder")));
    }

    private static CheckpointClass classify(ExactCpuLedgerSnapshot ledger, ExactTransferBrokerSnapshot broker,
            Optional<ExactWorkOrderSnapshot> workOrder) {
        return switch (ledger.state()) {
            case PREPARED -> CheckpointClass.PREPARED;
            case RESERVED -> CheckpointClass.RESERVED;
            case RELEASE_PENDING -> CheckpointClass.RELEASE_PENDING;
            case HANDED_OFF -> CheckpointClass.HANDED_OFF;
            case IDLE -> throw new IllegalArgumentException("Idle ledger has no recovery authority");
            case FAIL_CLOSED -> classifyClosedLedger(ledger, broker, workOrder);
        };
    }

    private static CheckpointClass classifyClosedLedger(ExactCpuLedgerSnapshot ledger,
            ExactTransferBrokerSnapshot broker, Optional<ExactWorkOrderSnapshot> workOrder) {
        if (ledger.handle().isEmpty())
            throw new IllegalArgumentException("Resource-free fail-closed ledger is not recoverable");
        if (workOrder.isPresent() || ledger.leaseIdentity().isPresent() || broker.workOrderId().isPresent()
                || broker.leaseIdentity().isPresent())
            return CheckpointClass.HANDED_OFF;
        if (ledger.releaseObligation().isPresent())
            return CheckpointClass.RELEASE_PENDING;
        if (ledger.reservationId().isPresent() || !ledger.reservedDebits().isEmpty())
            return CheckpointClass.RESERVED;
        return CheckpointClass.PREPARED;
    }

    private static void validatePrepared(ExactCraftingPlan plan, ExactCpuLedgerSnapshot ledger,
            ExactTransferBrokerSnapshot broker, Optional<ExactWorkOrderSnapshot> workOrder) {
        require(ledger.handle().isPresent() && ledger.reservationId().isEmpty() && ledger.reservedDebits().isEmpty()
                && ledger.releaseObligation().isEmpty() && ledger.leaseIdentity().isEmpty(),
                "Prepared recovery ledger retains unowned resource authority");
        require(workOrder.isEmpty() && broker.workOrderId().isEmpty() && broker.leaseIdentity().isEmpty(),
                "Pre-handoff recovery cannot retain a work order");
        require(broker.state() == ExactTransferBrokerState.ROLLBACK_PENDING
                || broker.state() == ExactTransferBrokerState.FAIL_CLOSED,
                "Prepared recovery requires rollback-pending or fail-closed broker custody");
        requireBrokerPlanHandle(plan, ledger, broker);
        boolean firstExtractDiscrepancy = broker.transferDiscrepancy().filter(
                value -> value.operation() == BrokerTransferDiscrepancy.Operation.EXTRACT
                        && value.knownEscrow().isEmpty())
                .isPresent();
        require((!broker.escrowed().isEmpty() || firstExtractDiscrepancy)
                && componentwiseAtMost(broker.escrowed(), plan.initialStorageDebits()),
                "Prepared recovery escrow must be nonempty unless first extraction retained explicit empty evidence");
    }

    private static void validateReserved(ExactCraftingPlan plan, ExactCpuLedgerSnapshot ledger,
            ExactTransferBrokerSnapshot broker, Optional<ExactWorkOrderSnapshot> workOrder) {
        require(ledger.handle().isPresent() && ledger.reservationId().isPresent()
                && ledger.reservedDebits().equals(plan.initialStorageDebits()) && ledger.releaseObligation().isEmpty()
                && ledger.leaseIdentity().isEmpty(), "Reserved recovery ledger must own the exact sealed debit");
        require(workOrder.isEmpty() && broker.workOrderId().isEmpty() && broker.leaseIdentity().isEmpty(),
                "Pre-handoff recovery cannot retain a work order");
        require(broker.state() == ExactTransferBrokerState.RESERVED
                || broker.state() == ExactTransferBrokerState.FAIL_CLOSED,
                "Reserved recovery requires reserved or fail-closed broker custody");
        requireBrokerReservation(plan, ledger, broker);
        if (broker.state() == ExactTransferBrokerState.RESERVED)
            require(broker.escrowed().equals(plan.initialStorageDebits()),
                    "Reserved broker escrow must equal the sealed plan debit");
        else
            require(componentwiseAtMost(broker.escrowed(), plan.initialStorageDebits()),
                    "Fail-closed reserved escrow must be a componentwise plan-debit subset");
    }

    private static void validateReleasePending(ExactCraftingPlan plan, ExactCpuLedgerSnapshot ledger,
            ExactTransferBrokerSnapshot broker, Optional<ExactWorkOrderSnapshot> workOrder) {
        require(ledger.handle().isPresent() && ledger.reservationId().isPresent()
                && ledger.releaseObligation().isPresent()
                && ledger.leaseIdentity().isEmpty(), "Release-pending recovery ledger lacks its release authority");
        ReleaseObligation obligation = ledger.releaseObligation().orElseThrow();
        require(obligation.handle().equals(ledger.handle().orElseThrow())
                && obligation.reservationId().equals(ledger.reservationId().orElseThrow())
                && obligation.reservedDebits().equals(plan.initialStorageDebits())
                && ledger.reservedDebits().equals(plan.initialStorageDebits()),
                "Release obligation must exactly retain the sealed reservation debit");
        require(workOrder.isEmpty() && broker.workOrderId().isEmpty() && broker.leaseIdentity().isEmpty(),
                "Pre-handoff release cannot retain a work order");
        require(broker.state() == ExactTransferBrokerState.RELEASE_PENDING
                || broker.state() == ExactTransferBrokerState.FAIL_CLOSED,
                "Release-pending recovery requires release-pending or fail-closed broker custody");
        requireBrokerReservation(plan, ledger, broker);
        require(componentwiseAtMost(broker.escrowed(), plan.initialStorageDebits()),
                "Release escrow must be a componentwise plan-debit subset");
    }

    private static void validateHandedOff(ExactCraftingPlan plan, ExactCpuLedgerSnapshot ledger,
            ExactTransferBrokerSnapshot broker, Optional<ExactWorkOrderSnapshot> workOrder,
            Optional<FailedHandoffRecovery> failedHandoff) {
        require(ledger.handle().isPresent() && ledger.reservationId().isPresent() && ledger.leaseIdentity().isPresent()
                && ledger.reservedDebits().equals(plan.initialStorageDebits()) && ledger.releaseObligation().isEmpty(),
                "Handed-off recovery ledger must retain the exact lease debit");
        if (workOrder.isEmpty()) {
            require(broker.state() == ExactTransferBrokerState.FAIL_CLOSED && failedHandoff.isPresent(),
                    "Missing work order requires the explicit irreversible fail-closed handoff lease");
            FailedHandoffRecovery failed = failedHandoff.orElseThrow();
            require(failed.planId().equals(plan.planId())
                    && failed.handle().equals(ledger.handle().orElseThrow())
                    && failed.reservationId().equals(ledger.reservationId().orElseThrow())
                    && failed.leaseIdentity().equals(ledger.leaseIdentity().orElseThrow())
                    && failed.reservedDebits().equals(plan.initialStorageDebits())
                    && broker.escrowed().equals(plan.initialStorageDebits()),
                    "Fail-closed handoff lease or escrow differs from its sealed authority");
            requireBrokerReservation(plan, ledger, broker);
            require(broker.workOrderId().isPresent() && broker.leaseIdentity().isPresent()
                    && broker.leaseIdentity().orElseThrow().equals(failed.leaseIdentity()),
                    "Fail-closed handoff broker lacks its staged work-order lease identity");
            return;
        }
        require(failedHandoff.isEmpty(), "Materialized work order cannot retain a second failed-handoff authority");
        require(broker.state() == ExactTransferBrokerState.LEASED
                || broker.state() == ExactTransferBrokerState.FAIL_CLOSED,
                "Handed-off recovery requires leased or fail-closed-handoff broker state");
        require(broker.escrowed().isEmpty(), "Handed-off broker cannot retain duplicate escrow custody");
        ExactWorkOrderSnapshot order = workOrder.orElseThrow();
        require(order.planId().equals(plan.planId()) && order.handle().equals(ledger.handle().orElseThrow())
                && order.reservationId().equals(ledger.reservationId().orElseThrow())
                && order.leaseIdentity().equals(ledger.leaseIdentity().orElseThrow()),
                "Recovery work-order identity differs from its ledger lease");
        validateWorkProgress(plan, order);
        requireBrokerReservation(plan, ledger, broker);
        require(broker.workOrderId().isPresent() && broker.leaseIdentity().isPresent()
                && broker.workOrderId().orElseThrow().equals(order.workOrderId())
                && broker.leaseIdentity().orElseThrow().equals(order.leaseIdentity()),
                "Recovery broker lease identity differs from its work order");
        require(!(broker.state() == ExactTransferBrokerState.LEASED
                && order.state() == ExactWorkOrderState.COMPLETED),
                "A completed work order cannot retain a live handed-off broker/ledger authority");
    }

    private static void requireBrokerPlanHandle(ExactCraftingPlan plan, ExactCpuLedgerSnapshot ledger,
            ExactTransferBrokerSnapshot broker) {
        require(broker.planId().isPresent() && broker.handle().isPresent() && broker.reservationId().isPresent()
                && broker.planId().orElseThrow().equals(plan.planId())
                && broker.handle().orElseThrow().equals(ledger.handle().orElseThrow()),
                "Recovery broker plan/handle/reservation identity differs from its ledger");
    }

    private static void requireBrokerReservation(ExactCraftingPlan plan, ExactCpuLedgerSnapshot ledger,
            ExactTransferBrokerSnapshot broker) {
        requireBrokerPlanHandle(plan, ledger, broker);
        require(broker.reservationId().isPresent()
                && broker.reservationId().orElseThrow().equals(ledger.reservationId().orElseThrow()),
                "Recovery broker reservation identity differs from its ledger");
    }

    private static boolean componentwiseAtMost(Map<KeyId, AEAmount> observed, Map<KeyId, AEAmount> maximum) {
        for (var entry : observed.entrySet()) {
            AEAmount limit = maximum.get(entry.getKey());
            if (limit == null || entry.getValue().compareTo(limit) > 0)
                return false;
        }
        return true;
    }

    private static boolean requiresRecovery(ExactCpuLedgerSnapshot ledger, ExactTransferBrokerSnapshot broker,
            Optional<ExactWorkOrderSnapshot> workOrder) {
        if (ledger.state() == ExactCpuLedgerState.FAIL_CLOSED || broker.state() == ExactTransferBrokerState.FAIL_CLOSED
                || broker.transferDiscrepancy().isPresent())
            return true;
        if (workOrder.isEmpty())
            return false;
        ExactWorkOrderSnapshot order = workOrder.orElseThrow();
        return order.state() == ExactWorkOrderState.FAIL_CLOSED || order.inFlightCommand().isPresent()
                || order.discrepancy().isPresent()
                || order.transferDiscrepancy().isPresent()
                || order.completedEvidence().isPresent() && !order.completedEvidenceProgressApplied();
    }

    /** Pure sealed-manifest progress check used by both capture validation and persistence restore. */
    static void validateWorkProgress(ExactCraftingPlan plan, ExactWorkOrderSnapshot order) {
        require(!order.completedEvidenceProgressApplied(),
                "Applied completed evidence has no reachable live producer in this recovery version");
        int count = plan.executionManifests().size();
        boolean settlement = order.state() == ExactWorkOrderState.SETTLEMENT_PENDING
                || order.releaseMode().filter(mode -> mode == ExactWorkOrderReleaseMode.SETTLEMENT).isPresent();
        boolean finished = order.causalStepIndex() == count;
        if (settlement) {
            require(order.causalStepIndex() == count && order.remainingExecutions().equals(AEAmount.ZERO)
                    && order.selectionRemaining().isEmpty() && order.cycleMemberIndex() == -1
                    && order.cycleRemainingRepetitions().equals(AEAmount.ZERO)
                    && order.cycleMemberRemainingExecutions().equals(AEAmount.ZERO)
                    && order.cycleSelectionRemaining().isEmpty(),
                    "Settlement must have consumed every sealed manifest");
        } else if (finished) {
            require(order.remainingExecutions().equals(AEAmount.ZERO) && order.selectionRemaining().isEmpty()
                    && order.cycleMemberIndex() == -1 && order.cycleRemainingRepetitions().equals(AEAmount.ZERO)
                    && order.cycleMemberRemainingExecutions().equals(AEAmount.ZERO)
                    && order.cycleSelectionRemaining().isEmpty(),
                    "Finished cancellation cursor retained unconsumed sealed progress");
        } else {
            require(order.causalStepIndex() < count, "Non-terminal work order has no sealed manifest to execute");
            ExecutionManifest manifest = plan.executionManifests().get(order.causalStepIndex());
            if (manifest instanceof NormalExecutionManifest normal) {
                require(order.cycleMemberIndex() == -1 && order.cycleRemainingRepetitions().equals(AEAmount.ZERO)
                        && order.cycleMemberRemainingExecutions().equals(AEAmount.ZERO)
                        && order.cycleSelectionRemaining().isEmpty(), "Normal manifest retained cycle progress");
                validateProgress(normal.execution(), order.remainingExecutions(), order.selectionRemaining());
            } else {
                CycleExecutionManifest cycle = (CycleExecutionManifest) manifest;
                require(order.remainingExecutions().equals(AEAmount.ZERO) && order.selectionRemaining().isEmpty()
                        && order.cycleMemberIndex() >= 0 && order.cycleMemberIndex() < cycle.memberExecutions().size()
                        && !order.cycleRemainingRepetitions().equals(AEAmount.ZERO)
                        && order.cycleRemainingRepetitions().compareTo(cycle.repetitions()) <= 0,
                        "Cycle manifest retained invalid normal or repetition progress");
                validateProgress(cycle.memberExecutions().get(order.cycleMemberIndex()),
                        order.cycleMemberRemainingExecutions(), order.cycleSelectionRemaining());
            }
        }
        order.outstandingCommand().ifPresent(command -> validateRetainedCommand(plan, order, command));
        order.inFlightCommand().ifPresent(command -> validateRetainedCommand(plan, order, command));
        order.discrepancy().ifPresent(value -> validateRetainedCommand(plan, order, value.command()));
        if (order.completedEvidence().isPresent() && !order.completedEvidenceProgressApplied())
            validateRetainedCommand(plan, order, order.completedEvidence().orElseThrow().command());
        validateCustody(plan, order);
    }

    /**
     * Replays only bounded sealed structural prefixes into a signed exact projection. Signed accumulation is
     * intentional: a reusable candidate can return its own key and be physically feasible while an intermediate
     * aggregate gross debit would temporarily look negative. The final projected custody remains non-negative.
     */
    private static void validateCustody(ExactCraftingPlan plan, ExactWorkOrderSnapshot order) {
        TreeMap<KeyId, BigInteger> projection = new TreeMap<>(java.util.Comparator.comparingInt(KeyId::value));
        applySigned(projection, plan.initialStorageDebits(), 1);
        List<ExecutionManifest> manifests = plan.executionManifests();
        for (int index = 0; index < order.causalStepIndex(); index++) {
            applyManifest(projection, manifests.get(index));
        }
        if (order.causalStepIndex() < manifests.size()) {
            ExecutionManifest current = manifests.get(order.causalStepIndex());
            if (current instanceof NormalExecutionManifest normal) {
                applyExecution(projection, normal.execution(),
                        normal.execution().executions().subtractExact(order.remainingExecutions()));
            } else {
                CycleExecutionManifest cycle = (CycleExecutionManifest) current;
                AEAmount completedTurns = cycle.repetitions().subtractExact(order.cycleRemainingRepetitions());
                for (SealedPatternExecution member : cycle.memberExecutions()) {
                    applyRepeatedExecution(projection, member, completedTurns);
                }
                for (int member = 0; member < order.cycleMemberIndex(); member++) {
                    SealedPatternExecution sealed = cycle.memberExecutions().get(member);
                    applyExecution(projection, sealed, sealed.executions());
                }
                SealedPatternExecution active = cycle.memberExecutions().get(order.cycleMemberIndex());
                applyExecution(projection, active,
                        active.executions().subtractExact(order.cycleMemberRemainingExecutions()));
            }
        }

        if (order.inFlightCommand().isPresent()) {
            applySigned(projection, order.inFlightCommand().orElseThrow().custodyInputs(), -1);
        } else if (order.completedEvidence().isPresent()) {
            applySigned(projection, order.completedEvidence().orElseThrow().command().custodyInputs(), -1);
        } else if (order.discrepancy().isPresent()) {
            ExactWorkDiscrepancy discrepancy = order.discrepancy().orElseThrow();
            TreeMap<KeyId, BigInteger> unmerged = new TreeMap<>(projection);
            applySigned(unmerged, discrepancy.command().custodyInputs(), -1);
            TreeMap<KeyId, BigInteger> merged = new TreeMap<>(unmerged);
            applySigned(merged, discrepancy.actualOutputs(), 1);
            applySigned(merged, discrepancy.actualRemainders(), 1);
            require(order.custody().equals(positiveAmounts(unmerged))
                    || order.custody().equals(positiveAmounts(merged)),
                    "Discrepancy custody is neither retained executor inputs nor the exact merged actual goods");
            return;
        }

        Map<KeyId, AEAmount> expected = positiveAmounts(projection);
        boolean settlement = order.state() == ExactWorkOrderState.SETTLEMENT_PENDING
                || order.releaseMode().filter(mode -> mode == ExactWorkOrderReleaseMode.SETTLEMENT).isPresent();
        if (settlement) {
            require(expected.equals(terminalCustody(plan)),
                    "Settlement cursor does not derive the sealed terminal custody");
        }
        if (order.state() == ExactWorkOrderState.RELEASE_PENDING || order.transferDiscrepancy().isPresent()
                || order.state() == ExactWorkOrderState.FAIL_CLOSED && order.releaseMode().isPresent()) {
            require(componentwiseAtMost(order.custody(), expected),
                    "Released work-order custody exceeds its exact pre-release projection");
            return;
        }
        if (order.state() == ExactWorkOrderState.COMPLETED) {
            require(order.custody().isEmpty(), "Completed work-order must retain no released custody");
            return;
        }
        require(order.custody().equals(expected), "Work-order custody differs from its sealed exact projection");
    }

    private static void applyManifest(TreeMap<KeyId, BigInteger> projection, ExecutionManifest manifest) {
        if (manifest instanceof NormalExecutionManifest normal) {
            applyExecution(projection, normal.execution(), normal.execution().executions());
            return;
        }
        CycleExecutionManifest cycle = (CycleExecutionManifest) manifest;
        for (SealedPatternExecution member : cycle.memberExecutions()) {
            applyRepeatedExecution(projection, member, cycle.repetitions());
        }
    }

    /** Applies a complete sealed cycle member for an exact number of turns without iterating by quantity. */
    private static void applyRepeatedExecution(TreeMap<KeyId, BigInteger> projection, SealedPatternExecution sealed,
            AEAmount repetitions) {
        if (repetitions.equals(AEAmount.ZERO))
            return;
        for (PlannedInputSelection source : sealed.plannedSelections()) {
            CompiledInputSpec input = sealed.pattern().inputs().get(source.inputIndex());
            CompiledCandidateSpec candidate = input.candidates().get(source.candidateIndex());
            AEAmount units = source.templateUnits().multiply(repetitions);
            applySigned(projection, candidate.key(), candidate.amountPerTemplate().multiply(units), -1);
            candidate.remainder().ifPresent(remainder -> applySigned(projection, remainder.key(),
                    remainder.amountPerTemplate().multiply(units), 1));
        }
        AEAmount executions = sealed.executions().multiply(repetitions);
        for (CompiledOutputSpec output : sealed.pattern().outputs()) {
            applySigned(projection, output.key(), output.amountPerExecution().multiply(executions), 1);
        }
    }

    private static void applyExecution(TreeMap<KeyId, BigInteger> projection, SealedPatternExecution sealed,
            AEAmount executions) {
        if (executions.equals(AEAmount.ZERO))
            return;
        int selection = 0;
        for (int inputIndex = 0; inputIndex < sealed.pattern().inputs().size(); inputIndex++) {
            CompiledInputSpec input = sealed.pattern().inputs().get(inputIndex);
            AEAmount required = input.multiplier().multiply(executions);
            while (selection < sealed.plannedSelections().size()
                    && sealed.plannedSelections().get(selection).inputIndex() == inputIndex) {
                PlannedInputSelection source = sealed.plannedSelections().get(selection++);
                AEAmount used = source.templateUnits().min(required);
                if (!used.equals(AEAmount.ZERO)) {
                    CompiledCandidateSpec candidate = input.candidates().get(source.candidateIndex());
                    applySigned(projection, candidate.key(), candidate.amountPerTemplate().multiply(used), -1);
                    candidate.remainder().ifPresent(remainder -> applySigned(projection, remainder.key(),
                            remainder.amountPerTemplate().multiply(used), 1));
                    required = required.subtractExact(used);
                }
            }
            require(required.equals(AEAmount.ZERO), "Sealed execution prefix lacks its exact selected inputs");
        }
        require(selection == sealed.plannedSelections().size(), "Sealed execution has an unknown input selection");
        for (CompiledOutputSpec output : sealed.pattern().outputs()) {
            applySigned(projection, output.key(), output.amountPerExecution().multiply(executions), 1);
        }
    }

    private static Map<KeyId, AEAmount> terminalCustody(ExactCraftingPlan plan) {
        TreeMap<KeyId, BigInteger> expected = new TreeMap<>(java.util.Comparator.comparingInt(KeyId::value));
        applySigned(expected, plan.finalSurplus(), 1);
        applySigned(expected, plan.request().output(), plan.request().amount(), 1);
        return positiveAmounts(expected);
    }

    private static void applySigned(TreeMap<KeyId, BigInteger> target, Map<KeyId, AEAmount> amounts, int sign) {
        for (Map.Entry<KeyId, AEAmount> entry : amounts.entrySet()) {
            applySigned(target, entry.getKey(), entry.getValue(), sign);
        }
    }

    private static void applySigned(TreeMap<KeyId, BigInteger> target, KeyId key, AEAmount amount, int sign) {
        BigInteger value = target.getOrDefault(key, BigInteger.ZERO)
                .add(amount.toBigInteger().multiply(BigInteger.valueOf(sign)));
        if (value.abs().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS)
            throw new IllegalArgumentException("Recovery custody projection exceeds the exact quantity bound");
        if (!target.containsKey(key) && target.size() >= PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS)
            throw new IllegalArgumentException("Recovery custody projection exceeds the exact key bound");
        if (value.signum() == 0)
            target.remove(key);
        else
            target.put(key, value);
    }

    private static Map<KeyId, AEAmount> positiveAmounts(TreeMap<KeyId, BigInteger> signed) {
        TreeMap<KeyId, AEAmount> result = new TreeMap<>(java.util.Comparator.comparingInt(KeyId::value));
        for (Map.Entry<KeyId, BigInteger> entry : signed.entrySet()) {
            require(entry.getValue().signum() >= 0, "Recovery custody projection became negative");
            if (entry.getValue().signum() != 0)
                result.put(entry.getKey(), AEAmount.of(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    private static void validateProgress(SealedPatternExecution sealed, AEAmount remaining, List<AEAmount> cursor) {
        require(!remaining.equals(AEAmount.ZERO) && remaining.compareTo(sealed.executions()) <= 0
                && cursor.size() == sealed.plannedSelections().size(),
                "Sealed member progress has an unreachable execution or cursor shape");
        AEAmount completed = sealed.executions().subtractExact(remaining);
        int selection = 0;
        for (int inputIndex = 0; inputIndex < sealed.pattern().inputs().size(); inputIndex++) {
            CompiledInputSpec input = sealed.pattern().inputs().get(inputIndex);
            AEAmount consumed = input.multiplier().multiply(completed);
            while (selection < sealed.plannedSelections().size()
                    && sealed.plannedSelections().get(selection).inputIndex() == inputIndex) {
                AEAmount original = sealed.plannedSelections().get(selection).templateUnits();
                AEAmount used = original.min(consumed);
                AEAmount expected = original.subtractExact(used);
                require(cursor.get(selection).equals(expected),
                        "Selection cursor is not the deterministic sealed candidate prefix");
                consumed = consumed.subtractExact(used);
                selection++;
            }
            require(consumed.equals(AEAmount.ZERO), "Selection cursor did not cover completed sealed input work");
        }
        require(selection == sealed.plannedSelections().size(), "Selection cursor contains an unknown sealed input");
    }

    private static void validateRetainedCommand(ExactCraftingPlan plan, ExactWorkOrderSnapshot order,
            ExactWorkCommand command) {
        require(command.location().causalStepIndex() == order.causalStepIndex()
                && command.batchId().equals(plan.executionManifests().get(order.causalStepIndex()).batchId()),
                "Retained command does not belong to current sealed causal step");
        int member = command.location().cycleMemberIndex();
        ExecutionManifest manifest = plan.executionManifests().get(order.causalStepIndex());
        if (member < 0) {
            require(manifest instanceof NormalExecutionManifest
                    && command.pattern() == ((NormalExecutionManifest) manifest)
                            .execution().pattern(),
                    "Retained normal command pattern differs from sealed manifest");
        } else {
            require(manifest instanceof CycleExecutionManifest && member == order.cycleMemberIndex()
                    && command.pattern() == ((CycleExecutionManifest) manifest).memberExecutions().get(member)
                            .pattern(),
                    "Retained cycle command pattern differs from sealed manifest");
        }
        SealedPatternExecution sealed = member < 0 ? ((NormalExecutionManifest) manifest).execution()
                : ((CycleExecutionManifest) manifest).memberExecutions().get(member);
        List<AEAmount> cursor = member < 0 ? order.selectionRemaining() : order.cycleSelectionRemaining();
        validateCommandPrefix(sealed, cursor, command);
    }

    private static void validateCommandPrefix(SealedPatternExecution sealed, List<AEAmount> cursor,
            ExactWorkCommand command) {
        require(command.executionWindow() <= Long.MAX_VALUE, "Command window must remain a physical signed-long value");
        ArrayList<PlannedInputSelection> expected = new ArrayList<>();
        int original = 0;
        AEAmount window = AEAmount.of(command.executionWindow());
        for (int inputIndex = 0; inputIndex < sealed.pattern().inputs().size(); inputIndex++) {
            CompiledInputSpec input = sealed.pattern().inputs().get(inputIndex);
            AEAmount need = input.multiplier().multiply(window);
            while (original < sealed.plannedSelections().size()
                    && sealed.plannedSelections().get(original).inputIndex() == inputIndex) {
                PlannedInputSelection source = sealed.plannedSelections().get(original);
                AEAmount take = cursor.get(original).min(need);
                if (!take.equals(AEAmount.ZERO)) {
                    CompiledCandidateSpec candidate = input.candidates().get(source.candidateIndex());
                    AEAmount gross = candidate.amountPerTemplate().multiply(take);
                    expected.add(new PlannedInputSelection(inputIndex, source.candidateIndex(), take,
                            source.consumedKey(), gross, gross, candidate.remainder()
                                    .map(value -> new appeng.rebuild.planner.PlannedRemainderReturn(value.key(),
                                            value.amountPerTemplate().multiply(take)))));
                    need = need.subtractExact(take);
                }
                original++;
            }
            require(need.equals(AEAmount.ZERO), "Retained command cannot be produced by its current selection cursor");
        }
        require(original == sealed.plannedSelections().size() && expected.equals(command.plannedSelections()),
                "Retained command selections are not the exact current sealed cursor prefix");
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalArgumentException(message);
    }

    private enum CheckpointClass {
        PREPARED,
        RESERVED,
        RELEASE_PENDING,
        HANDED_OFF
    }
}

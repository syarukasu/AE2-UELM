package appeng.rebuild.execution;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import appeng.api.networking.security.IActionSource;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.CompiledCandidateSpec;
import appeng.rebuild.pattern.CompiledInputSpec;
import appeng.rebuild.pattern.CompiledOutputSpec;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.planner.PlannedInputSelection;
import appeng.rebuild.planner.PlannedRemainderReturn;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.storage.BrokerExactStorage;

/**
 * Sole owner of the escrow transferred from an exact CPU reservation.
 *
 * <p>
 * This phase only turns sealed <em>normal</em> manifests into bounded physical commands and accounts for executor
 * evidence. It deliberately neither touches storage/world state nor releases, settles, cancels, or executes compact
 * cycle manifests. Every mutating entry point is package-bound, server-thread-gated, and rejects re-entry.
 */
public final class ExactWorkOrder {
    private static final Comparator<KeyId> KEY_ORDER = Comparator.comparingInt(KeyId::value);
    private static final AEAmount LONG_MAX_AMOUNT = AEAmount.of(Long.MAX_VALUE);
    private static final BigInteger MAX_BOUNDED_AMOUNT = BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS)
            .subtract(BigInteger.ONE);

    private final WorkOrderId workOrderId;
    private final ReservedPlanLease lease;
    private final BrokerExactStorage storage;
    private final ServerThreadGate serverThread;
    private final IActionSource actionSource;
    private final ExactCpuLedger ledger;
    private final List<ExecutionManifest> manifests;

    /** Only non-zero exact quantities are retained. It is never exposed without a defensive immutable copy. */
    private TreeMap<KeyId, AEAmount> custody = new TreeMap<>(KEY_ORDER);
    private ExactWorkOrderState state = ExactWorkOrderState.READY;
    private int causalStepIndex;
    private AEAmount remainingExecutions = AEAmount.ZERO;
    private List<AEAmount> selectionRemaining = List.of();
    /** Exact resumable progress for the active compact cycle; absent when {@code cycleMemberIndex == -1}. */
    private AEAmount cycleRemainingRepetitions = AEAmount.ZERO;
    private int cycleMemberIndex = -1;
    private AEAmount cycleMemberRemainingExecutions = AEAmount.ZERO;
    private List<AEAmount> cycleSelectionRemaining = List.of();
    private IssuedCommand outstanding;
    private IssuedCommand inFlight;
    private ExactWorkDiscrepancy discrepancy;
    private ExactCompletedCommandEvidence completedEvidence;
    private boolean completedEvidenceProgressApplied;
    private long nextGeneration;
    private boolean generationExhausted;
    private boolean entered;

    private ExactWorkOrder(WorkOrderId workOrderId, ReservedPlanLease lease, Map<KeyId, AEAmount> initialCustody,
            BrokerExactStorage storage, ServerThreadGate serverThread, IActionSource actionSource,
            ExactCpuLedger ledger) {
        this.workOrderId = Objects.requireNonNull(workOrderId, "workOrderId");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
        this.actionSource = Objects.requireNonNull(actionSource, "actionSource");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.manifests = List.copyOf(lease.plan().executionManifests());
        this.custody.putAll(ExactReservationReceipt.copyDebitsOrEmpty(initialCustody, "initialCustody"));
        if (!this.custody.equals(lease.reservedDebits())) {
            throw new IllegalArgumentException("Initial work-order custody must exactly equal the transferred lease");
        }
        loadCurrentNormalProgress();
    }

    /** Assignment-only materialization from the broker's fully validated pre-handoff staging record. */
    static ExactWorkOrder fromStaged(WorkOrderId workOrderId, ReservedPlanLease lease, Map<KeyId, AEAmount> custody,
            BrokerExactStorage storage, ServerThreadGate serverThread, IActionSource actionSource,
            ExactCpuLedger ledger) {
        return new ExactWorkOrder(workOrderId, lease, custody, storage, serverThread, actionSource, ledger);
    }

    /**
     * Seals the next bounded normal-manifest execution instruction without consulting live storage or recipes. Rejected
     * instructions retain all selection progress and custody; command generations are intentionally not reusable
     * identities.
     */
    synchronized ExactWorkOrderCommandResult issueNext(long requestedWindow) {
        ExactWorkOrderCommandResult.Unavailable admission = beginIssue();
        if (admission != null) {
            return admission;
        }
        try {
            if (state != ExactWorkOrderState.READY) {
                return unavailable(ExactWorkOrderCommandResult.Reason.WRONG_STATE);
            }
            if (requestedWindow <= 0) {
                return unavailable(ExactWorkOrderCommandResult.Reason.INVALID_WINDOW);
            }
            if (causalStepIndex >= manifests.size()) {
                return finishIfTerminal();
            }
            if (generationExhausted) {
                state = ExactWorkOrderState.FAIL_CLOSED;
                return unavailable(ExactWorkOrderCommandResult.Reason.COMMAND_GENERATION_EXHAUSTED);
            }
            ExecutionManifest manifest = manifests.get(causalStepIndex);
            final SealedPatternExecution execution;
            final appeng.rebuild.planner.PlannedBatchId batchId;
            final ExactWorkCommandLocation location;
            final AEAmount activeRemaining;
            final List<AEAmount> activeSelections;
            if (manifest instanceof NormalExecutionManifest normal) {
                execution = normal.execution();
                batchId = normal.batchId();
                location = ExactWorkCommandLocation.normal(causalStepIndex, remainingExecutions);
                activeRemaining = remainingExecutions;
                activeSelections = selectionRemaining;
            } else if (manifest instanceof CycleExecutionManifest cycle) {
                if (cycleMemberIndex < 0 || cycleMemberIndex >= cycle.memberExecutions().size()) {
                    return failIssueInvariant();
                }
                execution = cycle.memberExecutions().get(cycleMemberIndex);
                batchId = cycle.batchId();
                location = ExactWorkCommandLocation.cycle(causalStepIndex, cycleMemberIndex,
                        cycleRemainingRepetitions, cycleMemberRemainingExecutions);
                activeRemaining = cycleMemberRemainingExecutions;
                activeSelections = cycleSelectionRemaining;
                if (atCycleMemberStart(execution) && !hasIncomingCycleLink(cycle)) {
                    return failIssueInvariant();
                }
            } else {
                return failIssueInvariant();
            }
            if (activeRemaining.equals(AEAmount.ZERO)
                    || activeSelections.size() != execution.plannedSelections().size()) {
                return failIssueInvariant();
            }

            long maximumWindow = maximumWindow(requestedWindow, execution, activeRemaining);
            if (maximumWindow <= 0) {
                return unavailable(ExactWorkOrderCommandResult.Reason.PHYSICAL_WINDOW_UNAVAILABLE);
            }
            ExactWorkCommand command = largestPhysicalCommand(execution, batchId, location, activeSelections,
                    maximumWindow);
            if (command == null) {
                return unavailable(ExactWorkOrderCommandResult.Reason.PHYSICAL_WINDOW_UNAVAILABLE);
            }
            ProgressDelta progress = ProgressDelta.from(execution.plannedSelections(), activeSelections,
                    command.plannedSelections(), command.executionWindow());
            outstanding = new IssuedCommand(command, progress);
            state = ExactWorkOrderState.COMMAND_OUTSTANDING;
            if (nextGeneration == Long.MAX_VALUE) {
                generationExhausted = true;
            } else {
                nextGeneration++;
            }
            return new ExactWorkOrderCommandResult.Issued(command);
        } catch (RuntimeException failure) {
            return failIssueInvariant();
        } finally {
            endOperation();
        }
    }

    /** Transfers the entire sealed command's input custody to the executor; partial acknowledgement is impossible. */
    synchronized ExactWorkOrderTransitionResult accept(WorkCommandAcceptance acceptance) {
        ExactWorkOrderTransitionResult.Failure admission = beginTransition();
        if (admission != null) {
            return admission;
        }
        try {
            if (state != ExactWorkOrderState.COMMAND_OUTSTANDING) {
                return transitionFailure(ExactWorkOrderTransitionResult.Reason.WRONG_STATE);
            }
            if (acceptance == null || outstanding == null || !outstanding.command().equals(acceptance.command())) {
                return transitionFailure(ExactWorkOrderTransitionResult.Reason.IDENTITY_MISMATCH);
            }
            TreeMap<KeyId, AEAmount> transferred = subtractCopy(custody, outstanding.command().custodyInputs());
            if (transferred == null || projectedCustody(outstanding.command()) == null) {
                return failTransitionInvariant();
            }
            custody = transferred;
            inFlight = outstanding;
            outstanding = null;
            state = ExactWorkOrderState.IN_FLIGHT;
            return new ExactWorkOrderTransitionResult.Accepted(snapshot());
        } catch (RuntimeException failure) {
            return failTransitionInvariant();
        } finally {
            endOperation();
        }
    }

    /** Rejects precisely the still-unaccepted command and leaves custody and selection cursors unchanged. */
    synchronized ExactWorkOrderTransitionResult reject(WorkCommandRejection rejection) {
        ExactWorkOrderTransitionResult.Failure admission = beginTransition();
        if (admission != null) {
            return admission;
        }
        try {
            if (state != ExactWorkOrderState.COMMAND_OUTSTANDING) {
                return transitionFailure(ExactWorkOrderTransitionResult.Reason.WRONG_STATE);
            }
            if (rejection == null || outstanding == null || !outstanding.command().equals(rejection.command())) {
                return transitionFailure(ExactWorkOrderTransitionResult.Reason.IDENTITY_MISMATCH);
            }
            outstanding = null;
            state = ExactWorkOrderState.READY;
            return new ExactWorkOrderTransitionResult.Rejected(snapshot());
        } finally {
            endOperation();
        }
    }

    /**
     * Credits every reported good exactly once. A mismatch retains the actual goods plus immutable evidence and then
     * closes the order; only a complete exact match advances per-selection cursors.
     */
    synchronized ExactWorkOrderTransitionResult complete(WorkCommandCompletion completion) {
        ExactWorkOrderTransitionResult.Failure admission = beginTransition();
        if (admission != null) {
            return admission;
        }
        try {
            if (state != ExactWorkOrderState.IN_FLIGHT) {
                return transitionFailure(ExactWorkOrderTransitionResult.Reason.WRONG_STATE);
            }
            if (completion == null || inFlight == null || !inFlight.command().equals(completion.command())) {
                return transitionFailure(ExactWorkOrderTransitionResult.Reason.IDENTITY_MISMATCH);
            }
            IssuedCommand completed = inFlight;
            // The executor has already physically produced these reported goods. Retain them before evaluating a
            // mismatch so fail-closed accounting cannot silently discard physical custody.
            boolean mismatch = !completed.command().expectedOutputs().equals(completion.actualOutputs())
                    || !completed.command().expectedRemainders().equals(completion.actualRemainders());
            ExactWorkDiscrepancy observed = mismatch
                    ? new ExactWorkDiscrepancy(completed.command(), completed.command().expectedOutputs(),
                            completion.actualOutputs(), completed.command().expectedRemainders(),
                            completion.actualRemainders())
                    : null;
            final TreeMap<KeyId, AEAmount> credited;
            try {
                credited = creditCopy(custody, completion.actualOutputs(), completion.actualRemainders());
            } catch (RuntimeException boundedCustodyFailure) {
                if (observed != null) {
                    return closeWithUnmergedDiscrepancy(observed);
                }
                return closeWithCompletedEvidence(exactCompletedEvidence(completed, completion), false);
            }
            if (mismatch) {
                custody = credited;
                inFlight = null;
                discrepancy = observed;
                state = ExactWorkOrderState.FAIL_CLOSED;
                return transitionFailure(ExactWorkOrderTransitionResult.Reason.RESULT_MISMATCH);
            }
            try {
                ExactCompletedCommandEvidence evidence = exactCompletedEvidence(completed, completion);
                WorkProgress staged = completed.command().location().isCycle()
                        ? stageCycleProgress(completed, credited)
                        : stageNormalProgress(completed);
                ExactWorkOrderState nextState = ExactWorkOrderState.READY;
                if (staged.causalStepIndex() >= manifests.size()) {
                    if (!credited.equals(expectedTerminalCustody())) {
                        // A terminal mismatch is not an applied transition. The command evidence remains the sole
                        // recovery authority; neither custody nor cursors may represent a partial completion.
                        return closeWithCompletedEvidence(evidence, false);
                    }
                    nextState = ExactWorkOrderState.SETTLEMENT_PENDING;
                }
                // Validate the complete proposed durable observation before the first live assignment. In particular,
                // snapshot construction must not turn an already-applied completion into evidence marked unapplied.
                ExactWorkOrderTransitionResult.Completed result = new ExactWorkOrderTransitionResult.Completed(
                        snapshotFor(nextState, credited, staged));
                // All arithmetic, links, terminal checks, and persistence-shape validation occurred before this point.
                // The following is deliberately assignment-only and cannot throw.
                custody = credited;
                inFlight = null;
                applyProgress(staged);
                state = nextState;
                return result;
            } catch (RuntimeException invariant) {
                return closeWithCompletedEvidence(exactCompletedEvidence(completed, completion), false);
            }
        } catch (RuntimeException failure) {
            return failTransitionInvariant();
        } finally {
            endOperation();
        }
    }

    /** Returns a detached exact observation; it grants no mutation or release authority. */
    public synchronized ExactWorkOrderSnapshot snapshot() {
        return new ExactWorkOrderSnapshot(state, lease.planId(), lease.handle(), lease.reservationId(),
                lease.leaseIdentity(), workOrderId, custody, causalStepIndex, remainingExecutions, selectionRemaining,
                cycleRemainingRepetitions, cycleMemberIndex, cycleMemberRemainingExecutions, cycleSelectionRemaining,
                nextGeneration, generationExhausted,
                Optional.ofNullable(outstanding).map(IssuedCommand::command), Optional.ofNullable(inFlight)
                        .map(IssuedCommand::command),
                Optional.ofNullable(discrepancy), Optional.ofNullable(completedEvidence),
                completedEvidenceProgressApplied);
    }

    private ExactWorkOrderSnapshot snapshotFor(ExactWorkOrderState proposedState, Map<KeyId, AEAmount> proposedCustody,
            WorkProgress progress) {
        return new ExactWorkOrderSnapshot(proposedState, lease.planId(), lease.handle(), lease.reservationId(),
                lease.leaseIdentity(), workOrderId, proposedCustody, progress.causalStepIndex(),
                progress.remainingExecutions(), progress.selectionRemaining(), progress.cycleRemainingRepetitions(),
                progress.cycleMemberIndex(), progress.cycleMemberRemainingExecutions(),
                progress.cycleSelectionRemaining(), nextGeneration, generationExhausted, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    private ExactWorkOrderCommandResult.Unavailable beginIssue() {
        if (entered) {
            return unavailable(ExactWorkOrderCommandResult.Reason.REENTRANT);
        }
        entered = true;
        if (!onServerThread()) {
            ExactWorkOrderCommandResult.Unavailable failure = unavailable(
                    ExactWorkOrderCommandResult.Reason.WRONG_THREAD);
            entered = false;
            return failure;
        }
        return null;
    }

    private ExactWorkOrderTransitionResult.Failure beginTransition() {
        if (entered) {
            return transitionFailure(ExactWorkOrderTransitionResult.Reason.REENTRANT);
        }
        entered = true;
        if (!onServerThread()) {
            ExactWorkOrderTransitionResult.Failure failure = transitionFailure(
                    ExactWorkOrderTransitionResult.Reason.WRONG_THREAD);
            entered = false;
            return failure;
        }
        return null;
    }

    private boolean onServerThread() {
        try {
            return serverThread.isServerThread();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void endOperation() {
        entered = false;
    }

    private ExactWorkOrderCommandResult finishIfTerminal() {
        if (!custody.equals(expectedTerminalCustody())) {
            state = ExactWorkOrderState.FAIL_CLOSED;
            return unavailable(ExactWorkOrderCommandResult.Reason.INVARIANT_VIOLATION);
        }
        state = ExactWorkOrderState.SETTLEMENT_PENDING;
        return unavailable(ExactWorkOrderCommandResult.Reason.WRONG_STATE);
    }

    private ExactWorkOrderCommandResult.Unavailable unavailable(ExactWorkOrderCommandResult.Reason reason) {
        return new ExactWorkOrderCommandResult.Unavailable(reason, snapshot());
    }

    private ExactWorkOrderCommandResult failIssueInvariant() {
        state = ExactWorkOrderState.FAIL_CLOSED;
        return unavailable(ExactWorkOrderCommandResult.Reason.INVARIANT_VIOLATION);
    }

    private ExactWorkOrderTransitionResult.Failure transitionFailure(ExactWorkOrderTransitionResult.Reason reason) {
        return new ExactWorkOrderTransitionResult.Failure(reason, snapshot());
    }

    private ExactWorkOrderTransitionResult.Failure failTransitionInvariant() {
        state = ExactWorkOrderState.FAIL_CLOSED;
        return transitionFailure(ExactWorkOrderTransitionResult.Reason.INVARIANT_VIOLATION);
    }

    /** Retains executor evidence as the recovery authority when bounded exact custody cannot aggregate it. */
    private ExactWorkOrderTransitionResult.Failure closeWithUnmergedDiscrepancy(ExactWorkDiscrepancy observed) {
        inFlight = null;
        discrepancy = observed;
        state = ExactWorkOrderState.FAIL_CLOSED;
        return transitionFailure(ExactWorkOrderTransitionResult.Reason.RESULT_MISMATCH);
    }

    private ExactWorkOrderTransitionResult.Failure closeWithCompletedEvidence(ExactCompletedCommandEvidence evidence,
            boolean progressApplied) {
        inFlight = null;
        completedEvidence = evidence;
        completedEvidenceProgressApplied = progressApplied;
        state = ExactWorkOrderState.FAIL_CLOSED;
        return transitionFailure(ExactWorkOrderTransitionResult.Reason.INVARIANT_VIOLATION);
    }

    private ExactCompletedCommandEvidence exactCompletedEvidence(IssuedCommand completed,
            WorkCommandCompletion completion) {
        return new ExactCompletedCommandEvidence(completed.command(), completion.actualOutputs(),
                completion.actualRemainders());
    }

    private void loadCurrentNormalProgress() {
        if (causalStepIndex >= manifests.size()) {
            remainingExecutions = AEAmount.ZERO;
            selectionRemaining = List.of();
            return;
        }
        applyProgress(initialProgressFor(causalStepIndex));
    }

    private ProgressState progressAt(int index) {
        if (index >= manifests.size()) {
            return new ProgressState(AEAmount.ZERO, List.of());
        }
        ExecutionManifest manifest = manifests.get(index);
        if (manifest instanceof NormalExecutionManifest normal) {
            ArrayList<AEAmount> remaining = new ArrayList<>(normal.execution().plannedSelections().size());
            for (PlannedInputSelection selection : normal.execution().plannedSelections()) {
                remaining.add(selection.templateUnits());
            }
            return new ProgressState(normal.execution().executions(), List.copyOf(remaining));
        }
        return new ProgressState(AEAmount.ZERO, List.of());
    }

    /** Computes the exact cursor movement only; no custody or order progress is committed here. */
    private CycleProgress calculateCycleProgress(IssuedCommand completed) {
        if (causalStepIndex >= manifests.size()
                || !(manifests.get(causalStepIndex) instanceof CycleExecutionManifest cycle)
                || cycleMemberIndex < 0 || cycleMemberIndex >= cycle.memberExecutions().size()
                || !completed.command().location().equals(ExactWorkCommandLocation.cycle(causalStepIndex,
                        cycleMemberIndex, cycleRemainingRepetitions, cycleMemberRemainingExecutions))
                || !completed.command().batchId().equals(cycle.batchId()) || !completed.command().pattern()
                        .equals(cycle.memberExecutions().get(cycleMemberIndex).pattern())) {
            throw new IllegalStateException("Cycle completion does not match retained causal progress");
        }
        ProgressState progressed = completed.progress().apply(cycleMemberRemainingExecutions,
                cycleSelectionRemaining);
        return new CycleProgress(cycle, progressed);
    }

    /** Stages a complete cycle transition and validates all boundaries before any live field is assigned. */
    private WorkProgress stageCycleProgress(IssuedCommand completed, Map<KeyId, AEAmount> credited) {
        CycleProgress progress = calculateCycleProgress(completed);
        if (!progress.memberProgress().remainingExecutions().equals(AEAmount.ZERO)) {
            return new WorkProgress(causalStepIndex, remainingExecutions, selectionRemaining,
                    cycleRemainingRepetitions, cycleMemberIndex, progress.memberProgress().remainingExecutions(),
                    progress.memberProgress().selectionRemaining());
        }
        for (AEAmount cursor : progress.memberProgress().selectionRemaining()) {
            if (!cursor.equals(AEAmount.ZERO)) {
                throw new IllegalStateException("Completed cycle member retained a sealed selection cursor");
            }
        }
        CycleExecutionManifest cycle = progress.cycle();
        appeng.rebuild.planner.PlannedCycleLink outgoing = cycle.links().get(cycleMemberIndex);
        AEAmount available = credited.getOrDefault(outgoing.key(), AEAmount.ZERO);
        if (available.compareTo(outgoing.amountPerTurn()) < 0) {
            throw new IllegalStateException("Completed cycle member did not restore its sealed outgoing link");
        }
        int finalMember = cycle.memberExecutions().size() - 1;
        if (cycleMemberIndex != finalMember) {
            return cycleProgressFor(causalStepIndex, cycle, cycleRemainingRepetitions, cycleMemberIndex + 1);
        }
        AEAmount restoredSeed = credited.getOrDefault(cycle.seedKey(), AEAmount.ZERO);
        if (restoredSeed.compareTo(cycle.seedAmount()) < 0) {
            throw new IllegalStateException("Closing cycle member did not physically restore its retained seed");
        }
        AEAmount afterTurn = cycleRemainingRepetitions.subtractExact(AEAmount.ONE);
        if (!afterTurn.equals(AEAmount.ZERO)) {
            return cycleProgressFor(causalStepIndex, cycle, afterTurn, 0);
        }
        return initialProgressFor(causalStepIndex + 1);
    }

    private WorkProgress stageNormalProgress(IssuedCommand completed) {
        if (causalStepIndex >= manifests.size()
                || !(manifests.get(causalStepIndex) instanceof NormalExecutionManifest normal)
                || !completed.command().location().equals(ExactWorkCommandLocation.normal(causalStepIndex,
                        remainingExecutions))
                || !completed.command().batchId().equals(normal.batchId())
                || !completed.command().pattern().equals(normal.execution().pattern())) {
            throw new IllegalStateException("Normal completion does not match retained causal progress");
        }
        ProgressState progressed = completed.progress().apply(remainingExecutions, selectionRemaining);
        return progressed.remainingExecutions().equals(AEAmount.ZERO)
                ? initialProgressFor(causalStepIndex + 1)
                : new WorkProgress(causalStepIndex, progressed.remainingExecutions(), progressed.selectionRemaining(),
                        AEAmount.ZERO, -1, AEAmount.ZERO, List.of());
    }

    private WorkProgress initialProgressFor(int index) {
        if (index >= manifests.size()) {
            return new WorkProgress(index, AEAmount.ZERO, List.of(), AEAmount.ZERO, -1, AEAmount.ZERO, List.of());
        }
        ExecutionManifest manifest = manifests.get(index);
        if (manifest instanceof NormalExecutionManifest) {
            ProgressState normal = progressAt(index);
            return new WorkProgress(index, normal.remainingExecutions(), normal.selectionRemaining(), AEAmount.ZERO, -1,
                    AEAmount.ZERO, List.of());
        }
        if (manifest instanceof CycleExecutionManifest cycle) {
            return cycleProgressFor(index, cycle, cycle.repetitions(), 0);
        }
        throw new IllegalStateException("Unknown sealed execution manifest");
    }

    private WorkProgress cycleProgressFor(int index, CycleExecutionManifest cycle, AEAmount repetitions,
            int memberIndex) {
        if (memberIndex < 0 || memberIndex >= cycle.memberExecutions().size()) {
            throw new IllegalStateException("Cycle member index is out of bounds");
        }
        SealedPatternExecution member = cycle.memberExecutions().get(memberIndex);
        ArrayList<AEAmount> cursors = new ArrayList<>(member.plannedSelections().size());
        for (PlannedInputSelection selection : member.plannedSelections()) {
            cursors.add(selection.templateUnits());
        }
        return new WorkProgress(index, AEAmount.ZERO, List.of(), repetitions, memberIndex, member.executions(),
                List.copyOf(cursors));
    }

    private void applyProgress(WorkProgress progress) {
        causalStepIndex = progress.causalStepIndex();
        remainingExecutions = progress.remainingExecutions();
        selectionRemaining = progress.selectionRemaining();
        cycleRemainingRepetitions = progress.cycleRemainingRepetitions();
        cycleMemberIndex = progress.cycleMemberIndex();
        cycleMemberRemainingExecutions = progress.cycleMemberRemainingExecutions();
        cycleSelectionRemaining = progress.cycleSelectionRemaining();
    }

    private long maximumWindow(long requestedWindow, SealedPatternExecution execution, AEAmount remaining) {
        long maximum = Math.min(requestedWindow, remaining.min(LONG_MAX_AMOUNT).longValueExact());
        for (PlannedInputSelection selection : execution.plannedSelections()) {
            if (selection.initialRequiredAmount().compareTo(selection.grossConsumedAmount()) < 0) {
                return Math.min(maximum, 1L);
            }
        }
        return maximum;
    }

    /** Checks the complete incoming sealed link before the first physical window of a ring member is issued. */
    private boolean hasIncomingCycleLink(CycleExecutionManifest cycle) {
        int source = (cycleMemberIndex - 1 + cycle.memberExecutions().size()) % cycle.memberExecutions().size();
        appeng.rebuild.planner.PlannedCycleLink incoming = cycle.links().get(source);
        if (cycleMemberIndex == 0 && (!incoming.key().equals(cycle.seedKey())
                || !incoming.amountPerTurn().equals(cycle.seedAmount()))) {
            return false;
        }
        return custody.getOrDefault(incoming.key(), AEAmount.ZERO).compareTo(incoming.amountPerTurn()) >= 0;
    }

    private boolean atCycleMemberStart(SealedPatternExecution execution) {
        if (!cycleMemberRemainingExecutions.equals(execution.executions())
                || cycleSelectionRemaining.size() != execution.plannedSelections().size()) {
            return false;
        }
        for (int index = 0; index < cycleSelectionRemaining.size(); index++) {
            if (!cycleSelectionRemaining.get(index).equals(execution.plannedSelections().get(index).templateUnits())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds the largest feasible physical window without assuming feasibility is globally monotonic. Candidate
     * exhaustion points partition the window into bounded pieces where every command amount and projected custody is
     * affine. Each piece is solved algebraically after a bounded physical-prefix binary search.
     */
    private ExactWorkCommand largestPhysicalCommand(SealedPatternExecution execution,
            appeng.rebuild.planner.PlannedBatchId batchId, ExactWorkCommandLocation location,
            List<AEAmount> activeSelections, long maximumWindow) {
        TreeSet<Long> starts = structuralStarts(execution, activeSelections, maximumWindow);
        starts.add(1L);
        ExactWorkCommand best = null;
        long lower = 1L;
        for (Long next : starts.tailSet(2L, true)) {
            long upper = next - 1L;
            if (lower <= upper) {
                ExactWorkCommand candidate = largestInStructuralPiece(execution, batchId, location, activeSelections,
                        lower, upper);
                if (candidate != null && (best == null || candidate.executionWindow() > best.executionWindow())) {
                    best = candidate;
                }
            }
            lower = next;
        }
        if (lower <= maximumWindow) {
            ExactWorkCommand candidate = largestInStructuralPiece(execution, batchId, location, activeSelections,
                    lower, maximumWindow);
            if (candidate != null && (best == null || candidate.executionWindow() > best.executionWindow())) {
                best = candidate;
            }
        }
        return best;
    }

    private TreeSet<Long> structuralStarts(SealedPatternExecution execution, List<AEAmount> activeSelections,
            long maximumWindow) {
        TreeSet<Long> starts = new TreeSet<>();
        for (int inputIndex = 0; inputIndex < execution.pattern().inputs().size(); inputIndex++) {
            AEAmount cumulative = AEAmount.ZERO;
            AEAmount multiplier = execution.pattern().inputs().get(inputIndex).multiplier();
            for (int selectionIndex = 0; selectionIndex < execution.plannedSelections().size(); selectionIndex++) {
                PlannedInputSelection selection = execution.plannedSelections().get(selectionIndex);
                if (selection.inputIndex() != inputIndex) {
                    continue;
                }
                cumulative = cumulative.add(activeSelections.get(selectionIndex));
                // The next candidate starts at the first integral window whose required units exceed this completed
                // candidate's cumulative capacity. ceil(cumulative / multiplier) is one window too early when the
                // capacity divides exactly.
                AEAmount boundary = cumulative.divide(multiplier).add(AEAmount.ONE);
                if (boundary.compareTo(AEAmount.ONE) > 0 && boundary.compareTo(AEAmount.of(maximumWindow)) <= 0) {
                    starts.add(boundary.longValueExact());
                }
            }
        }
        return starts;
    }

    private ExactWorkCommand largestInStructuralPiece(SealedPatternExecution execution,
            appeng.rebuild.planner.PlannedBatchId batchId, ExactWorkCommandLocation location,
            List<AEAmount> activeSelections, long lower, long upper) {
        ExactWorkCommand first = buildPhysicalCommand(execution, batchId, location, activeSelections, lower);
        if (first == null) {
            return null;
        }
        long physicalUpper = upper;
        if (buildPhysicalCommand(execution, batchId, location, activeSelections, upper) == null) {
            long low = lower;
            long high = upper - 1L;
            while (low <= high) {
                long middle = low + ((high - low) >>> 1);
                if (buildPhysicalCommand(execution, batchId, location, activeSelections, middle) == null) {
                    high = middle - 1L;
                } else {
                    physicalUpper = middle;
                    low = middle + 1L;
                }
            }
        }
        ExactWorkCommand last = buildPhysicalCommand(execution, batchId, location, activeSelections, physicalUpper);
        long feasible = largestProjectedWindow(first, last, lower, physicalUpper);
        return feasible < lower ? null : tryBuildCommand(execution, batchId, location, activeSelections, feasible);
    }

    private long largestProjectedWindow(ExactWorkCommand first, ExactWorkCommand last, long lower, long upper) {
        Map<KeyId, BigInteger> firstProjection = unboundedProjection(first);
        Map<KeyId, BigInteger> lastProjection = unboundedProjection(last);
        TreeSet<KeyId> keys = new TreeSet<>(KEY_ORDER);
        keys.addAll(firstProjection.keySet());
        keys.addAll(lastProjection.keySet());
        long[] offsets = { 0L, upper - lower };
        BigInteger width = BigInteger.valueOf(offsets[1]);
        for (KeyId key : keys) {
            BigInteger base = firstProjection.getOrDefault(key, BigInteger.ZERO);
            BigInteger end = lastProjection.getOrDefault(key, BigInteger.ZERO);
            BigInteger slope = affineSlope(base, end, width);
            if (slope == null || !constrainAffine(offsets, base, slope, MAX_BOUNDED_AMOUNT)) {
                return -1L;
            }
        }
        Map<KeyId, AEAmount> firstInputs = first.custodyInputs();
        Map<KeyId, AEAmount> lastInputs = last.custodyInputs();
        keys.clear();
        keys.addAll(firstInputs.keySet());
        keys.addAll(lastInputs.keySet());
        for (KeyId key : keys) {
            BigInteger base = firstInputs.getOrDefault(key, AEAmount.ZERO).toBigInteger();
            BigInteger end = lastInputs.getOrDefault(key, AEAmount.ZERO).toBigInteger();
            BigInteger slope = affineSlope(base, end, width);
            BigInteger available = custody.getOrDefault(key, AEAmount.ZERO).toBigInteger();
            if (slope == null || !constrainAffine(offsets, base, slope, available)) {
                return -1L;
            }
        }
        return lower + offsets[1];
    }

    private static BigInteger affineSlope(BigInteger base, BigInteger end, BigInteger width) {
        BigInteger delta = end.subtract(base);
        if (width.signum() == 0) {
            return delta.signum() == 0 ? BigInteger.ZERO : null;
        }
        BigInteger[] division = delta.divideAndRemainder(width);
        return division[1].signum() == 0 ? division[0] : null;
    }

    /** Intersects the inclusive offset range with {@code 0 <= base + slope * offset <= maximum}. */
    private static boolean constrainAffine(long[] offsets, BigInteger base, BigInteger slope, BigInteger maximum) {
        if (maximum.signum() < 0) {
            return false;
        }
        if (slope.signum() > 0) {
            offsets[0] = Math.max(offsets[0], ceilDivClamped(base.negate(), slope));
            offsets[1] = Math.min(offsets[1], floorDivClamped(maximum.subtract(base), slope));
        } else if (slope.signum() < 0) {
            BigInteger positive = slope.negate();
            offsets[0] = Math.max(offsets[0], ceilDivClamped(base.subtract(maximum), positive));
            offsets[1] = Math.min(offsets[1], floorDivClamped(base, positive));
        } else if (base.signum() < 0 || base.compareTo(maximum) > 0) {
            return false;
        }
        return offsets[0] <= offsets[1];
    }

    private Map<KeyId, BigInteger> unboundedProjection(ExactWorkCommand command) {
        TreeMap<KeyId, BigInteger> projection = new TreeMap<>(KEY_ORDER);
        custody.forEach((key, amount) -> projection.put(key, amount.toBigInteger()));
        applySigned(projection, command.custodyInputs(), -1);
        applySigned(projection, command.expectedOutputs(), 1);
        applySigned(projection, command.expectedRemainders(), 1);
        projection.entrySet().removeIf(entry -> entry.getValue().signum() == 0);
        return Map.copyOf(projection);
    }

    private static void applySigned(TreeMap<KeyId, BigInteger> target, Map<KeyId, AEAmount> amounts, int sign) {
        for (Map.Entry<KeyId, AEAmount> entry : amounts.entrySet()) {
            target.merge(entry.getKey(), entry.getValue().toBigInteger().multiply(BigInteger.valueOf(sign)),
                    BigInteger::add);
        }
    }

    private static long floorDivClamped(BigInteger dividend, BigInteger divisor) {
        BigInteger[] result = dividend.divideAndRemainder(divisor);
        BigInteger quotient = result[1].signum() < 0 ? result[0].subtract(BigInteger.ONE) : result[0];
        return clampToLong(quotient);
    }

    private static long ceilDivClamped(BigInteger dividend, BigInteger divisor) {
        BigInteger[] result = dividend.divideAndRemainder(divisor);
        BigInteger quotient = result[1].signum() > 0 ? result[0].add(BigInteger.ONE) : result[0];
        return clampToLong(quotient);
    }

    private static long clampToLong(BigInteger value) {
        if (value.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0) {
            return Long.MIN_VALUE;
        }
        if (value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return Long.MAX_VALUE;
        }
        return value.longValue();
    }

    /** Pure command construction: it neither advances cursors nor changes custody. */
    private ExactWorkCommand tryBuildCommand(SealedPatternExecution execution,
            appeng.rebuild.planner.PlannedBatchId batchId, ExactWorkCommandLocation location,
            List<AEAmount> activeSelections, long window) {
        ExactWorkCommand command = buildPhysicalCommand(execution, batchId, location, activeSelections, window);
        return command != null && projectedCustody(command) != null ? command : null;
    }

    private ExactWorkCommand buildPhysicalCommand(SealedPatternExecution execution,
            appeng.rebuild.planner.PlannedBatchId batchId, ExactWorkCommandLocation location,
            List<AEAmount> activeSelections, long window) {
        try {
            CompiledPattern pattern = execution.pattern();
            List<PlannedInputSelection> selections = selectionsForWindow(pattern, execution.plannedSelections(),
                    activeSelections, window);
            Map<KeyId, AEAmount> inputs = aggregateInputs(selections);
            List<ExactWorkOutput> outputSlots = outputsForWindow(pattern, window);
            Map<KeyId, AEAmount> outputs = aggregateOutputs(outputSlots);
            Map<KeyId, AEAmount> remainders = aggregateRemainders(selections);
            ExactWorkCommand command = new ExactWorkCommand(
                    new WorkCommandId(lease.planId(), lease.leaseIdentity(), workOrderId,
                            nextGeneration),
                    lease.handle(), lease.reservationId(), batchId, location, pattern, window,
                    selections,
                    inputs, outputSlots, outputs, remainders);
            return command;
        } catch (IllegalArgumentException | ArithmeticException invalidPhysicalWindow) {
            return null;
        }
    }

    private List<PlannedInputSelection> selectionsForWindow(CompiledPattern pattern,
            List<PlannedInputSelection> originals, List<AEAmount> activeSelections, long window) {
        ArrayList<PlannedInputSelection> result = new ArrayList<>();
        int cursor = 0;
        AEAmount executions = AEAmount.of(window);
        for (int inputIndex = 0; inputIndex < pattern.inputs().size(); inputIndex++) {
            CompiledInputSpec input = pattern.inputs().get(inputIndex);
            AEAmount need = input.multiplier().multiply(executions);
            int priorCandidate = -1;
            while (cursor < originals.size() && originals.get(cursor).inputIndex() == inputIndex) {
                PlannedInputSelection original = originals.get(cursor);
                if (original.candidateIndex() <= priorCandidate) {
                    throw new IllegalArgumentException("Unordered sealed candidate selections");
                }
                priorCandidate = original.candidateIndex();
                AEAmount available = activeSelections.get(cursor);
                AEAmount take = need.equals(AEAmount.ZERO) ? AEAmount.ZERO : available.min(need);
                if (!take.equals(AEAmount.ZERO)) {
                    CompiledCandidateSpec candidate = input.candidates().get(original.candidateIndex());
                    AEAmount gross = candidate.amountPerTemplate().multiply(take);
                    Optional<PlannedRemainderReturn> remainder = candidate.remainder().map(
                            value -> new PlannedRemainderReturn(value.key(), value.amountPerTemplate().multiply(take)));
                    // A multi-execution reusable selection is deliberately split into one physical window. That one
                    // execution must physically receive its whole gross input, then returns its per-window remainder.
                    result.add(new PlannedInputSelection(inputIndex, original.candidateIndex(), take,
                            original.consumedKey(), gross, gross, remainder));
                    need = need.subtractExact(take);
                }
                cursor++;
            }
            if (!need.equals(AEAmount.ZERO)) {
                throw new IllegalArgumentException("Selection cursors cannot cover requested execution window");
            }
        }
        if (cursor != originals.size()) {
            throw new IllegalArgumentException("Selection list has an input outside the sealed pattern");
        }
        return List.copyOf(result);
    }

    private static List<ExactWorkOutput> outputsForWindow(CompiledPattern pattern, long window) {
        AEAmount executions = AEAmount.of(window);
        ArrayList<ExactWorkOutput> slots = new ArrayList<>(pattern.outputs().size());
        for (int index = 0; index < pattern.outputs().size(); index++) {
            CompiledOutputSpec output = pattern.outputs().get(index);
            slots.add(new ExactWorkOutput(index, output.key(), output.amountPerExecution().multiply(executions)));
        }
        return List.copyOf(slots);
    }

    private static Map<KeyId, AEAmount> aggregateInputs(List<PlannedInputSelection> selections) {
        TreeMap<KeyId, AEAmount> result = new TreeMap<>(KEY_ORDER);
        for (PlannedInputSelection selection : selections) {
            merge(result, selection.consumedKey(), selection.initialRequiredAmount());
        }
        return Map.copyOf(result);
    }

    private static Map<KeyId, AEAmount> aggregateOutputs(List<ExactWorkOutput> slots) {
        TreeMap<KeyId, AEAmount> result = new TreeMap<>(KEY_ORDER);
        for (ExactWorkOutput output : slots) {
            merge(result, output.key(), output.amount());
        }
        return Map.copyOf(result);
    }

    private static Map<KeyId, AEAmount> aggregateRemainders(List<PlannedInputSelection> selections) {
        TreeMap<KeyId, AEAmount> result = new TreeMap<>(KEY_ORDER);
        for (PlannedInputSelection selection : selections) {
            selection.remainderReturn().ifPresent(remainder -> merge(result, remainder.key(), remainder.amount()));
        }
        return Map.copyOf(result);
    }

    private Map<KeyId, AEAmount> expectedTerminalCustody() {
        TreeMap<KeyId, AEAmount> expected = new TreeMap<>(KEY_ORDER);
        expected.putAll(lease.plan().finalSurplus());
        merge(expected, lease.plan().request().output(), lease.plan().request().amount());
        return Map.copyOf(expected);
    }

    /** Pure post-completion custody preflight used before issue and rechecked before physical acceptance. */
    private TreeMap<KeyId, AEAmount> projectedCustody(ExactWorkCommand command) {
        TreeMap<KeyId, AEAmount> projected = subtractCopy(custody, command.custodyInputs());
        if (projected == null) {
            return null;
        }
        try {
            add(projected, command.expectedOutputs());
            add(projected, command.expectedRemainders());
            return projected;
        } catch (RuntimeException bounded) {
            return null;
        }
    }

    private static TreeMap<KeyId, AEAmount> subtractCopy(Map<KeyId, AEAmount> source, Map<KeyId, AEAmount> debits) {
        TreeMap<KeyId, AEAmount> copy = new TreeMap<>(KEY_ORDER);
        copy.putAll(source);
        for (Map.Entry<KeyId, AEAmount> debit : debits.entrySet()) {
            AEAmount current = copy.getOrDefault(debit.getKey(), AEAmount.ZERO);
            if (current.compareTo(debit.getValue()) < 0) {
                return null;
            }
            AEAmount remaining = current.subtractExact(debit.getValue());
            if (remaining.equals(AEAmount.ZERO)) {
                copy.remove(debit.getKey());
            } else {
                copy.put(debit.getKey(), remaining);
            }
        }
        return copy;
    }

    private static TreeMap<KeyId, AEAmount> creditCopy(Map<KeyId, AEAmount> source,
            Map<KeyId, AEAmount> outputs, Map<KeyId, AEAmount> remainders) {
        TreeMap<KeyId, AEAmount> copy = new TreeMap<>(KEY_ORDER);
        copy.putAll(source);
        add(copy, outputs);
        add(copy, remainders);
        return copy;
    }

    private static void add(TreeMap<KeyId, AEAmount> source, Map<KeyId, AEAmount> credits) {
        for (Map.Entry<KeyId, AEAmount> credit : credits.entrySet()) {
            if (!source.containsKey(credit.getKey()) && source.size() >= PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS) {
                throw new IllegalArgumentException("Exact custody key bound exceeded");
            }
            merge(source, credit.getKey(), credit.getValue());
        }
    }

    private static void merge(TreeMap<KeyId, AEAmount> target, KeyId key, AEAmount amount) {
        AEAmount merged = target.getOrDefault(key, AEAmount.ZERO).add(amount);
        if (merged.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
            throw new IllegalArgumentException("Exact custody quantity exceeds its bounded bit limit");
        }
        target.put(key, merged);
    }

    /** Immutable per-command cursor movement committed only after a matching completion. */
    private record ProgressDelta(List<AEAmount> selectionUnits, AEAmount executions) {
        private ProgressDelta {
            selectionUnits = List.copyOf(selectionUnits);
            Objects.requireNonNull(executions, "executions");
        }

        private static ProgressDelta from(List<PlannedInputSelection> originals, List<AEAmount> remaining,
                List<PlannedInputSelection> commandSelections, long executionWindow) {
            ArrayList<AEAmount> delta = new ArrayList<>(originals.size());
            for (int index = 0; index < originals.size(); index++) {
                delta.add(AEAmount.ZERO);
            }
            int originalCursor = 0;
            for (PlannedInputSelection command : commandSelections) {
                while (originalCursor < originals.size() && (originals.get(originalCursor).inputIndex() < command
                        .inputIndex() || originals.get(originalCursor).inputIndex() == command.inputIndex()
                                && originals.get(originalCursor).candidateIndex() < command.candidateIndex())) {
                    originalCursor++;
                }
                if (originalCursor >= originals.size() || !originals.get(originalCursor).consumedKey()
                        .equals(command.consumedKey())
                        || command.templateUnits().compareTo(remaining.get(originalCursor)) > 0) {
                    throw new IllegalArgumentException("Command selection does not match current cursor");
                }
                delta.set(originalCursor, command.templateUnits());
            }
            return new ProgressDelta(delta, AEAmount.of(executionWindow));
        }

        private ProgressState apply(AEAmount remainingExecutions, List<AEAmount> selectionRemaining) {
            if (selectionUnits.size() != selectionRemaining.size() || remainingExecutions.compareTo(executions) < 0) {
                throw new IllegalStateException("Progress delta is not valid for this work order");
            }
            ArrayList<AEAmount> next = new ArrayList<>(selectionUnits.size());
            for (int index = 0; index < selectionUnits.size(); index++) {
                AEAmount used = selectionUnits.get(index);
                AEAmount available = selectionRemaining.get(index);
                if (available.compareTo(used) < 0) {
                    throw new IllegalStateException("Progress would underflow a sealed selection cursor");
                }
                next.add(available.subtractExact(used));
            }
            AEAmount nextExecutions = remainingExecutions.subtractExact(executions);
            if (nextExecutions.equals(AEAmount.ZERO)) {
                for (AEAmount amount : next) {
                    if (!amount.equals(AEAmount.ZERO)) {
                        throw new IllegalStateException("Completed normal batch retained an input cursor");
                    }
                }
            }
            return new ProgressState(nextExecutions, List.copyOf(next));
        }
    }

    private record ProgressState(AEAmount remainingExecutions, List<AEAmount> selectionRemaining) {
        private ProgressState {
            Objects.requireNonNull(remainingExecutions, "remainingExecutions");
            selectionRemaining = List.copyOf(Objects.requireNonNull(selectionRemaining, "selectionRemaining"));
        }
    }

    private record CycleProgress(CycleExecutionManifest cycle, ProgressState memberProgress) {
        private CycleProgress {
            Objects.requireNonNull(cycle, "cycle");
            Objects.requireNonNull(memberProgress, "memberProgress");
        }
    }

    /** Fully staged bounded work-order position. Assignment happens only after all completion validation succeeds. */
    private record WorkProgress(int causalStepIndex, AEAmount remainingExecutions,
            List<AEAmount> selectionRemaining, AEAmount cycleRemainingRepetitions, int cycleMemberIndex,
            AEAmount cycleMemberRemainingExecutions, List<AEAmount> cycleSelectionRemaining) {
        private WorkProgress {
            if (causalStepIndex < 0 || cycleMemberIndex < -1
                    || cycleMemberIndex >= PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS) {
                throw new IllegalArgumentException("Invalid staged causal progress index");
            }
            remainingExecutions = Objects.requireNonNull(remainingExecutions, "remainingExecutions");
            selectionRemaining = List.copyOf(Objects.requireNonNull(selectionRemaining, "selectionRemaining"));
            cycleRemainingRepetitions = Objects.requireNonNull(cycleRemainingRepetitions,
                    "cycleRemainingRepetitions");
            cycleMemberRemainingExecutions = Objects.requireNonNull(cycleMemberRemainingExecutions,
                    "cycleMemberRemainingExecutions");
            cycleSelectionRemaining = List.copyOf(
                    Objects.requireNonNull(cycleSelectionRemaining, "cycleSelectionRemaining"));
            if (remainingExecutions.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS
                    || cycleRemainingRepetitions.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS
                    || cycleMemberRemainingExecutions.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS
                    || selectionRemaining.size() > PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS
                    || cycleSelectionRemaining.size() > PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS) {
                throw new IllegalArgumentException("Staged progress exceeds exact bounded limits");
            }
            for (AEAmount amount : selectionRemaining) {
                if (Objects.requireNonNull(amount, "selectionRemaining entry").toBigInteger()
                        .bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
                    throw new IllegalArgumentException("Staged normal selection exceeds exact bounded limits");
                }
            }
            for (AEAmount amount : cycleSelectionRemaining) {
                if (Objects.requireNonNull(amount, "cycleSelectionRemaining entry").toBigInteger()
                        .bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
                    throw new IllegalArgumentException("Staged cycle selection exceeds exact bounded limits");
                }
            }
            if (cycleMemberIndex < 0 && (!cycleRemainingRepetitions.equals(AEAmount.ZERO)
                    || !cycleMemberRemainingExecutions.equals(AEAmount.ZERO) || !cycleSelectionRemaining.isEmpty())) {
                throw new IllegalArgumentException("Inactive staged cycle cannot retain progress");
            }
            if (cycleMemberIndex >= 0 && (!remainingExecutions.equals(AEAmount.ZERO)
                    || !selectionRemaining.isEmpty() || cycleRemainingRepetitions.equals(AEAmount.ZERO)
                    || cycleMemberRemainingExecutions.equals(AEAmount.ZERO))) {
                throw new IllegalArgumentException("Active staged cycle has ambiguous normal progress");
            }
        }
    }

    private record IssuedCommand(ExactWorkCommand command, ProgressDelta progress) {
        private IssuedCommand {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(progress, "progress");
        }
    }
}

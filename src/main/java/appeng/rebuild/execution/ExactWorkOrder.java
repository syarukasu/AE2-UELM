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
            ExecutionManifest manifest = manifests.get(causalStepIndex);
            if (!(manifest instanceof NormalExecutionManifest normal)) {
                return new ExactWorkOrderCommandResult.CycleUnsupported(snapshot());
            }
            if (generationExhausted) {
                state = ExactWorkOrderState.FAIL_CLOSED;
                return unavailable(ExactWorkOrderCommandResult.Reason.COMMAND_GENERATION_EXHAUSTED);
            }
            if (remainingExecutions.equals(AEAmount.ZERO) || selectionRemaining.size() != normal.execution()
                    .plannedSelections().size()) {
                return failIssueInvariant();
            }

            long maximumWindow = maximumWindow(requestedWindow, normal.execution());
            if (maximumWindow <= 0) {
                return unavailable(ExactWorkOrderCommandResult.Reason.PHYSICAL_WINDOW_UNAVAILABLE);
            }
            ExactWorkCommand command = largestPhysicalCommand(normal, maximumWindow);
            if (command == null) {
                return unavailable(ExactWorkOrderCommandResult.Reason.PHYSICAL_WINDOW_UNAVAILABLE);
            }
            ProgressDelta progress = ProgressDelta.from(normal.execution().plannedSelections(), selectionRemaining,
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
            final ProgressState nextProgress;
            final int nextCausalStep;
            try {
                ProgressState calculated = completed.progress().apply(remainingExecutions, selectionRemaining);
                int calculatedStep = calculated.remainingExecutions().equals(AEAmount.ZERO) ? causalStepIndex + 1
                        : causalStepIndex;
                nextProgress = calculatedStep == causalStepIndex ? calculated : progressAt(calculatedStep);
                nextCausalStep = calculatedStep;
            } catch (RuntimeException invalidProgress) {
                if (observed != null) {
                    return closeWithUnmergedDiscrepancy(observed);
                }
                return closeWithCompletedEvidence(exactCompletedEvidence(completed, completion), false);
            }
            final TreeMap<KeyId, AEAmount> credited;
            try {
                credited = creditCopy(custody, completion.actualOutputs(), completion.actualRemainders());
            } catch (RuntimeException boundedCustodyFailure) {
                if (observed != null) {
                    return closeWithUnmergedDiscrepancy(observed);
                }
                return closeWithCompletedEvidence(exactCompletedEvidence(completed, completion), true, nextProgress,
                        nextCausalStep);
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
                ExactWorkOrderState nextState = ExactWorkOrderState.READY;
                if (nextCausalStep >= manifests.size()) {
                    if (!credited.equals(expectedTerminalCustody())) {
                        return closeWithCompletedEvidence(credited, evidence, nextProgress, nextCausalStep);
                    }
                    nextState = ExactWorkOrderState.SETTLEMENT_PENDING;
                }
                custody = credited;
                inFlight = null;
                remainingExecutions = nextProgress.remainingExecutions();
                selectionRemaining = nextProgress.selectionRemaining();
                causalStepIndex = nextCausalStep;
                state = nextState;
                return new ExactWorkOrderTransitionResult.Completed(snapshot());
            } catch (RuntimeException invariant) {
                return closeWithCompletedEvidence(credited, exactCompletedEvidence(completed, completion), nextProgress,
                        nextCausalStep);
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
                nextGeneration, generationExhausted,
                Optional.ofNullable(outstanding).map(IssuedCommand::command), Optional.ofNullable(inFlight)
                        .map(IssuedCommand::command),
                Optional.ofNullable(discrepancy), Optional.ofNullable(completedEvidence),
                completedEvidenceProgressApplied);
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

    /** A command completed physically, so retain its staged goods and exact completion evidence before closing. */
    private ExactWorkOrderTransitionResult.Failure closeWithCompletedEvidence(TreeMap<KeyId, AEAmount> credited,
            ExactCompletedCommandEvidence evidence, ProgressState progress, int nextCausalStep) {
        custody = credited;
        inFlight = null;
        completedEvidence = evidence;
        completedEvidenceProgressApplied = true;
        remainingExecutions = progress.remainingExecutions();
        selectionRemaining = progress.selectionRemaining();
        causalStepIndex = nextCausalStep;
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

    private ExactWorkOrderTransitionResult.Failure closeWithCompletedEvidence(ExactCompletedCommandEvidence evidence,
            boolean progressApplied, ProgressState progress, int nextCausalStep) {
        if (!progressApplied) {
            return closeWithCompletedEvidence(evidence, false);
        }
        inFlight = null;
        completedEvidence = evidence;
        completedEvidenceProgressApplied = true;
        remainingExecutions = progress.remainingExecutions();
        selectionRemaining = progress.selectionRemaining();
        causalStepIndex = nextCausalStep;
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
        ProgressState progress = progressAt(causalStepIndex);
        remainingExecutions = progress.remainingExecutions();
        selectionRemaining = progress.selectionRemaining();
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

    private long maximumWindow(long requestedWindow, SealedPatternExecution execution) {
        long maximum = Math.min(requestedWindow, remainingExecutions.min(LONG_MAX_AMOUNT).longValueExact());
        for (PlannedInputSelection selection : execution.plannedSelections()) {
            if (selection.initialRequiredAmount().compareTo(selection.grossConsumedAmount()) < 0) {
                return Math.min(maximum, 1L);
            }
        }
        return maximum;
    }

    /**
     * Finds the largest feasible physical window without assuming feasibility is globally monotonic. Candidate
     * exhaustion points partition the window into bounded pieces where every command amount and projected custody is
     * affine. Each piece is solved algebraically after a bounded physical-prefix binary search.
     */
    private ExactWorkCommand largestPhysicalCommand(NormalExecutionManifest normal, long maximumWindow) {
        TreeSet<Long> starts = structuralStarts(normal.execution(), maximumWindow);
        starts.add(1L);
        ExactWorkCommand best = null;
        long lower = 1L;
        for (Long next : starts.tailSet(2L, true)) {
            long upper = next - 1L;
            if (lower <= upper) {
                ExactWorkCommand candidate = largestInStructuralPiece(normal, lower, upper);
                if (candidate != null && (best == null || candidate.executionWindow() > best.executionWindow())) {
                    best = candidate;
                }
            }
            lower = next;
        }
        if (lower <= maximumWindow) {
            ExactWorkCommand candidate = largestInStructuralPiece(normal, lower, maximumWindow);
            if (candidate != null && (best == null || candidate.executionWindow() > best.executionWindow())) {
                best = candidate;
            }
        }
        return best;
    }

    private TreeSet<Long> structuralStarts(SealedPatternExecution execution, long maximumWindow) {
        TreeSet<Long> starts = new TreeSet<>();
        for (int inputIndex = 0; inputIndex < execution.pattern().inputs().size(); inputIndex++) {
            AEAmount cumulative = AEAmount.ZERO;
            AEAmount multiplier = execution.pattern().inputs().get(inputIndex).multiplier();
            for (int selectionIndex = 0; selectionIndex < execution.plannedSelections().size(); selectionIndex++) {
                PlannedInputSelection selection = execution.plannedSelections().get(selectionIndex);
                if (selection.inputIndex() != inputIndex) {
                    continue;
                }
                cumulative = cumulative.add(selectionRemaining.get(selectionIndex));
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

    private ExactWorkCommand largestInStructuralPiece(NormalExecutionManifest normal, long lower, long upper) {
        ExactWorkCommand first = buildPhysicalCommand(normal, lower);
        if (first == null) {
            return null;
        }
        long physicalUpper = upper;
        if (buildPhysicalCommand(normal, upper) == null) {
            long low = lower;
            long high = upper - 1L;
            while (low <= high) {
                long middle = low + ((high - low) >>> 1);
                if (buildPhysicalCommand(normal, middle) == null) {
                    high = middle - 1L;
                } else {
                    physicalUpper = middle;
                    low = middle + 1L;
                }
            }
        }
        ExactWorkCommand last = buildPhysicalCommand(normal, physicalUpper);
        long feasible = largestProjectedWindow(first, last, lower, physicalUpper);
        return feasible < lower ? null : tryBuildCommand(normal, feasible);
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
    private ExactWorkCommand tryBuildCommand(NormalExecutionManifest normal, long window) {
        ExactWorkCommand command = buildPhysicalCommand(normal, window);
        return command != null && projectedCustody(command) != null ? command : null;
    }

    private ExactWorkCommand buildPhysicalCommand(NormalExecutionManifest normal, long window) {
        try {
            SealedPatternExecution execution = normal.execution();
            CompiledPattern pattern = execution.pattern();
            List<PlannedInputSelection> selections = selectionsForWindow(pattern, execution.plannedSelections(),
                    window);
            Map<KeyId, AEAmount> inputs = aggregateInputs(selections);
            List<ExactWorkOutput> outputSlots = outputsForWindow(pattern, window);
            Map<KeyId, AEAmount> outputs = aggregateOutputs(outputSlots);
            Map<KeyId, AEAmount> remainders = aggregateRemainders(selections);
            ExactWorkCommand command = new ExactWorkCommand(
                    new WorkCommandId(lease.planId(), lease.leaseIdentity(), workOrderId,
                            nextGeneration),
                    lease.handle(), lease.reservationId(), normal.batchId(), pattern, window,
                    selections,
                    inputs, outputSlots, outputs, remainders);
            return command;
        } catch (IllegalArgumentException | ArithmeticException invalidPhysicalWindow) {
            return null;
        }
    }

    private List<PlannedInputSelection> selectionsForWindow(CompiledPattern pattern,
            List<PlannedInputSelection> originals, long window) {
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
                AEAmount available = selectionRemaining.get(cursor);
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

    private record IssuedCommand(ExactWorkCommand command, ProgressDelta progress) {
        private IssuedCommand {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(progress, "progress");
        }
    }
}

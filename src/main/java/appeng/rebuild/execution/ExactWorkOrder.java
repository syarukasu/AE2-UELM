package appeng.rebuild.execution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

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

    private final WorkOrderId workOrderId;
    private final ReservedPlanLease lease;
    private final BrokerExactStorage storage;
    private final ServerThreadGate serverThread;
    private final IActionSource actionSource;
    private final ExactCpuLedger ledger;
    private final List<ExecutionManifest> manifests;

    /** Only non-zero exact quantities are retained. It is never exposed without a defensive immutable copy. */
    private final TreeMap<KeyId, AEAmount> custody = new TreeMap<>(KEY_ORDER);
    private ExactWorkOrderState state = ExactWorkOrderState.READY;
    private int causalStepIndex;
    private AEAmount remainingExecutions = AEAmount.ZERO;
    private List<AEAmount> selectionRemaining = List.of();
    private IssuedCommand outstanding;
    private IssuedCommand inFlight;
    private ExactWorkDiscrepancy discrepancy;
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
            if (!canSubtract(custody, outstanding.command().custodyInputs())) {
                return failTransitionInvariant();
            }
            subtract(custody, outstanding.command().custodyInputs());
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
            add(custody, completion.actualOutputs());
            add(custody, completion.actualRemainders());
            inFlight = null;
            if (!completed.command().expectedOutputs().equals(completion.actualOutputs())
                    || !completed.command().expectedRemainders().equals(completion.actualRemainders())) {
                discrepancy = new ExactWorkDiscrepancy(completed.command(), completed.command().expectedOutputs(),
                        completion.actualOutputs(), completed.command().expectedRemainders(),
                        completion.actualRemainders());
                state = ExactWorkOrderState.FAIL_CLOSED;
                return transitionFailure(ExactWorkOrderTransitionResult.Reason.RESULT_MISMATCH);
            }
            completed.progress().commit(this);
            if (remainingExecutions.equals(AEAmount.ZERO)) {
                causalStepIndex++;
                loadCurrentNormalProgress();
            }
            if (causalStepIndex >= manifests.size()) {
                if (!custody.equals(expectedTerminalCustody())) {
                    return failTransitionInvariant();
                }
                state = ExactWorkOrderState.SETTLEMENT_PENDING;
            } else {
                state = ExactWorkOrderState.READY;
            }
            return new ExactWorkOrderTransitionResult.Completed(snapshot());
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
                Optional.ofNullable(outstanding).map(IssuedCommand::command), Optional.ofNullable(inFlight)
                        .map(IssuedCommand::command),
                Optional.ofNullable(discrepancy));
    }

    private ExactWorkOrderCommandResult.Unavailable beginIssue() {
        if (entered) {
            return unavailable(ExactWorkOrderCommandResult.Reason.INVARIANT_VIOLATION);
        }
        entered = true;
        if (!onServerThread()) {
            ExactWorkOrderCommandResult.Unavailable failure = unavailable(
                    ExactWorkOrderCommandResult.Reason.WRONG_STATE);
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

    private void loadCurrentNormalProgress() {
        if (causalStepIndex >= manifests.size()) {
            remainingExecutions = AEAmount.ZERO;
            selectionRemaining = List.of();
            return;
        }
        ExecutionManifest manifest = manifests.get(causalStepIndex);
        if (manifest instanceof NormalExecutionManifest normal) {
            remainingExecutions = normal.execution().executions();
            ArrayList<AEAmount> remaining = new ArrayList<>(normal.execution().plannedSelections().size());
            for (PlannedInputSelection selection : normal.execution().plannedSelections()) {
                remaining.add(selection.templateUnits());
            }
            selectionRemaining = List.copyOf(remaining);
        } else {
            remainingExecutions = AEAmount.ZERO;
            selectionRemaining = List.of();
        }
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

    /** Bounded monotonic feasibility search over at most the 63 bits of a signed physical window. */
    private ExactWorkCommand largestPhysicalCommand(NormalExecutionManifest normal, long maximumWindow) {
        ExactWorkCommand best = tryBuildCommand(normal, maximumWindow);
        if (best != null) {
            return best;
        }
        long low = 1L;
        long high = maximumWindow - 1L;
        while (low <= high) {
            long middle = low + ((high - low) >>> 1);
            ExactWorkCommand candidate = tryBuildCommand(normal, middle);
            if (candidate != null) {
                best = candidate;
                low = middle + 1L;
            } else {
                high = middle - 1L;
            }
        }
        return best;
    }

    /** Pure command construction: it neither advances cursors nor changes custody. */
    private ExactWorkCommand tryBuildCommand(NormalExecutionManifest normal, long window) {
        try {
            SealedPatternExecution execution = normal.execution();
            CompiledPattern pattern = execution.pattern();
            List<PlannedInputSelection> selections = selectionsForWindow(pattern, execution.plannedSelections(),
                    window);
            Map<KeyId, AEAmount> inputs = aggregateInputs(selections);
            List<ExactWorkOutput> outputSlots = outputsForWindow(pattern, window);
            Map<KeyId, AEAmount> outputs = aggregateOutputs(outputSlots);
            Map<KeyId, AEAmount> remainders = aggregateRemainders(selections);
            return new ExactWorkCommand(new WorkCommandId(lease.planId(), lease.leaseIdentity(), workOrderId,
                    nextGeneration), lease.handle(), lease.reservationId(), normal.batchId(), pattern, window,
                    selections,
                    inputs, outputSlots, outputs, remainders);
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

    private static boolean canSubtract(Map<KeyId, AEAmount> source, Map<KeyId, AEAmount> debits) {
        for (Map.Entry<KeyId, AEAmount> debit : debits.entrySet()) {
            if (source.getOrDefault(debit.getKey(), AEAmount.ZERO).compareTo(debit.getValue()) < 0) {
                return false;
            }
        }
        return true;
    }

    private static void subtract(TreeMap<KeyId, AEAmount> source, Map<KeyId, AEAmount> debits) {
        for (Map.Entry<KeyId, AEAmount> debit : debits.entrySet()) {
            AEAmount remaining = source.get(debit.getKey()).subtractExact(debit.getValue());
            if (remaining.equals(AEAmount.ZERO)) {
                source.remove(debit.getKey());
            } else {
                source.put(debit.getKey(), remaining);
            }
        }
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
        target.merge(key, amount, AEAmount::add);
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

        private void commit(ExactWorkOrder order) {
            if (selectionUnits.size() != order.selectionRemaining.size()
                    || order.remainingExecutions.compareTo(executions) < 0) {
                throw new IllegalStateException("Progress delta is not valid for this work order");
            }
            ArrayList<AEAmount> next = new ArrayList<>(selectionUnits.size());
            for (int index = 0; index < selectionUnits.size(); index++) {
                AEAmount used = selectionUnits.get(index);
                AEAmount available = order.selectionRemaining.get(index);
                if (available.compareTo(used) < 0) {
                    throw new IllegalStateException("Progress would underflow a sealed selection cursor");
                }
                next.add(available.subtractExact(used));
            }
            order.remainingExecutions = order.remainingExecutions.subtractExact(executions);
            if (order.remainingExecutions.equals(AEAmount.ZERO)) {
                for (AEAmount amount : next) {
                    if (!amount.equals(AEAmount.ZERO)) {
                        throw new IllegalStateException("Completed normal batch retained an input cursor");
                    }
                }
            }
            order.selectionRemaining = List.copyOf(next);
        }
    }

    private record IssuedCommand(ExactWorkCommand command, ProgressDelta progress) {
        private IssuedCommand {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(progress, "progress");
        }
    }
}

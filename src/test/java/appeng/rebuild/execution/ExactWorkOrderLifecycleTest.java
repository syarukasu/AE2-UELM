package appeng.rebuild.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.CompiledCandidateSpec;
import appeng.rebuild.pattern.CompiledInputSpec;
import appeng.rebuild.pattern.CompiledOutputSpec;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.CompiledPatternGraph;
import appeng.rebuild.pattern.CompiledPatternGraphBuilder;
import appeng.rebuild.pattern.GraphBuildResult;
import appeng.rebuild.pattern.GraphGeneration;
import appeng.rebuild.pattern.NormalizedPatternSnapshot;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternKind;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.pattern.RecipeRevision;
import appeng.rebuild.pattern.SubstitutionPolicy;
import appeng.rebuild.planner.ExactCraftPlanDraft;
import appeng.rebuild.planner.ExactCraftPlanResult;
import appeng.rebuild.planner.ExactCraftPlanner;
import appeng.rebuild.planner.ExactCraftRequest;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.BrokerExactStorage;
import appeng.rebuild.storage.ExactStorageVisitor;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/**
 * T007B3 lifecycle tests. They exercise only the package-bound work-order release protocol against a detached exact
 * storage fixture; no Minecraft bootstrap or live world is involved.
 */
class ExactWorkOrderLifecycleTest {
    private static final long KEY_GENERATION = 101L;
    private static final long SERVER_GENERATION = 103L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(107L);
    private static final RecipeRevision RECIPE_REVISION = new RecipeRevision(109L);
    private static final IActionSource SOURCE = IActionSource.empty();
    private static final ExactCraftPlanner PLANNER = new ExactCraftPlanner();
    private static final ExactCraftingPlanValidator VALIDATOR = new ExactCraftingPlanValidator();
    private static final Comparator<KeyId> KEY_ORDER = Comparator.comparingInt(KeyId::value);

    @Test
    void settlementReleasesExactTerminalCustodyBeforeAcknowledgingCpu() {
        Map<Integer, AEAmount> debits = Map.of(0, AEAmount.ONE);
        Harness harness = normal("lifecycle-settlement", debits);
        ExactWorkOrder order = harness.order();
        ExactWorkCommand command = issued(order);
        completeExactly(order, command);

        ExactWorkOrderSnapshot pending = order.snapshot();
        KeyId output = outputKey(debits);
        assertEquals(ExactWorkOrderState.SETTLEMENT_PENDING, pending.state());
        assertTrue(pending.releaseMode().isEmpty());
        assertEquals(Map.of(output, AEAmount.ONE), pending.custody());

        AtomicReference<ExactWorkOrderState> stateBeforeAck = new AtomicReference<>();
        AtomicReference<ExactCpuLedgerState> ledgerBeforeAck = new AtomicReference<>();
        harness.storage().insertHook = () -> {
            stateBeforeAck.set(order.snapshot().state());
            ledgerBeforeAck.set(harness.ledger().snapshot().state());
        };
        ExactWorkOrderLifecycleResult.Settled settled = assertInstanceOf(
                ExactWorkOrderLifecycleResult.Settled.class, order.progressRelease(1));

        assertEquals(ExactWorkOrderState.RELEASE_PENDING, stateBeforeAck.get(),
                "CPU acknowledgement must occur after the physical insert");
        assertEquals(ExactCpuLedgerState.HANDED_OFF, ledgerBeforeAck.get(),
                "the ledger must retain handoff ownership until custody is empty");
        assertEquals(ExactWorkOrderState.COMPLETED, settled.snapshot().state());
        assertEquals(Optional.of(ExactWorkOrderReleaseMode.SETTLEMENT), settled.snapshot().releaseMode());
        assertTrue(settled.snapshot().custody().isEmpty());
        assertEquals(settled.snapshot(), order.snapshot());
        assertEquals(ExactCpuLedgerState.IDLE, harness.ledger().snapshot().state());
        assertBrokerIdle(harness.broker());
        assertEquals(AEAmount.ONE, harness.storage().amount(output));
    }

    @Test
    void finalBrokerPermitRejectsReentrantOperationWithoutChangingTerminalState() {
        Harness harness = normal("lifecycle-broker-reentry", Map.of(0, AEAmount.ONE));
        ExactWorkOrder order = harness.order();
        ExactWorkCommand command = issued(order);
        completeExactly(order, command);

        AtomicReference<ExactTransferBrokerResult> nested = new AtomicReference<>();
        // Install the gate hook only after the physical insert has happened, so it fires from the broker's final
        // identity permit rather than from the outer work-order lifecycle admission.
        harness.storage().insertHook = () -> harness.gate().hook = () -> nested
                .set(harness.broker().progressRelease(1));

        assertInstanceOf(ExactWorkOrderLifecycleResult.Settled.class, order.progressRelease(1));
        ExactTransferBrokerResult.Failure reentrant = assertInstanceOf(ExactTransferBrokerResult.Failure.class,
                nested.get());
        assertEquals(ExactTransferBrokerResult.FailureReason.REENTRANT_OPERATION, reentrant.reason());
        assertEquals(ExactTransferBrokerState.LEASED, reentrant.snapshot().state());
        assertEquals(ExactWorkOrderState.COMPLETED, order.snapshot().state());
        assertEquals(ExactCpuLedgerState.IDLE, harness.ledger().snapshot().state());
        assertBrokerIdle(harness.broker());
    }

    @Test
    void partialInsertPersistsCustodyAndRetriesWithinBoundedBudget() {
        Map<Integer, AEAmount> debits = Map.of(0, AEAmount.ONE, 1, AEAmount.ONE);
        Harness harness = normal("lifecycle-partial", debits);
        ExactWorkOrder order = harness.order();
        assertInstanceOf(ExactWorkOrderLifecycleResult.ReleasePending.class, order.requestCancellation());
        harness.storage().enqueueReturn(AEAmount.ONE);

        ExactWorkOrderLifecycleResult.ReleasePending partial = assertInstanceOf(
                ExactWorkOrderLifecycleResult.ReleasePending.class, order.progressRelease(1));
        assertEquals(ExactWorkOrderState.RELEASE_PENDING, partial.snapshot().state());
        assertEquals(Optional.of(ExactWorkOrderReleaseMode.CANCELLATION), partial.snapshot().releaseMode());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE), partial.snapshot().custody());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE), order.snapshot().custody());
        assertEquals(1, harness.storage().modulateInsertCalls);
        assertEquals(ExactCpuLedgerState.HANDED_OFF, harness.ledger().snapshot().state());

        ExactWorkOrderLifecycleResult.Cancelled cancelled = assertInstanceOf(
                ExactWorkOrderLifecycleResult.Cancelled.class, order.progressRelease(1));
        assertEquals(ExactWorkOrderState.COMPLETED, cancelled.snapshot().state());
        assertTrue(cancelled.snapshot().custody().isEmpty());
        assertEquals(Optional.of(ExactWorkOrderReleaseMode.CANCELLATION), cancelled.snapshot().releaseMode());
        assertEquals(2, harness.storage().modulateInsertCalls);
        assertEquals(ExactCpuLedgerState.IDLE, harness.ledger().snapshot().state());
        assertBrokerIdle(harness.broker());
        assertEquals(AEAmount.ONE, harness.storage().amount(new KeyId(0)));
        assertEquals(AEAmount.ONE, harness.storage().amount(new KeyId(1)));

        // A completed work order must not leave the broker in a stale LEASED observation. Reusing the same broker for
        // a fresh prepare/reserve cycle proves that the old handoff identities no longer block admission.
        ExactCpuLedger followupLedger = new ExactCpuLedger();
        CpuPlanHandle followupHandle = assertInstanceOf(ExactCpuLedgerResult.Prepared.class,
                followupLedger.prepare(harness.plan())).handle();
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class,
                harness.broker().reserve(followupLedger, followupHandle));
        assertEquals(ExactTransferBrokerState.RESERVED, harness.broker().snapshot().state());
        assertInstanceOf(ExactTransferBrokerResult.Cancelled.class,
                harness.broker().cancelReservation(followupLedger, followupHandle));
        assertBrokerIdle(harness.broker());
        assertEquals(ExactCpuLedgerState.IDLE, followupLedger.snapshot().state());
    }

    @Test
    void storageRuntimeExceptionLeavesCustodyUntouchedAndCanRetry() {
        Map<Integer, AEAmount> debits = Map.of(0, AEAmount.ONE);
        Harness harness = normal("lifecycle-runtime", debits);
        ExactWorkOrder order = harness.order();
        assertInstanceOf(ExactWorkOrderLifecycleResult.ReleasePending.class, order.requestCancellation());
        ExactWorkOrderSnapshot before = order.snapshot();
        harness.storage().enqueueThrow();

        ExactWorkOrderLifecycleResult.ReleasePending retry = assertInstanceOf(
                ExactWorkOrderLifecycleResult.ReleasePending.class, order.progressRelease(1));
        assertEquals(before.custody(), retry.snapshot().custody());
        assertEquals(before.custody(), order.snapshot().custody());
        assertEquals(AEAmount.ZERO, harness.storage().amount(new KeyId(0)));
        assertEquals(ExactCpuLedgerState.HANDED_OFF, harness.ledger().snapshot().state());

        assertInstanceOf(ExactWorkOrderLifecycleResult.Cancelled.class, order.progressRelease(1));
        assertEquals(AEAmount.ONE, harness.storage().amount(new KeyId(0)));
        assertEquals(ExactCpuLedgerState.IDLE, harness.ledger().snapshot().state());
        assertBrokerIdle(harness.broker());
    }

    @Test
    void nullAndOverReturnFailClosedWithoutDroppingCustody() {
        for (InsertScript script : List.of(InsertScript.NULL, InsertScript.OVER_RETURN)) {
            Map<Integer, AEAmount> debits = Map.of(0, AEAmount.ONE);
            Harness harness = normal("lifecycle-invalid-" + script, debits);
            ExactWorkOrder order = harness.order();
            assertInstanceOf(ExactWorkOrderLifecycleResult.ReleasePending.class, order.requestCancellation());
            ExactWorkOrderSnapshot before = order.snapshot();
            if (script == InsertScript.NULL) {
                harness.storage().enqueueNull();
            } else {
                harness.storage().enqueueOverReturn();
            }

            ExactWorkOrderLifecycleResult.Failure failure = assertInstanceOf(
                    ExactWorkOrderLifecycleResult.Failure.class, order.progressRelease(1));
            assertEquals(ExactWorkOrderLifecycleResult.Reason.STORAGE_PROTOCOL_VIOLATION, failure.reason());
            assertEquals(ExactWorkOrderState.FAIL_CLOSED, failure.snapshot().state());
            assertEquals(before.custody(), failure.snapshot().custody());
            assertEquals(before.custody(), order.snapshot().custody());
            assertEquals(AEAmount.ZERO, harness.storage().amount(new KeyId(0)));
            assertEquals(ExactCpuLedgerState.HANDED_OFF, harness.ledger().snapshot().state());
        }
    }

    @Test
    void readyCancellationClearsUnacceptedCommandAndSettlesAsCancelled() {
        Map<Integer, AEAmount> debits = Map.of(0, AEAmount.ONE);
        Harness harness = normal("lifecycle-ready-cancel", debits);
        ExactWorkOrder order = harness.order();
        ExactWorkCommand command = issued(order);
        assertTrue(order.snapshot().outstandingCommand().isPresent());

        ExactWorkOrderLifecycleResult.ReleasePending pending = assertInstanceOf(
                ExactWorkOrderLifecycleResult.ReleasePending.class, order.requestCancellation());
        assertEquals(ExactWorkOrderState.RELEASE_PENDING, pending.snapshot().state());
        assertEquals(Optional.of(ExactWorkOrderReleaseMode.CANCELLATION), pending.snapshot().releaseMode());
        assertTrue(pending.snapshot().outstandingCommand().isEmpty());
        assertTrue(order.snapshot().outstandingCommand().isEmpty());

        ExactWorkOrderSnapshot afterCancel = order.snapshot();
        ExactWorkOrderTransitionResult.Failure staleAccept = assertInstanceOf(
                ExactWorkOrderTransitionResult.Failure.class, order.accept(new WorkCommandAcceptance(command)));
        assertEquals(ExactWorkOrderTransitionResult.Reason.WRONG_STATE, staleAccept.reason());
        assertEquals(afterCancel, order.snapshot());

        ExactWorkOrderLifecycleResult.Cancelled cancelled = assertInstanceOf(
                ExactWorkOrderLifecycleResult.Cancelled.class, order.progressRelease(1));
        assertEquals(ExactWorkOrderState.COMPLETED, cancelled.snapshot().state());
        assertEquals(Optional.of(ExactWorkOrderReleaseMode.CANCELLATION), cancelled.snapshot().releaseMode());
        assertEquals(AEAmount.ONE, harness.storage().amount(new KeyId(0)));
        assertEquals(ExactCpuLedgerState.IDLE, harness.ledger().snapshot().state());
    }

    @Test
    void inFlightCancellationWaitsAndMatchingCompletionCreditsAndAppliesPlanProgress() {
        Map<Integer, AEAmount> debits = Map.of(0, AEAmount.ONE);
        Harness harness = normal("lifecycle-inflight-cancel", debits);
        ExactWorkOrder order = harness.order();
        ExactWorkCommand command = issued(order);
        ExactWorkOrderTransitionResult.Accepted accepted = assertInstanceOf(
                ExactWorkOrderTransitionResult.Accepted.class, order.accept(new WorkCommandAcceptance(command)));
        ExactWorkOrderLifecycleResult.CancellationWaiting waiting = assertInstanceOf(
                ExactWorkOrderLifecycleResult.CancellationWaiting.class, order.requestCancellation());
        assertEquals(ExactWorkOrderState.CANCEL_PENDING, waiting.snapshot().state());
        assertTrue(waiting.snapshot().inFlightCommand().isPresent());
        assertTrue(waiting.snapshot().releaseMode().isEmpty());

        ExactWorkOrderTransitionResult.Completed completed = assertInstanceOf(
                ExactWorkOrderTransitionResult.Completed.class,
                order.complete(new WorkCommandCompletion(command, command.expectedOutputs(),
                        command.expectedRemainders())));
        assertEquals(ExactWorkOrderState.RELEASE_PENDING, completed.snapshot().state());
        assertEquals(Optional.of(ExactWorkOrderReleaseMode.CANCELLATION), completed.snapshot().releaseMode());
        assertTrue(completed.snapshot().inFlightCommand().isEmpty());
        assertEquals(harness.plan().executionManifests().size(), completed.snapshot().causalStepIndex());
        assertEquals(AEAmount.ZERO, completed.snapshot().remainingExecutions());
        assertTrue(completed.snapshot().selectionRemaining().isEmpty());
        assertEquals(Map.of(outputKey(debits), AEAmount.ONE), completed.snapshot().custody());

        assertInstanceOf(ExactWorkOrderLifecycleResult.Cancelled.class, order.progressRelease(1));
        assertEquals(ExactCpuLedgerState.IDLE, harness.ledger().snapshot().state());
        assertBrokerIdle(harness.broker());
        assertEquals(AEAmount.ONE, harness.storage().amount(outputKey(debits)));
    }

    @Test
    void staleMismatchedAndDuplicateCallbacksNeverMutateLifecycle() {
        Map<Integer, AEAmount> debits = Map.of(0, AEAmount.ONE);
        Harness harness = normal("lifecycle-callbacks", debits);
        ExactWorkOrder order = harness.order();
        ExactWorkCommand command = issued(order);
        ExactWorkCommand stale = foreign(command);
        ExactWorkOrderSnapshot beforeAccept = order.snapshot();

        ExactWorkOrderTransitionResult.Failure wrongAccept = assertInstanceOf(
                ExactWorkOrderTransitionResult.Failure.class, order.accept(new WorkCommandAcceptance(stale)));
        assertEquals(ExactWorkOrderTransitionResult.Reason.IDENTITY_MISMATCH, wrongAccept.reason());
        assertEquals(beforeAccept, order.snapshot());
        order.accept(new WorkCommandAcceptance(command));
        ExactWorkOrderSnapshot accepted = order.snapshot();

        ExactWorkOrderTransitionResult.Failure wrongCompletion = assertInstanceOf(
                ExactWorkOrderTransitionResult.Failure.class,
                order.complete(new WorkCommandCompletion(stale, stale.expectedOutputs(), stale.expectedRemainders())));
        assertEquals(ExactWorkOrderTransitionResult.Reason.IDENTITY_MISMATCH, wrongCompletion.reason());
        assertEquals(accepted, order.snapshot());

        assertInstanceOf(ExactWorkOrderLifecycleResult.CancellationWaiting.class, order.requestCancellation());
        assertInstanceOf(ExactWorkOrderTransitionResult.Completed.class,
                order.complete(new WorkCommandCompletion(command, command.expectedOutputs(),
                        command.expectedRemainders())));
        ExactWorkOrderSnapshot released = order.snapshot();
        ExactWorkOrderTransitionResult.Failure duplicateCompletion = assertInstanceOf(
                ExactWorkOrderTransitionResult.Failure.class,
                order.complete(new WorkCommandCompletion(command, command.expectedOutputs(),
                        command.expectedRemainders())));
        assertEquals(ExactWorkOrderTransitionResult.Reason.WRONG_STATE, duplicateCompletion.reason());
        assertEquals(released, order.snapshot());

        assertInstanceOf(ExactWorkOrderLifecycleResult.Cancelled.class, order.progressRelease(1));
        ExactWorkOrderSnapshot completed = order.snapshot();
        ExactWorkOrderLifecycleResult.Failure duplicateRelease = assertInstanceOf(
                ExactWorkOrderLifecycleResult.Failure.class, order.progressRelease(1));
        assertEquals(ExactWorkOrderLifecycleResult.Reason.WRONG_STATE, duplicateRelease.reason());
        ExactWorkOrderLifecycleResult.Failure duplicateCancel = assertInstanceOf(
                ExactWorkOrderLifecycleResult.Failure.class, order.requestCancellation());
        assertEquals(ExactWorkOrderLifecycleResult.Reason.WRONG_STATE, duplicateCancel.reason());
        assertEquals(completed, order.snapshot());
        assertBrokerIdle(harness.broker());
    }

    @Test
    void wrongThreadAndReentrantLifecycleCallbacksAreTypedAndMutationSafe() {
        Map<Integer, AEAmount> debits = Map.of(0, AEAmount.ONE);
        Harness wrongThread = normal("lifecycle-thread", debits);
        ExactWorkOrderSnapshot before = wrongThread.order().snapshot();
        wrongThread.gate().allowed = false;
        ExactWorkOrderLifecycleResult.Failure wrong = assertInstanceOf(
                ExactWorkOrderLifecycleResult.Failure.class, wrongThread.order().requestCancellation());
        assertEquals(ExactWorkOrderLifecycleResult.Reason.WRONG_THREAD, wrong.reason());
        assertEquals(before, wrong.snapshot());
        assertEquals(before, wrongThread.order().snapshot());

        Harness reentrant = normal("lifecycle-reentrant", debits);
        AtomicReference<ExactWorkOrderLifecycleResult> nested = new AtomicReference<>();
        reentrant.gate().hook = () -> nested.set(reentrant.order().requestCancellation());
        ExactWorkOrderLifecycleResult.ReleasePending outer = assertInstanceOf(
                ExactWorkOrderLifecycleResult.ReleasePending.class, reentrant.order().requestCancellation());
        assertEquals(ExactWorkOrderLifecycleResult.Reason.REENTRANT,
                assertInstanceOf(ExactWorkOrderLifecycleResult.Failure.class, nested.get()).reason());
        assertEquals(ExactWorkOrderState.RELEASE_PENDING, outer.snapshot().state());
        assertEquals(Optional.of(ExactWorkOrderReleaseMode.CANCELLATION), reentrant.order().snapshot().releaseMode());
    }

    @Test
    void operationBudgetRejectsInvalidValuesWithoutMutationAndBoundsRetries() {
        Map<Integer, AEAmount> debits = Map.of(0, AEAmount.ONE, 1, AEAmount.ONE);
        Harness harness = normal("lifecycle-budget", debits);
        ExactWorkOrder order = harness.order();
        assertInstanceOf(ExactWorkOrderLifecycleResult.ReleasePending.class, order.requestCancellation());
        ExactWorkOrderSnapshot before = order.snapshot();

        for (int budget : List.of(0, -1, PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS + 1)) {
            ExactWorkOrderLifecycleResult.Failure failure = assertInstanceOf(
                    ExactWorkOrderLifecycleResult.Failure.class, order.progressRelease(budget));
            assertEquals(ExactWorkOrderLifecycleResult.Reason.INVALID_OPERATION_BUDGET, failure.reason());
            assertEquals(before, order.snapshot());
        }
        assertEquals(0, harness.storage().modulateInsertCalls);
        assertEquals(ExactCpuLedgerState.HANDED_OFF, harness.ledger().snapshot().state());

        assertInstanceOf(ExactWorkOrderLifecycleResult.ReleasePending.class, order.progressRelease(1));
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE), order.snapshot().custody());
        assertInstanceOf(ExactWorkOrderLifecycleResult.Cancelled.class, order.progressRelease(1));
        assertEquals(2, harness.storage().modulateInsertCalls);
    }

    @Test
    void emptyFreePlanCanAcknowledgeCancellationOnlyWithExactLeaseIdentity() {
        Harness wrongIdentity = normal("lifecycle-empty-wrong", Map.of());
        ExactWorkOrder order = wrongIdentity.order();
        assertInstanceOf(ExactWorkOrderLifecycleResult.ReleasePending.class, order.requestCancellation());
        ExactWorkOrderSnapshot pending = order.snapshot();
        assertEquals(ExactWorkOrderState.RELEASE_PENDING, pending.state());
        assertTrue(pending.custody().isEmpty());
        ExactWorkOrderLifecycleResult.Failure mismatch = assertInstanceOf(
                ExactWorkOrderLifecycleResult.Failure.class,
                order.acknowledgeHandoff(new CpuPlanHandle(UUID.randomUUID(), pending.handle().revision()),
                        pending.leaseIdentity()));
        assertEquals(ExactWorkOrderLifecycleResult.Reason.IDENTITY_MISMATCH, mismatch.reason());
        assertEquals(ExactWorkOrderState.FAIL_CLOSED, order.snapshot().state());
        assertEquals(ExactCpuLedgerState.HANDED_OFF, wrongIdentity.ledger().snapshot().state());

        Harness nonempty = normal("lifecycle-empty-gate", Map.of(0, AEAmount.ONE));
        assertInstanceOf(ExactWorkOrderLifecycleResult.ReleasePending.class, nonempty.order().requestCancellation());
        ExactWorkOrderSnapshot nonemptyPending = nonempty.order().snapshot();
        ExactWorkOrderLifecycleResult.Failure premature = assertInstanceOf(
                ExactWorkOrderLifecycleResult.Failure.class,
                nonempty.order().acknowledgeHandoff(nonemptyPending.handle(), nonemptyPending.leaseIdentity()));
        assertEquals(ExactWorkOrderLifecycleResult.Reason.WRONG_STATE, premature.reason());
        assertEquals(nonemptyPending, nonempty.order().snapshot());
        assertEquals(ExactCpuLedgerState.HANDED_OFF, nonempty.ledger().snapshot().state());
        assertInstanceOf(ExactWorkOrderLifecycleResult.Cancelled.class, nonempty.order().progressRelease(1));

        Harness exact = normal("lifecycle-empty-exact", Map.of());
        assertInstanceOf(ExactWorkOrderLifecycleResult.ReleasePending.class, exact.order().requestCancellation());
        ExactWorkOrderSnapshot exactPending = exact.order().snapshot();
        ExactWorkOrderLifecycleResult.Cancelled cancelled = assertInstanceOf(
                ExactWorkOrderLifecycleResult.Cancelled.class,
                exact.order().acknowledgeHandoff(exactPending.handle(), exactPending.leaseIdentity()));
        assertEquals(ExactWorkOrderState.COMPLETED, cancelled.snapshot().state());
        assertEquals(Optional.of(ExactWorkOrderReleaseMode.CANCELLATION), cancelled.snapshot().releaseMode());
        assertTrue(cancelled.snapshot().custody().isEmpty());
        assertEquals(ExactCpuLedgerState.IDLE, exact.ledger().snapshot().state());
        assertBrokerIdle(exact.broker());
        assertEquals(0, exact.storage().modulateInsertCalls);
        ExactWorkOrderSnapshot completed = exact.order().snapshot();
        ExactWorkOrderLifecycleResult.Failure duplicate = assertInstanceOf(
                ExactWorkOrderLifecycleResult.Failure.class,
                exact.order().acknowledgeHandoff(exactPending.handle(), exactPending.leaseIdentity()));
        assertEquals(ExactWorkOrderLifecycleResult.Reason.WRONG_STATE, duplicate.reason());
        assertEquals(completed, exact.order().snapshot());
    }

    @Test
    void snapshotStateAndReleaseModeRemainConsistentAcrossCancellation() {
        Map<Integer, AEAmount> debits = Map.of(0, AEAmount.ONE);
        Harness harness = normal("lifecycle-snapshot", debits);
        ExactWorkOrder order = harness.order();
        assertEquals(ExactWorkOrderState.READY, order.snapshot().state());
        assertTrue(order.snapshot().releaseMode().isEmpty());
        ExactWorkCommand command = issued(order);
        assertEquals(ExactWorkOrderState.COMMAND_OUTSTANDING, order.snapshot().state());
        assertTrue(order.snapshot().releaseMode().isEmpty());
        order.accept(new WorkCommandAcceptance(command));
        assertEquals(ExactWorkOrderState.IN_FLIGHT, order.snapshot().state());
        assertTrue(order.snapshot().releaseMode().isEmpty());
        assertInstanceOf(ExactWorkOrderLifecycleResult.CancellationWaiting.class, order.requestCancellation());
        assertEquals(ExactWorkOrderState.CANCEL_PENDING, order.snapshot().state());
        assertTrue(order.snapshot().releaseMode().isEmpty());
        order.complete(new WorkCommandCompletion(command, command.expectedOutputs(), command.expectedRemainders()));
        assertEquals(ExactWorkOrderState.RELEASE_PENDING, order.snapshot().state());
        assertEquals(Optional.of(ExactWorkOrderReleaseMode.CANCELLATION), order.snapshot().releaseMode());
        assertInstanceOf(ExactWorkOrderLifecycleResult.Cancelled.class, order.progressRelease(1));
        assertEquals(ExactWorkOrderState.COMPLETED, order.snapshot().state());
        assertEquals(Optional.of(ExactWorkOrderReleaseMode.CANCELLATION), order.snapshot().releaseMode());
    }

    private static ExactWorkCommand issued(ExactWorkOrder order) {
        return assertInstanceOf(ExactWorkOrderCommandResult.Issued.class, order.issueNext(Long.MAX_VALUE)).command();
    }

    private static void completeExactly(ExactWorkOrder order, ExactWorkCommand command) {
        assertInstanceOf(ExactWorkOrderTransitionResult.Accepted.class,
                order.accept(new WorkCommandAcceptance(command)));
        assertInstanceOf(ExactWorkOrderTransitionResult.Completed.class,
                order.complete(new WorkCommandCompletion(command, command.expectedOutputs(),
                        command.expectedRemainders())));
    }

    private static ExactWorkCommand foreign(ExactWorkCommand command) {
        WorkCommandId id = new WorkCommandId(command.id().planId(), command.id().leaseIdentity(),
                command.id().workOrderId(), command.id().generation() + 100L);
        return new ExactWorkCommand(id, command.handle(), command.reservationId(), command.batchId(),
                command.location(),
                command.pattern(), command.executionWindow(), command.plannedSelections(), command.custodyInputs(),
                command.expectedOutputSlots(), command.expectedOutputs(), command.expectedRemainders());
    }

    private static Harness normal(String id, Map<Integer, AEAmount> requestedDebits) {
        TreeMap<Integer, AEAmount> debits = new TreeMap<>(requestedDebits);
        int output = debits.isEmpty() ? 0 : debits.lastKey() + 1;
        int keyCount = output + 1;
        List<CompiledInputSpec> inputs = new ArrayList<>(debits.size());
        for (Map.Entry<Integer, AEAmount> entry : debits.entrySet()) {
            inputs.add(new CompiledInputSpec(
                    List.of(new CompiledCandidateSpec(new KeyId(entry.getKey()), entry.getValue(), Optional.empty())),
                    AEAmount.ONE, SubstitutionPolicy.EXACT));
        }
        CompiledPattern pattern = new CompiledPattern(new PatternId(id), PatternKind.CRAFTING, inputs,
                List.of(new CompiledOutputSpec(new KeyId(output), AEAmount.ONE, true)), Optional.empty(),
                new PatternRevision(0L), KEY_GENERATION);
        StorageSnapshot dependencies = snapshot(keyCount, requestedDebits);
        NormalizedPatternSnapshot patterns = patternSnapshot(pattern);
        ExactCraftPlanDraft draft = assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(new ExactCraftRequest(new KeyId(output), AEAmount.ONE), dependencies, patterns)).draft();
        ExactCraftingPlan plan = assertInstanceOf(ExactPlanValidationResult.Success.class,
                VALIDATOR.validate(draft, dependencies, patterns)).plan();
        LifecycleStorage storage = new LifecycleStorage(dependencies, plan.initialStorageDebits());
        LifecycleGate gate = new LifecycleGate();
        ExactTransferBroker broker = new ExactTransferBroker(storage, () -> patterns, gate, SOURCE);
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = assertInstanceOf(ExactCpuLedgerResult.Prepared.class, ledger.prepare(plan)).handle();
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        ExactWorkOrder order = assertInstanceOf(ExactTransferBrokerResult.Started.class,
                broker.startWorkOrder(ledger, handle)).workOrder();
        return new Harness(plan, storage, gate, broker, ledger, order);
    }

    private static NormalizedPatternSnapshot patternSnapshot(CompiledPattern pattern) {
        CompiledPatternGraph graph = assertInstanceOf(GraphBuildResult.Success.class,
                new CompiledPatternGraphBuilder(GRAPH_GENERATION, KEY_GENERATION).build(List.of(pattern))).graph();
        return new NormalizedPatternSnapshot(SERVER_GENERATION, RECIPE_REVISION, KEY_GENERATION, graph.patternsById(),
                graph, Map.of(pattern.id(), 0), 1, false, List.of());
    }

    private static StorageSnapshot snapshot(int keyCount, Map<Integer, AEAmount> values) {
        AmountVector amounts = new AmountVector(keyCount);
        long[] revisions = new long[keyCount];
        values.forEach(amounts::set);
        return new StorageSnapshot(KEY_GENERATION, StorageRevision.ZERO, keyCount, amounts, revisions);
    }

    private static KeyId outputKey(Map<Integer, AEAmount> debits) {
        return new KeyId(debits.isEmpty() ? 0 : debits.keySet().stream().max(Integer::compareTo).orElseThrow() + 1);
    }

    private static void assertBrokerIdle(ExactTransferBroker broker) {
        ExactTransferBrokerSnapshot snapshot = broker.snapshot();
        assertEquals(ExactTransferBrokerState.IDLE, snapshot.state());
        assertTrue(snapshot.handle().isEmpty());
        assertTrue(snapshot.planId().isEmpty());
        assertTrue(snapshot.reservationId().isEmpty());
        assertTrue(snapshot.workOrderId().isEmpty());
        assertTrue(snapshot.leaseIdentity().isEmpty());
        assertTrue(snapshot.escrowed().isEmpty());
    }

    private record Harness(ExactCraftingPlan plan, LifecycleStorage storage, LifecycleGate gate,
            ExactTransferBroker broker, ExactCpuLedger ledger, ExactWorkOrder order) {
    }

    private enum InsertScript {
        NULL,
        OVER_RETURN
    }

    private static final class LifecycleGate implements ServerThreadGate {
        private boolean allowed = true;
        private Runnable hook;

        @Override
        public boolean isServerThread() {
            if (hook != null) {
                Runnable current = hook;
                hook = null;
                current.run();
            }
            return allowed;
        }
    }

    private static final class LifecycleStorage implements BrokerExactStorage {
        private final StorageSnapshot dependencies;
        private final TreeMap<KeyId, AEAmount> physical = new TreeMap<>(KEY_ORDER);
        private final ArrayDeque<InsertBehavior> insertScripts = new ArrayDeque<>();
        private int modulateInsertCalls;
        private Runnable insertHook;

        private LifecycleStorage(StorageSnapshot dependencies, Map<KeyId, AEAmount> initial) {
            this.dependencies = dependencies;
            physical.putAll(initial);
        }

        @Override
        public AEAmount insert(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
            if (mode == Actionable.SIMULATE) {
                return amount;
            }
            modulateInsertCalls++;
            InsertBehavior behavior = insertScripts.isEmpty() ? InsertBehavior.returning(amount)
                    : insertScripts.removeFirst();
            if (behavior.failure() != null) {
                throw behavior.failure();
            }
            if (behavior.apply() && behavior.returned() != null && !behavior.returned().equals(AEAmount.ZERO)) {
                physical.put(key, amount(key).add(behavior.returned()));
            }
            if (insertHook != null) {
                insertHook.run();
            }
            return behavior.returned();
        }

        @Override
        public AEAmount extract(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
            AEAmount extracted = amount(key).min(amount);
            if (mode == Actionable.MODULATE && !extracted.equals(AEAmount.ZERO)) {
                AEAmount remainder = amount(key).subtractExact(extracted);
                if (remainder.equals(AEAmount.ZERO)) {
                    physical.remove(key);
                } else {
                    physical.put(key, remainder);
                }
            }
            return extracted;
        }

        @Override
        public AEAmount amount(KeyId key) {
            return physical.getOrDefault(key, AEAmount.ZERO);
        }

        @Override
        public void enumerate(ExactStorageVisitor visitor) {
            physical.forEach(visitor::accept);
        }

        @Override
        public StorageSnapshot captureSnapshot() {
            return dependencies;
        }

        private void enqueueReturn(AEAmount amount) {
            insertScripts.addLast(InsertBehavior.returning(amount));
        }

        private void enqueueNull() {
            insertScripts.addLast(new InsertBehavior(null, false, null));
        }

        private void enqueueOverReturn() {
            insertScripts.addLast(new InsertBehavior(AEAmount.of(2L), false, null));
        }

        private void enqueueThrow() {
            insertScripts.addLast(new InsertBehavior(null, false, new RuntimeException("scripted insert failure")));
        }
    }

    private record InsertBehavior(AEAmount returned, boolean apply, RuntimeException failure) {
        private static InsertBehavior returning(AEAmount amount) {
            return new InsertBehavior(amount, true, null);
        }
    }
}

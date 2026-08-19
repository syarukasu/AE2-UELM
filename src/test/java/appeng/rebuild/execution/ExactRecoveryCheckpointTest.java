package appeng.rebuild.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

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
import appeng.rebuild.planner.ExactCraftPlanResult;
import appeng.rebuild.planner.ExactCraftPlanner;
import appeng.rebuild.planner.ExactCraftRequest;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Package-bound matrix tests for inert recovery checkpoint state ownership. */
class ExactRecoveryCheckpointTest {
    private static final long KEY_GENERATION = 211L;
    private static final long SERVER_GENERATION = 223L;
    private static final KeyId OUTPUT = new KeyId(0);
    private static final CpuPlanHandle HANDLE = new CpuPlanHandle(
            UUID.fromString("00000000-0000-0000-0000-000000000211"), 1L);
    private static final ReservationId RESERVATION = new ReservationId(
            UUID.fromString("00000000-0000-0000-0000-000000000223"));
    private static final UUID LEASE = UUID.fromString("00000000-0000-0000-0000-000000000227");
    private static final WorkOrderId WORK_ORDER = new WorkOrderId(
            UUID.fromString("00000000-0000-0000-0000-000000000229"));

    @Test
    void preHandoffReservedCheckpointHasNoWorkOrderAndExactEscrow() {
        ExactCraftingPlan plan = plan();
        ExactCpuLedgerSnapshot ledger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.RESERVED, 1L,
                Optional.of(HANDLE), Optional.of(RESERVATION), plan.initialStorageDebits(), Optional.empty(),
                Optional.empty());
        ExactTransferBrokerSnapshot broker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.RESERVED,
                Optional.of(HANDLE), Optional.of(plan.planId()), Optional.of(RESERVATION), Optional.empty(),
                Optional.empty(), plan.initialStorageDebits(), Optional.empty());

        ExactRecoveryCheckpoint checkpoint = new ExactRecoveryCheckpoint(plan, ledger, broker, Optional.empty(),
                false);

        assertEquals(plan, checkpoint.plan());
        assertTrue(checkpoint.workOrder().isEmpty());
        assertEquals(plan.initialStorageDebits(), checkpoint.broker().escrowed());
        assertTrue(!checkpoint.recoveryRequired());
    }

    @Test
    void handedOffLeasedCheckpointRequiresAndRetainsOptionalWorkOrder() {
        ExactCraftingPlan plan = plan();
        ExactCpuLedgerSnapshot ledger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.HANDED_OFF, 1L,
                Optional.of(HANDLE), Optional.of(RESERVATION), plan.initialStorageDebits(), Optional.empty(),
                Optional.of(LEASE));
        ExactTransferBrokerSnapshot broker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.LEASED,
                Optional.of(HANDLE), Optional.of(plan.planId()), Optional.of(RESERVATION), Optional.of(WORK_ORDER),
                Optional.of(LEASE), Map.of(), Optional.empty());
        ExactWorkOrderSnapshot work = readyWork(plan);

        ExactRecoveryCheckpoint checkpoint = new ExactRecoveryCheckpoint(plan, ledger, broker, Optional.of(work),
                false);

        assertEquals(Optional.of(work), checkpoint.workOrder());
        assertEquals(ExactCpuLedgerState.HANDED_OFF, checkpoint.ledger().state());
        assertEquals(ExactTransferBrokerState.LEASED, checkpoint.broker().state());
        assertEquals(WORK_ORDER, checkpoint.workOrder().orElseThrow().workOrderId());
    }

    @Test
    void checkpointRejectsWorkOrderOnPreHandoffAndMissingWorkOrderAfterHandoff() {
        ExactCraftingPlan plan = plan();
        ExactCpuLedgerSnapshot reservedLedger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.RESERVED, 1L,
                Optional.of(HANDLE), Optional.of(RESERVATION), plan.initialStorageDebits(), Optional.empty(),
                Optional.empty());
        ExactTransferBrokerSnapshot reservedBroker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.RESERVED,
                Optional.of(HANDLE), Optional.of(plan.planId()), Optional.of(RESERVATION), Optional.empty(),
                Optional.empty(), plan.initialStorageDebits(), Optional.empty());
        ExactWorkOrderSnapshot work = readyWork(plan);
        assertThrows(IllegalArgumentException.class,
                () -> new ExactRecoveryCheckpoint(plan, reservedLedger, reservedBroker, Optional.of(work), false));

        ExactCpuLedgerSnapshot handedOffLedger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.HANDED_OFF, 1L,
                Optional.of(HANDLE), Optional.of(RESERVATION), plan.initialStorageDebits(), Optional.empty(),
                Optional.of(LEASE));
        ExactTransferBrokerSnapshot leasedBroker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.LEASED,
                Optional.of(HANDLE), Optional.of(plan.planId()), Optional.of(RESERVATION), Optional.of(WORK_ORDER),
                Optional.of(LEASE), Map.of(), Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> new ExactRecoveryCheckpoint(plan, handedOffLedger, leasedBroker, Optional.empty(), false));
    }

    @Test
    void failedHandoffCheckpointRetainsTheExactLeasePlanObject() {
        ExactCraftingPlan plan = plan();
        ExactCpuLedgerSnapshot ledger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.HANDED_OFF, 1L,
                Optional.of(HANDLE), Optional.of(RESERVATION), plan.initialStorageDebits(), Optional.empty(),
                Optional.of(LEASE));
        ExactTransferBrokerSnapshot broker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.FAIL_CLOSED,
                Optional.of(HANDLE), Optional.of(plan.planId()), Optional.of(RESERVATION), Optional.of(WORK_ORDER),
                Optional.of(LEASE), plan.initialStorageDebits(), Optional.empty());
        ReservedPlanLease lease = new ReservedPlanLease(LEASE, HANDLE, RESERVATION, plan,
                plan.initialStorageDebits());

        ExactRecoveryCheckpoint checkpoint = new ExactRecoveryCheckpoint(plan, ledger, broker, Optional.empty(),
                Optional.of(lease), true);

        assertSame(plan, checkpoint.plan());
        assertSame(plan, checkpoint.failedHandoffLease().orElseThrow().plan());

        ExactCraftingPlan otherPlan = plan();
        ReservedPlanLease mismatchedLease = new ReservedPlanLease(LEASE, HANDLE, RESERVATION, otherPlan,
                otherPlan.initialStorageDebits());
        assertThrows(IllegalArgumentException.class,
                () -> new ExactRecoveryCheckpoint(plan, ledger, broker, Optional.empty(),
                        Optional.of(mismatchedLease), true));
    }

    @Test
    void preparedCheckpointRequiresBrokerReservationIdentity() {
        ExactCraftingPlan plan = planWithDebit();
        Map<KeyId, AEAmount> escrow = plan.initialStorageDebits();
        ExactCpuLedgerSnapshot ledger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.PREPARED, 1L,
                Optional.of(HANDLE), Optional.empty(), Map.of(), Optional.empty(), Optional.empty());
        ExactTransferBrokerSnapshot broker = new ExactTransferBrokerSnapshot(
                ExactTransferBrokerState.ROLLBACK_PENDING, Optional.of(HANDLE), Optional.of(plan.planId()),
                Optional.of(RESERVATION), Optional.empty(), Optional.empty(), escrow, Optional.empty());
        ExactRecoveryCheckpoint checkpoint = new ExactRecoveryCheckpoint(plan, ledger, broker, Optional.empty(),
                false);
        assertEquals(Optional.of(RESERVATION), checkpoint.broker().reservationId());

        ExactTransferBrokerSnapshot missingReservation = new ExactTransferBrokerSnapshot(
                ExactTransferBrokerState.ROLLBACK_PENDING, Optional.of(HANDLE), Optional.of(plan.planId()),
                Optional.empty(), Optional.empty(), Optional.empty(), escrow, Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> new ExactRecoveryCheckpoint(plan, ledger, missingReservation, Optional.empty(), false));
    }

    private static ExactWorkOrderSnapshot readyWork(ExactCraftingPlan plan) {
        return new ExactWorkOrderSnapshot(ExactWorkOrderState.READY, plan.planId(), HANDLE, RESERVATION, LEASE,
                WORK_ORDER, Map.of(), 0, AEAmount.ZERO, List.of(), AEAmount.ZERO, -1, AEAmount.ZERO, List.of(),
                Optional.empty(), 0L, false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                false);
    }

    private static ExactCraftingPlan plan() {
        return plan(false);
    }

    private static ExactCraftingPlan planWithDebit() {
        return plan(true);
    }

    private static ExactCraftingPlan plan(boolean withDebit) {
        KeyId input = new KeyId(1);
        List<CompiledInputSpec> inputs = withDebit
                ? List.of(new CompiledInputSpec(
                        List.of(new CompiledCandidateSpec(input, AEAmount.ONE, Optional.empty())), AEAmount.ONE,
                        SubstitutionPolicy.EXACT))
                : List.of();
        CompiledPattern pattern = new CompiledPattern(
                new PatternId(withDebit ? "checkpoint-with-input" : "checkpoint-no-input"),
                PatternKind.CRAFTING, inputs, List.of(new CompiledOutputSpec(OUTPUT, AEAmount.ONE, true)),
                Optional.empty(),
                new PatternRevision(0L), KEY_GENERATION);
        CompiledPatternGraph graph = assertSuccess(
                new CompiledPatternGraphBuilder(new GraphGeneration(227L), KEY_GENERATION).build(List.of(pattern)));
        NormalizedPatternSnapshot patterns = new NormalizedPatternSnapshot(SERVER_GENERATION, new RecipeRevision(229L),
                KEY_GENERATION, graph.patternsById(), graph, Map.of(pattern.id(), 0), 1, false, List.of());
        AmountVector amounts = new AmountVector(withDebit ? 2 : 1);
        if (withDebit)
            amounts.set(input.value(), AEAmount.ONE);
        StorageSnapshot storage = new StorageSnapshot(KEY_GENERATION, StorageRevision.ZERO, withDebit ? 2 : 1,
                amounts, new long[withDebit ? 2 : 1]);
        ExactCraftPlanResult.Success result = (ExactCraftPlanResult.Success) new ExactCraftPlanner()
                .plan(new ExactCraftRequest(OUTPUT, AEAmount.ONE), storage, patterns);
        return ((ExactPlanValidationResult.Success) new ExactCraftingPlanValidator()
                .validate(result.draft(), storage, patterns)).plan();
    }

    private static CompiledPatternGraph assertSuccess(GraphBuildResult result) {
        return ((GraphBuildResult.Success) result).graph();
    }
}

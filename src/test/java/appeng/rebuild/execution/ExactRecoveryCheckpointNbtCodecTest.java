package appeng.rebuild.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
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
import appeng.rebuild.persistence.ExactRecoveryCheckpointNbtCodec;
import appeng.rebuild.persistence.PersistenceDecodeResult;
import appeng.rebuild.persistence.PersistenceLimits;
import appeng.rebuild.planner.ExactCraftPlanDraft;
import appeng.rebuild.planner.ExactCraftPlanResult;
import appeng.rebuild.planner.ExactCraftPlanner;
import appeng.rebuild.planner.ExactCraftRequest;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.BrokerExactStorage;
import appeng.rebuild.storage.ExactStorageVisitor;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Tests the strict aggregate checkpoint persistence boundary without Minecraft bootstrap or live world access. */
class ExactRecoveryCheckpointNbtCodecTest {
    private static final long KEY_GENERATION = 601L;
    private static final long CURRENT_KEY_GENERATION = 607L;
    private static final long SERVER_GENERATION = 613L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(617L);
    private static final RecipeRevision RECIPE_REVISION = new RecipeRevision(619L);
    private static final KeyId SEED = new KeyId(0);
    private static final KeyId EXTRA = new KeyId(1);
    private static final KeyId SECONDARY = new KeyId(2);
    private static final CpuPlanHandle HANDLE = new CpuPlanHandle(
            UUID.fromString("00000000-0000-0000-0000-000000000601"), 1L);
    private static final ReservationId RESERVATION = new ReservationId(
            UUID.fromString("00000000-0000-0000-0000-000000000607"));
    private static final UUID LEASE = UUID.fromString("00000000-0000-0000-0000-000000000613");
    private static final WorkOrderId WORK_ORDER = new WorkOrderId(
            UUID.fromString("00000000-0000-0000-0000-000000000617"));
    private static final IActionSource SOURCE = IActionSource.empty();
    private static final Comparator<KeyId> KEY_ORDER = Comparator.comparingInt(KeyId::value);
    private static final ExactCraftPlanner PLANNER = new ExactCraftPlanner();
    private static final ExactCraftingPlanValidator VALIDATOR = new ExactCraftingPlanValidator();

    @Test
    void representativePreHandoffStatesRoundTripWithOneOuterKeyTableAndNoNestedPlanTable() {
        Fixture fixture = cycleFixture(AEAmount.of(5L), false);
        List<ExactRecoveryCheckpoint> checkpoints = List.of(prepared(fixture), reserved(fixture),
                releasePending(fixture, Map.of(SEED, AEAmount.ONE)), releasePending(fixture, Map.of()));

        for (ExactRecoveryCheckpoint checkpoint : checkpoints) {
            Encoded decoded = roundTrip(fixture, checkpoint, List.of(0));
            assertFalse(decoded.root().getCompound("p").contains("k"));
            assertCheckpointEquivalent(checkpoint, decoded.checkpoint(), fixture, decoded.current());
        }
    }

    @Test
    void hugeExactCycleProgressAndAllCommandIdentityCursorsRoundTrip() {
        for (BigInteger demand : List.of(BigInteger.ONE.shiftLeft(64), BigInteger.ONE.shiftLeft(128),
                BigInteger.TEN.pow(1000))) {
            Fixture fixture = cycleFixture(AEAmount.of(demand), false);
            Harness harness = handoff(fixture, false);
            int storageCalls = harness.storage().transferCalls();
            ExactRecoveryCheckpoint ready = checkpoint(harness, false);
            ExactWorkCommand command = issued(harness.order());
            ExactRecoveryCheckpoint outstanding = checkpoint(harness, false);
            harness.order().accept(new WorkCommandAcceptance(command));
            ExactRecoveryCheckpoint inFlight = checkpoint(harness, true);
            int gateCalls = harness.gate().calls;

            assertEquals(AEAmount.of(demand), ready.workOrder().orElseThrow().cycleRemainingRepetitions());
            assertTrue(outstanding.workOrder().orElseThrow().outstandingCommand().isPresent());
            assertTrue(inFlight.workOrder().orElseThrow().inFlightCommand().isPresent());
            assertEquals(command.location(),
                    inFlight.workOrder().orElseThrow().inFlightCommand().orElseThrow().location());

            Encoded readyDecoded = roundTrip(fixture, ready, List.of(0));
            assertCheckpointEquivalent(ready, readyDecoded.checkpoint(), fixture, readyDecoded.current());
            Encoded outstandingDecoded = roundTrip(fixture, outstanding, List.of(0));
            assertCheckpointEquivalent(outstanding, outstandingDecoded.checkpoint(), fixture,
                    outstandingDecoded.current());
            Encoded inFlightDecoded = roundTrip(fixture, inFlight, List.of(0));
            assertCheckpointEquivalent(inFlight, inFlightDecoded.checkpoint(), fixture, inFlightDecoded.current());
            assertTrue(inFlightDecoded.checkpoint().recoveryRequired());
            assertEquals(storageCalls, harness.storage().transferCalls(),
                    "checkpoint codec must not call physical storage");
            assertEquals(gateCalls, harness.gate().calls, "checkpoint codec must not call the server-thread gate");
        }
    }

    @Test
    void zeroInputProducerProgressIsAValidRestorableCheckpoint() {
        Fixture fixture = zeroInputFixture();
        Harness harness = handoff(fixture, false);
        ExactWorkOrderSnapshot work = harness.order().snapshot();
        assertEquals(AEAmount.ONE, work.remainingExecutions());
        assertTrue(work.selectionRemaining().isEmpty());
        ExactRecoveryCheckpoint checkpoint = checkpoint(harness, false);

        Encoded decoded = roundTrip(fixture, checkpoint, List.of(0));
        assertCheckpointEquivalent(checkpoint, decoded.checkpoint(), fixture, decoded.current());
        assertTrue(decoded.checkpoint().workOrder().orElseThrow().selectionRemaining().isEmpty());
    }

    @Test
    void workOrderReleasePreservesReleaseModeAndPartialCustody() {
        Fixture fixture = cycleFixture(AEAmount.ONE, true);
        Harness harness = handoff(fixture, true);
        ExactWorkCommand command = issued(harness.order());
        harness.order().accept(new WorkCommandAcceptance(command));
        harness.order().complete(new WorkCommandCompletion(command, command.expectedOutputs(),
                command.expectedRemainders()));
        ExactWorkOrderLifecycleResult.ReleasePending pending = assertInstanceOf(
                ExactWorkOrderLifecycleResult.ReleasePending.class, harness.order().progressRelease(1));
        assertEquals(ExactWorkOrderReleaseMode.SETTLEMENT, pending.snapshot().releaseMode().orElseThrow());
        assertFalse(pending.snapshot().custody().isEmpty());

        ExactRecoveryCheckpoint checkpoint = checkpoint(harness, false);
        Encoded decoded = roundTrip(fixture, checkpoint, List.of(0, 2));
        assertCheckpointEquivalent(checkpoint, decoded.checkpoint(), fixture, decoded.current());
        assertEquals(Optional.of(ExactWorkOrderReleaseMode.SETTLEMENT),
                decoded.checkpoint().workOrder().orElseThrow().releaseMode());
    }

    @Test
    void workOrderDiscrepancyPreservesRecoveryAuthority() {
        Fixture mismatchFixture = cycleFixture(AEAmount.ONE, false);
        Harness mismatch = handoff(mismatchFixture, false);
        ExactWorkCommand mismatchCommand = issued(mismatch.order());
        mismatch.order().accept(new WorkCommandAcceptance(mismatchCommand));
        ExactWorkOrderTransitionResult.Failure mismatchResult = assertInstanceOf(
                ExactWorkOrderTransitionResult.Failure.class,
                mismatch.order().complete(new WorkCommandCompletion(mismatchCommand, Map.of(), Map.of())));
        assertEquals(ExactWorkOrderTransitionResult.Reason.RESULT_MISMATCH, mismatchResult.reason());
        ExactRecoveryCheckpoint discrepancy = checkpoint(mismatch, true);
        Encoded discrepancyDecoded = roundTrip(mismatchFixture, discrepancy, List.of(0));
        assertCheckpointEquivalent(discrepancy, discrepancyDecoded.checkpoint(), mismatchFixture,
                discrepancyDecoded.current());
        assertTrue(discrepancyDecoded.checkpoint().workOrder().orElseThrow().discrepancy().isPresent());

    }

    @Test
    void failedHandoffAndBrokerTransferDiscrepancyRoundTripAndRemapUnexpectedEvidenceKey() {
        Fixture fixture = cycleFixture(AEAmount.of(5L), false);
        ExactRecoveryCheckpoint failed = failedHandoff(fixture);
        Encoded failedDecoded = roundTrip(fixture, failed, List.of(0));
        assertCheckpointEquivalent(failed, failedDecoded.checkpoint(), fixture, failedDecoded.current());
        assertEquals(failed.failedHandoff().orElseThrow(), failedDecoded.checkpoint().failedHandoff().orElseThrow());

        BrokerTransferDiscrepancy evidence = new BrokerTransferDiscrepancy(
                BrokerTransferDiscrepancy.Operation.EXTRACT, BrokerTransferDiscrepancy.Reason.NULL_RETURN,
                fixture.plan().planId(), HANDLE, RESERVATION, EXTRA, BigIntegerAmountValue(), Optional.empty(),
                Map.of());
        ExactCpuLedgerSnapshot ledger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.PREPARED, 1L,
                Optional.of(HANDLE), Optional.empty(), Map.of(), Optional.empty(), Optional.empty());
        ExactTransferBrokerSnapshot broker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.FAIL_CLOSED,
                Optional.of(HANDLE), Optional.of(fixture.plan().planId()), Optional.of(RESERVATION), Optional.empty(),
                Optional.empty(), Map.of(), Optional.of(evidence));
        ExactRecoveryCheckpoint discrepancy = new ExactRecoveryCheckpoint(fixture.plan(), ledger, broker,
                Optional.empty(), Optional.empty(), true);
        Encoded remapped = roundTrip(fixture, discrepancy, List.of(1));
        assertEquals(new KeyId(1), remapped.checkpoint().plan().request().output());
        assertEquals(new KeyId(0), remapped.checkpoint().broker().transferDiscrepancy().orElseThrow().key());
        assertEquals(new KeyId(1), remapped.current().lookup(fixture.keys().get(0)));
        assertEquals(new KeyId(0), remapped.current().lookup(fixture.keys().get(1)));
        assertEquals(BigInteger.ONE.shiftLeft(64),
                remapped.checkpoint().broker().transferDiscrepancy().orElseThrow().requested().toBigInteger());
        assertEquals(2, remapped.root().getCompound("k").getList("e", Tag.TAG_COMPOUND).size());
    }

    @Test
    void malformedVersionEnumBooleanUuidOrderAndKeyClosureNeverMutateCurrentRegistry() {
        Fixture fixture = cycleFixture(AEAmount.of(5L), false);
        ExactRecoveryCheckpoint checkpoint = brokerDiscrepancy(fixture);
        CompoundTag encoded = ExactRecoveryCheckpointNbtCodec.encode(checkpoint, fixture.source());
        List<Mutation> mutations = List.of(
                root -> root.putInt("v", PersistenceLimits.FORMAT_VERSION + 1),
                root -> root.getCompound("b").putString("s", "NOT_A_BROKER_STATE"),
                root -> root.putByte("r", (byte) 2),
                root -> root.getCompound("p").getCompound("i").putString("u",
                        root.getCompound("p").getCompound("i").getString("u").toUpperCase()),
                ExactRecoveryCheckpointNbtCodecTest::swapOuterKeys,
                ExactRecoveryCheckpointNbtCodecTest::removeUnexpectedKey,
                ExactRecoveryCheckpointNbtCodecTest::addUnreferencedKey);
        for (int mutationIndex = 0; mutationIndex < mutations.size(); mutationIndex++) {
            Mutation mutation = mutations.get(mutationIndex);
            KeyRegistry current = new KeyRegistry(CURRENT_KEY_GENERATION);
            try (MockedStatic<AEKey> ignored = decoder(fixture)) {
                CompoundTag forged = encoded.copy();
                mutation.apply(forged);
                try {
                    assertFailure(ExactRecoveryCheckpointNbtCodec.decode(forged, current),
                            mutationIndex == 0 ? PersistenceDecodeResult.Reason.UNSUPPORTED_VERSION
                                    : PersistenceDecodeResult.Reason.MALFORMED);
                } catch (AssertionError failure) {
                    throw new AssertionError("mutation index=" + mutationIndex, failure);
                }
            }
            assertEquals(0, current.size(), "malformed aggregate must not intern any current key");
        }
    }

    @Test
    void validShapeWorkCursorCustodyCandidateSkipAndTypedEmptyListAreRejected() {
        Fixture fixture = candidateFixture();
        Harness harness = handoff(fixture, false);
        ExactWorkCommand command = issued(harness.order());
        ExactRecoveryCheckpoint outstanding = checkpoint(harness, false);
        CompoundTag base = ExactRecoveryCheckpointNbtCodec.encode(outstanding, fixture.source());

        CompoundTag forgedCustody = base.copy();
        CompoundTag work = forgedCustody.getCompound("w").getCompound("v");
        CompoundTag custody = work.getCompound("c");
        custody.getList("l", Tag.TAG_COMPOUND).getCompound(0).getCompound("a").putByteArray("u", new byte[] { 2 });
        assertMalformedWithoutMutation(fixture, forgedCustody);

        CompoundTag typedEmpty = base.copy();
        ListTag typed = new ListTag();
        typed.add(new CompoundTag());
        typed.remove(0);
        setListElementType(typed, Tag.TAG_COMPOUND);
        typedEmpty.getCompound("w").getCompound("v").put("n", typed);
        assertEquals(Tag.TAG_COMPOUND, Byte.toUnsignedInt(typed.getElementType()));
        assertMalformedWithoutMutation(fixture, typedEmpty);

        assertFalse(command.location().isCycle());
        CompoundTag forgedCursor = base.copy();
        CompoundTag cursorWork = forgedCursor.getCompound("w").getCompound("v");
        cursorWork.getList("n", Tag.TAG_COMPOUND).getCompound(0).putByteArray("u", new byte[] { 3 });
        assertMalformedWithoutMutation(fixture, forgedCursor);
    }

    @Test
    void commandSkippingTheEarlierAvailableCandidateIsRejectedBeforeRegistryCommit() {
        Fixture fixture = candidateFixture();
        Harness harness = handoff(fixture, false);
        ExactWorkCommand command = issued(harness.order());
        assertEquals(0, command.plannedSelections().get(0).candidateIndex());
        ExactRecoveryCheckpoint checkpoint = new ExactRecoveryCheckpoint(fixture.plan(), harness.ledger().snapshot(),
                harness.broker().snapshot(), Optional.of(harness.order().snapshot()), Optional.empty(), false);
        CompoundTag forged = ExactRecoveryCheckpointNbtCodec.encode(checkpoint, fixture.source());
        CompoundTag encodedCommand = forged.getCompound("w").getCompound("v").getCompound("a").getCompound("v");
        encodedCommand.getList("s", Tag.TAG_COMPOUND).getCompound(0).putInt("c", 1);
        encodedCommand.getList("s", Tag.TAG_COMPOUND).getCompound(0).putInt("k", 1);
        encodedCommand.getCompound("c").getList("l", Tag.TAG_COMPOUND).getCompound(0).putInt("k", 1);
        assertMalformedWithoutMutation(fixture, forged);
    }

    @Test
    void forgedReleaseModeCannotCoexistWithRetainedCommandOrCompletionEvidence() {
        Fixture outstandingFixture = cycleFixture(AEAmount.ONE, false);
        Harness outstanding = handoff(outstandingFixture, false);
        issued(outstanding.order());
        assertReleaseModeConflict(outstandingFixture, checkpoint(outstanding, false));

        Fixture inFlightFixture = cycleFixture(AEAmount.ONE, false);
        Harness inFlight = handoff(inFlightFixture, false);
        ExactWorkCommand inFlightCommand = issued(inFlight.order());
        inFlight.order().accept(new WorkCommandAcceptance(inFlightCommand));
        assertReleaseModeConflict(inFlightFixture, checkpoint(inFlight, true));

        Fixture discrepancyFixture = cycleFixture(AEAmount.ONE, false);
        Harness discrepancy = handoff(discrepancyFixture, false);
        ExactWorkCommand discrepancyCommand = issued(discrepancy.order());
        discrepancy.order().accept(new WorkCommandAcceptance(discrepancyCommand));
        discrepancy.order().complete(new WorkCommandCompletion(discrepancyCommand, Map.of(), Map.of()));
        assertReleaseModeConflict(discrepancyFixture, checkpoint(discrepancy, true));

    }

    private static AEAmount BigIntegerAmountValue() {
        return AEAmount.of(BigInteger.ONE.shiftLeft(64));
    }

    private static ExactRecoveryCheckpoint prepared(Fixture fixture) {
        ExactCpuLedgerSnapshot ledger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.PREPARED, 1L,
                Optional.of(HANDLE), Optional.empty(), Map.of(), Optional.empty(), Optional.empty());
        ExactTransferBrokerSnapshot broker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.ROLLBACK_PENDING,
                Optional.of(HANDLE), Optional.of(fixture.plan().planId()), Optional.of(RESERVATION), Optional.empty(),
                Optional.empty(), fixture.plan().initialStorageDebits(), Optional.empty());
        return new ExactRecoveryCheckpoint(fixture.plan(), ledger, broker, Optional.empty(), Optional.empty(), false);
    }

    private static ExactRecoveryCheckpoint reserved(Fixture fixture) {
        ExactCpuLedgerSnapshot ledger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.RESERVED, 1L,
                Optional.of(HANDLE), Optional.of(RESERVATION), fixture.plan().initialStorageDebits(), Optional.empty(),
                Optional.empty());
        ExactTransferBrokerSnapshot broker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.RESERVED,
                Optional.of(HANDLE), Optional.of(fixture.plan().planId()), Optional.of(RESERVATION), Optional.empty(),
                Optional.empty(), fixture.plan().initialStorageDebits(), Optional.empty());
        return new ExactRecoveryCheckpoint(fixture.plan(), ledger, broker, Optional.empty(), Optional.empty(), false);
    }

    private static ExactRecoveryCheckpoint releasePending(Fixture fixture, Map<KeyId, AEAmount> escrow) {
        ReleaseObligation obligation = ReleaseObligation.restoreForRecovery(HANDLE, RESERVATION,
                fixture.plan().initialStorageDebits());
        ExactCpuLedgerSnapshot ledger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.RELEASE_PENDING, 1L,
                Optional.of(HANDLE), Optional.of(RESERVATION), fixture.plan().initialStorageDebits(),
                Optional.of(obligation), Optional.empty());
        ExactTransferBrokerSnapshot broker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.RELEASE_PENDING,
                Optional.of(HANDLE), Optional.of(fixture.plan().planId()), Optional.of(RESERVATION), Optional.empty(),
                Optional.empty(), escrow, Optional.empty());
        return new ExactRecoveryCheckpoint(fixture.plan(), ledger, broker, Optional.empty(), Optional.empty(), false);
    }

    private static ExactRecoveryCheckpoint failedHandoff(Fixture fixture) {
        ExactCpuLedgerSnapshot ledger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.HANDED_OFF, 1L,
                Optional.of(HANDLE), Optional.of(RESERVATION), fixture.plan().initialStorageDebits(), Optional.empty(),
                Optional.of(LEASE));
        ExactTransferBrokerSnapshot broker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.FAIL_CLOSED,
                Optional.of(HANDLE), Optional.of(fixture.plan().planId()), Optional.of(RESERVATION),
                Optional.of(WORK_ORDER), Optional.of(LEASE), fixture.plan().initialStorageDebits(), Optional.empty());
        FailedHandoffRecovery failed = new FailedHandoffRecovery(LEASE, HANDLE, RESERVATION, fixture.plan().planId(),
                fixture.plan().initialStorageDebits());
        return new ExactRecoveryCheckpoint(fixture.plan(), ledger, broker, Optional.empty(), Optional.of(failed), true);
    }

    private static ExactRecoveryCheckpoint brokerDiscrepancy(Fixture fixture) {
        ExactCpuLedgerSnapshot ledger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.PREPARED, 1L,
                Optional.of(HANDLE), Optional.empty(), Map.of(), Optional.empty(), Optional.empty());
        BrokerTransferDiscrepancy discrepancy = new BrokerTransferDiscrepancy(
                BrokerTransferDiscrepancy.Operation.EXTRACT, BrokerTransferDiscrepancy.Reason.NULL_RETURN,
                fixture.plan().planId(), HANDLE, RESERVATION, EXTRA, BigIntegerAmountValue(), Optional.empty(),
                Map.of());
        ExactTransferBrokerSnapshot broker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.FAIL_CLOSED,
                Optional.of(HANDLE), Optional.of(fixture.plan().planId()), Optional.of(RESERVATION), Optional.empty(),
                Optional.empty(), Map.of(), Optional.of(discrepancy));
        return new ExactRecoveryCheckpoint(fixture.plan(), ledger, broker, Optional.empty(), Optional.empty(), true);
    }

    private static ExactRecoveryCheckpoint checkpoint(Harness harness, boolean recoveryRequired) {
        return new ExactRecoveryCheckpoint(harness.fixture().plan(), harness.ledger().snapshot(),
                harness.broker().snapshot(), Optional.of(harness.order().snapshot()), Optional.empty(),
                recoveryRequired);
    }

    private static ExactWorkCommand issued(ExactWorkOrder order) {
        return assertInstanceOf(ExactWorkOrderCommandResult.Issued.class, order.issueNext(Long.MAX_VALUE)).command();
    }

    private static Harness handoff(Fixture fixture, boolean failInsert) {
        CountingStorage storage = new CountingStorage(fixture.storage(), fixture.plan().initialStorageDebits(),
                failInsert);
        AtomicGate gate = new AtomicGate();
        ExactTransferBroker broker = new ExactTransferBroker(storage, () -> fixture.patterns(), gate, SOURCE);
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = assertInstanceOf(ExactCpuLedgerResult.Prepared.class, ledger.prepare(fixture.plan()))
                .handle();
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        ExactWorkOrder order = assertInstanceOf(ExactTransferBrokerResult.Started.class,
                broker.startWorkOrder(ledger, handle)).workOrder();
        return new Harness(fixture, storage, gate, broker, ledger, order);
    }

    private static Encoded roundTrip(Fixture fixture, ExactRecoveryCheckpoint checkpoint, List<Integer> currentOrder) {
        int sourceSize = fixture.source().size();
        CompoundTag root = ExactRecoveryCheckpointNbtCodec.encode(checkpoint, fixture.source());
        assertEquals(sourceSize, fixture.source().size());
        KeyRegistry current = new KeyRegistry(CURRENT_KEY_GENERATION);
        for (int id : currentOrder)
            current.intern(fixture.keys().get(id));
        try (MockedStatic<AEKey> ignored = decoder(fixture)) {
            PersistenceDecodeResult<ExactRecoveryCheckpoint> result = ExactRecoveryCheckpointNbtCodec.decode(root,
                    current);
            PersistenceDecodeResult.Success<?> success = assertInstanceOf(PersistenceDecodeResult.Success.class, result,
                    "checkpoint decode failed: " + result);
            ExactRecoveryCheckpoint decoded = assertInstanceOf(ExactRecoveryCheckpoint.class, success.value());
            return new Encoded(root, current, decoded);
        }
    }

    private static void assertCheckpointEquivalent(ExactRecoveryCheckpoint expected,
            ExactRecoveryCheckpoint actual, Fixture fixture, KeyRegistry current) {
        assertEquals(expected.plan().planId(), actual.plan().planId());
        assertEquals(expected.plan().request().amount(), actual.plan().request().amount());
        assertEquals(rebase(expected.plan().initialStorageDebits(), fixture, current),
                actual.plan().initialStorageDebits());
        assertEquals(rebase(expected.plan().finalSurplus(), fixture, current), actual.plan().finalSurplus());
        assertEquals(CURRENT_KEY_GENERATION, actual.plan().planningRevision().keyRegistryGeneration());
        assertLedgerEquivalent(expected.ledger(), actual.ledger(), fixture, current);
        assertBrokerEquivalent(expected.broker(), actual.broker(), fixture, current);
        assertEquals(expected.recoveryRequired(), actual.recoveryRequired());
        assertEquals(expected.workOrder().isPresent(), actual.workOrder().isPresent());
        expected.workOrder().ifPresent(value -> assertWorkEquivalent(value, actual.workOrder().orElseThrow(), fixture,
                current));
        assertEquals(expected.failedHandoff().isPresent(), actual.failedHandoff().isPresent());
        expected.failedHandoff().ifPresent(value -> {
            FailedHandoffRecovery other = actual.failedHandoff().orElseThrow();
            assertEquals(value.leaseIdentity(), other.leaseIdentity());
            assertEquals(value.handle(), other.handle());
            assertEquals(value.reservationId(), other.reservationId());
            assertEquals(value.planId(), other.planId());
            assertEquals(rebase(value.reservedDebits(), fixture, current), other.reservedDebits());
        });
    }

    private static void assertLedgerEquivalent(ExactCpuLedgerSnapshot expected, ExactCpuLedgerSnapshot actual,
            Fixture fixture, KeyRegistry current) {
        assertEquals(expected.state(), actual.state());
        assertEquals(expected.lifecycleRevision(), actual.lifecycleRevision());
        assertEquals(expected.handle(), actual.handle());
        assertEquals(expected.reservationId(), actual.reservationId());
        assertEquals(rebase(expected.reservedDebits(), fixture, current), actual.reservedDebits());
        assertEquals(expected.leaseIdentity(), actual.leaseIdentity());
        assertEquals(expected.releaseObligation().isPresent(), actual.releaseObligation().isPresent());
        expected.releaseObligation().ifPresent(value -> {
            ReleaseObligation other = actual.releaseObligation().orElseThrow();
            assertEquals(value.handle(), other.handle());
            assertEquals(value.reservationId(), other.reservationId());
            assertEquals(rebase(value.reservedDebits(), fixture, current), other.reservedDebits());
        });
    }

    private static void assertBrokerEquivalent(ExactTransferBrokerSnapshot expected,
            ExactTransferBrokerSnapshot actual, Fixture fixture, KeyRegistry current) {
        assertEquals(expected.state(), actual.state());
        assertEquals(expected.handle(), actual.handle());
        assertEquals(expected.planId(), actual.planId());
        assertEquals(expected.reservationId(), actual.reservationId());
        assertEquals(expected.workOrderId(), actual.workOrderId());
        assertEquals(expected.leaseIdentity(), actual.leaseIdentity());
        assertEquals(rebase(expected.escrowed(), fixture, current), actual.escrowed());
        assertEquals(expected.transferDiscrepancy().isPresent(), actual.transferDiscrepancy().isPresent());
        expected.transferDiscrepancy().ifPresent(value -> {
            BrokerTransferDiscrepancy other = actual.transferDiscrepancy().orElseThrow();
            assertEquals(value.operation(), other.operation());
            assertEquals(value.reason(), other.reason());
            assertEquals(value.planId(), other.planId());
            assertEquals(value.handle(), other.handle());
            assertEquals(value.reservationId(), other.reservationId());
            assertEquals(current.lookup(fixture.keys().get(value.key().value())), other.key());
            assertEquals(value.requested(), other.requested());
            assertEquals(value.reported(), other.reported());
            assertEquals(rebase(value.knownEscrow(), fixture, current), other.knownEscrow());
        });
    }

    private static void assertWorkEquivalent(ExactWorkOrderSnapshot expected, ExactWorkOrderSnapshot actual,
            Fixture fixture, KeyRegistry current) {
        assertEquals(expected.state(), actual.state());
        assertEquals(expected.planId(), actual.planId());
        assertEquals(expected.handle(), actual.handle());
        assertEquals(expected.reservationId(), actual.reservationId());
        assertEquals(expected.leaseIdentity(), actual.leaseIdentity());
        assertEquals(expected.workOrderId(), actual.workOrderId());
        assertEquals(rebase(expected.custody(), fixture, current), actual.custody());
        assertEquals(expected.causalStepIndex(), actual.causalStepIndex());
        assertEquals(expected.remainingExecutions(), actual.remainingExecutions());
        assertEquals(expected.selectionRemaining(), actual.selectionRemaining());
        assertEquals(expected.cycleRemainingRepetitions(), actual.cycleRemainingRepetitions());
        assertEquals(expected.cycleMemberIndex(), actual.cycleMemberIndex());
        assertEquals(expected.cycleMemberRemainingExecutions(), actual.cycleMemberRemainingExecutions());
        assertEquals(expected.cycleSelectionRemaining(), actual.cycleSelectionRemaining());
        assertEquals(expected.releaseMode(), actual.releaseMode());
        assertEquals(expected.nextGeneration(), actual.nextGeneration());
        assertEquals(expected.generationExhausted(), actual.generationExhausted());
        assertEquals(expected.outstandingCommand().isPresent(), actual.outstandingCommand().isPresent());
        assertEquals(expected.inFlightCommand().isPresent(), actual.inFlightCommand().isPresent());
        expected.outstandingCommand().ifPresent(value -> assertCommandEquivalent(value,
                actual.outstandingCommand().orElseThrow(), fixture, current));
        expected.inFlightCommand().ifPresent(value -> assertCommandEquivalent(value,
                actual.inFlightCommand().orElseThrow(), fixture, current));
        assertEquals(expected.discrepancy().isPresent(), actual.discrepancy().isPresent());
        assertEquals(expected.completedEvidence().isPresent(), actual.completedEvidence().isPresent());
        assertEquals(expected.completedEvidenceProgressApplied(), actual.completedEvidenceProgressApplied());
    }

    private static void assertCommandEquivalent(ExactWorkCommand expected, ExactWorkCommand actual,
            Fixture fixture, KeyRegistry current) {
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.handle(), actual.handle());
        assertEquals(expected.reservationId(), actual.reservationId());
        assertEquals(expected.batchId(), actual.batchId());
        assertEquals(expected.location(), actual.location());
        assertEquals(expected.pattern().id(), actual.pattern().id());
        assertEquals(expected.pattern().revision(), actual.pattern().revision());
        assertEquals(CURRENT_KEY_GENERATION, actual.pattern().keyRegistryGeneration());
        assertEquals(expected.executionWindow(), actual.executionWindow());
        assertEquals(rebase(expected.custodyInputs(), fixture, current), actual.custodyInputs());
        assertEquals(rebase(expected.expectedOutputs(), fixture, current), actual.expectedOutputs());
        assertEquals(rebase(expected.expectedRemainders(), fixture, current), actual.expectedRemainders());
        assertEquals(expected.expectedOutputSlots().size(), actual.expectedOutputSlots().size());
        for (int i = 0; i < expected.expectedOutputSlots().size(); i++) {
            ExactWorkOutput e = expected.expectedOutputSlots().get(i);
            ExactWorkOutput a = actual.expectedOutputSlots().get(i);
            assertEquals(e.outputIndex(), a.outputIndex());
            assertEquals(current.lookup(fixture.keys().get(e.key().value())), a.key());
            assertEquals(e.amount(), a.amount());
        }
    }

    private static Map<KeyId, AEAmount> rebase(Map<KeyId, AEAmount> values, Fixture fixture, KeyRegistry current) {
        TreeMap<KeyId, AEAmount> result = new TreeMap<>(KEY_ORDER);
        values.forEach((key, value) -> result.put(current.lookup(fixture.keys().get(key.value())), value));
        return Map.copyOf(result);
    }

    private static void assertMalformedWithoutMutation(Fixture fixture, CompoundTag forged) {
        KeyRegistry current = new KeyRegistry(CURRENT_KEY_GENERATION);
        try (MockedStatic<AEKey> ignored = decoder(fixture)) {
            assertFailure(ExactRecoveryCheckpointNbtCodec.decode(forged, current),
                    PersistenceDecodeResult.Reason.MALFORMED);
        }
        assertEquals(0, current.size());
    }

    private static void assertReleaseModeConflict(Fixture fixture, ExactRecoveryCheckpoint checkpoint) {
        CompoundTag forged = ExactRecoveryCheckpointNbtCodec.encode(checkpoint, fixture.source());
        CompoundTag option = new CompoundTag();
        option.putByte("p", (byte) 1);
        CompoundTag mode = new CompoundTag();
        mode.putString("v", ExactWorkOrderReleaseMode.SETTLEMENT.name());
        option.put("v", mode);
        forged.getCompound("w").getCompound("v").put("o", option);
        assertMalformedWithoutMutation(fixture, forged);
    }

    private static void setListElementType(ListTag list, byte type) {
        try {
            Field field = ListTag.class.getDeclaredField("type");
            field.setAccessible(true);
            field.setByte(list, type);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("ListTag test fixture could not set its element type", failure);
        }
    }

    private static void assertFailure(PersistenceDecodeResult<?> result, PersistenceDecodeResult.Reason reason) {
        PersistenceDecodeResult.Failure<?> failure = assertInstanceOf(PersistenceDecodeResult.Failure.class, result);
        assertEquals(reason, failure.reason());
    }

    private static void swapOuterKeys(CompoundTag root) {
        ListTag entries = root.getCompound("k").getList("e", Tag.TAG_COMPOUND);
        Tag first = entries.get(0).copy();
        entries.set(0, entries.get(1).copy());
        entries.set(1, first);
    }

    private static void removeUnexpectedKey(CompoundTag root) {
        ListTag entries = root.getCompound("k").getList("e", Tag.TAG_COMPOUND);
        entries.remove(entries.size() - 1);
    }

    private static void addUnreferencedKey(CompoundTag root) {
        CompoundTag entry = new CompoundTag();
        entry.putInt("i", 2);
        CompoundTag identity = new CompoundTag();
        identity.putString("#c", "checkpoint:key:2");
        entry.put("k", identity);
        root.getCompound("k").getList("e", Tag.TAG_COMPOUND).add(entry);
    }

    private static MockedStatic<AEKey> decoder(Fixture fixture) {
        Map<String, AEKey> byIdentity = new HashMap<>();
        fixture.keys().forEach((id, key) -> byIdentity.put("checkpoint:key:" + id, key));
        MockedStatic<AEKey> decoder = mockStatic(AEKey.class);
        decoder.when(() -> AEKey.fromTagGeneric(any(CompoundTag.class))).thenAnswer(call -> {
            CompoundTag tag = call.getArgument(0, CompoundTag.class);
            AEKey key = byIdentity.get(tag.getString("#c"));
            if (key == null)
                throw new IllegalArgumentException("unknown checkpoint test key " + tag);
            return key;
        });
        return decoder;
    }

    private static Fixture cycleFixture(AEAmount demand, boolean secondaryOutput) {
        Map<Integer, AEKey> keys = keys();
        CompiledPattern self = new CompiledPattern(new PatternId("checkpoint-cycle"), PatternKind.CRAFTING,
                List.of(new CompiledInputSpec(
                        List.of(new CompiledCandidateSpec(SEED, AEAmount.ONE, Optional.empty())), AEAmount.ONE,
                        SubstitutionPolicy.EXACT)),
                outputs(secondaryOutput), Optional.empty(), new PatternRevision(0L), KEY_GENERATION);
        StorageSnapshot storage = snapshot(3, Map.of(0, AEAmount.ONE));
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(self), Map.of(self.id(), 10));
        ExactCraftPlanDraft draft = assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(new ExactCraftRequest(SEED, demand), storage, patterns)).draft();
        ExactCraftingPlan plan = assertInstanceOf(ExactPlanValidationResult.Success.class,
                VALIDATOR.validate(draft, storage, patterns)).plan();
        return new Fixture(plan, newRegistry(keys), keys, patterns, storage);
    }

    private static Fixture zeroInputFixture() {
        Map<Integer, AEKey> keys = keys();
        CompiledPattern producer = new CompiledPattern(new PatternId("checkpoint-zero-input"), PatternKind.CRAFTING,
                List.of(), List.of(new CompiledOutputSpec(SEED, AEAmount.ONE, true)), Optional.empty(),
                new PatternRevision(0L), KEY_GENERATION);
        StorageSnapshot storage = snapshot(3, Map.of());
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(producer), Map.of(producer.id(), 10));
        ExactCraftPlanDraft draft = assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(new ExactCraftRequest(SEED, AEAmount.ONE), storage, patterns)).draft();
        ExactCraftingPlan plan = assertInstanceOf(ExactPlanValidationResult.Success.class,
                VALIDATOR.validate(draft, storage, patterns)).plan();
        return new Fixture(plan, newRegistry(keys), keys, patterns, storage);
    }

    private static Fixture candidateFixture() {
        Map<Integer, AEKey> keys = keys();
        CompiledPattern pattern = new CompiledPattern(new PatternId("checkpoint-candidates"), PatternKind.CRAFTING,
                List.of(new CompiledInputSpec(List.of(
                        new CompiledCandidateSpec(new KeyId(0), AEAmount.ONE, Optional.empty()),
                        new CompiledCandidateSpec(new KeyId(1), AEAmount.ONE, Optional.empty())), AEAmount.ONE,
                        SubstitutionPolicy.ALLOW_ALTERNATIVES)),
                List.of(new CompiledOutputSpec(SECONDARY, AEAmount.ONE, true)), Optional.empty(),
                new PatternRevision(0L), KEY_GENERATION);
        StorageSnapshot storage = snapshot(3, Map.of(0, AEAmount.ONE, 1, AEAmount.ONE));
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(pattern), Map.of(pattern.id(), 10));
        ExactCraftPlanDraft draft = assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(new ExactCraftRequest(SECONDARY, AEAmount.ONE), storage, patterns)).draft();
        ExactCraftingPlan plan = assertInstanceOf(ExactPlanValidationResult.Success.class,
                VALIDATOR.validate(draft, storage, patterns)).plan();
        return new Fixture(plan, newRegistry(keys), keys, patterns, storage);
    }

    private static List<CompiledOutputSpec> outputs(boolean secondaryOutput) {
        if (!secondaryOutput)
            return List.of(new CompiledOutputSpec(SEED, AEAmount.of(2L), true));
        return List.of(new CompiledOutputSpec(SEED, AEAmount.of(2L), true),
                new CompiledOutputSpec(SECONDARY, AEAmount.ONE, false));
    }

    private static Map<Integer, AEKey> keys() {
        Map<Integer, AEKey> keys = new HashMap<>();
        for (int id = 0; id < 3; id++) {
            AEKey key = mock(AEKey.class);
            when(key.toTagGeneric()).thenReturn(identity("checkpoint:key:" + id));
            keys.put(id, key);
        }
        return Map.copyOf(keys);
    }

    private static CompoundTag identity(String value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("#c", value);
        return tag;
    }

    private static KeyRegistry newRegistry(Map<Integer, AEKey> keys) {
        KeyRegistry registry = new KeyRegistry(KEY_GENERATION);
        for (int id = 0; id < 3; id++)
            assertEquals(new KeyId(id), registry.intern(keys.get(id)));
        return registry;
    }

    private static NormalizedPatternSnapshot patternSnapshot(List<CompiledPattern> patterns,
            Map<PatternId, Integer> priorities) {
        CompiledPatternGraph graph = assertInstanceOf(GraphBuildResult.Success.class,
                new CompiledPatternGraphBuilder(GRAPH_GENERATION, KEY_GENERATION).build(patterns)).graph();
        return new NormalizedPatternSnapshot(SERVER_GENERATION, RECIPE_REVISION, KEY_GENERATION, graph.patternsById(),
                graph, priorities, patterns.size(), false, List.of());
    }

    private static StorageSnapshot snapshot(int keyCount, Map<Integer, AEAmount> values) {
        AmountVector amounts = new AmountVector(keyCount);
        long[] revisions = new long[keyCount];
        values.forEach(amounts::set);
        return new StorageSnapshot(KEY_GENERATION, StorageRevision.ZERO, keyCount, amounts, revisions);
    }

    private record Fixture(ExactCraftingPlan plan, KeyRegistry source, Map<Integer, AEKey> keys,
            NormalizedPatternSnapshot patterns, StorageSnapshot storage) {
    }

    private record Harness(Fixture fixture, CountingStorage storage, AtomicGate gate, ExactTransferBroker broker,
            ExactCpuLedger ledger, ExactWorkOrder order) {
    }

    private record Encoded(CompoundTag root, KeyRegistry current, ExactRecoveryCheckpoint checkpoint) {
    }

    @FunctionalInterface
    private interface Mutation {
        void apply(CompoundTag root);
    }

    private static final class AtomicGate implements ServerThreadGate {
        private int calls;

        @Override
        public boolean isServerThread() {
            calls++;
            return true;
        }
    }

    private static final class CountingStorage implements BrokerExactStorage {
        private final StorageSnapshot dependencies;
        private final TreeMap<KeyId, AEAmount> physical = new TreeMap<>(KEY_ORDER);
        private final boolean failInsert;
        private int transferCalls;

        private CountingStorage(StorageSnapshot dependencies, Map<KeyId, AEAmount> initial, boolean failInsert) {
            this.dependencies = dependencies;
            this.physical.putAll(initial);
            this.failInsert = failInsert;
        }

        @Override
        public AEAmount insert(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
            transferCalls++;
            if (mode == Actionable.SIMULATE)
                return amount;
            if (failInsert)
                throw new IllegalStateException("test release transient failure");
            physical.merge(key, amount, AEAmount::add);
            return amount;
        }

        @Override
        public AEAmount extract(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
            transferCalls++;
            AEAmount extracted = physical.getOrDefault(key, AEAmount.ZERO).min(amount);
            if (mode == Actionable.MODULATE && !extracted.equals(AEAmount.ZERO)) {
                AEAmount remainder = physical.get(key).subtractExact(extracted);
                if (remainder.equals(AEAmount.ZERO))
                    physical.remove(key);
                else
                    physical.put(key, remainder);
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

        private int transferCalls() {
            return transferCalls;
        }
    }
}

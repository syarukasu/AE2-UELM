package appeng.rebuild.execution;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.CompiledCandidateSpec;
import appeng.rebuild.pattern.CompiledInputSpec;
import appeng.rebuild.pattern.CompiledOutputSpec;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternKind;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.pattern.SubstitutionPolicy;
import appeng.rebuild.planner.PlannedBatchId;
import appeng.rebuild.planner.PlannedInputSelection;
import appeng.rebuild.planner.PlannedRemainderReturn;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/** Schema and ownership-boundary tests for one bounded physical work command. */
class ExactWorkCommandSchemaTest {
    private static final ExactPlanId PLAN_ID = new ExactPlanId(new UUID(1L, 2L));
    private static final UUID LEASE_ID = new UUID(3L, 4L);
    private static final WorkOrderId WORK_ORDER_ID = new WorkOrderId(new UUID(5L, 6L));
    private static final CpuPlanHandle HANDLE = new CpuPlanHandle(new UUID(7L, 8L), 9L);
    private static final ReservationId RESERVATION_ID = new ReservationId(new UUID(10L, 11L));
    private static final PlannedBatchId BATCH_ID = new PlannedBatchId(12L);

    private static final KeyId INPUT_A = new KeyId(10);
    private static final KeyId INPUT_B = new KeyId(12);
    private static final KeyId INPUT_C = new KeyId(13);
    private static final KeyId REMAINDER = new KeyId(11);
    private static final KeyId OUTPUT = new KeyId(20);
    private static final KeyId OTHER = new KeyId(21);

    private static final Fixture FIXTURE = fixture();

    @Test
    void validCommandRetainsEveryIdentitySelectionSlotAndAggregate() {
        ExactWorkCommand command = validCommand();

        assertEquals(new WorkCommandId(PLAN_ID, LEASE_ID, WORK_ORDER_ID, 19L), command.id());
        assertEquals(HANDLE, command.handle());
        assertEquals(RESERVATION_ID, command.reservationId());
        assertEquals(BATCH_ID, command.batchId());
        assertEquals(FIXTURE.pattern(), command.pattern());
        assertEquals(3L, command.executionWindow());
        assertEquals(FIXTURE.selections(), command.plannedSelections());
        assertEquals(Map.of(INPUT_A, AEAmount.of(4L), INPUT_B, AEAmount.of(3L), INPUT_C, AEAmount.of(15L)),
                command.custodyInputs());
        assertEquals(List.of(new ExactWorkOutput(0, OUTPUT, AEAmount.of(12L)),
                new ExactWorkOutput(1, OUTPUT, AEAmount.of(3L))), command.expectedOutputSlots());
        assertEquals(Map.of(OUTPUT, AEAmount.of(15L)), command.expectedOutputs());
        assertEquals(Map.of(REMAINDER, AEAmount.of(4L)), command.expectedRemainders());
    }

    @Test
    void commandCopiesInputsAndExposesDeterministicImmutableCollections() {
        List<PlannedInputSelection> selections = new ArrayList<>(FIXTURE.selections());
        Map<KeyId, AEAmount> custody = reverseMap(FIXTURE.custody());
        List<ExactWorkOutput> slots = new ArrayList<>(FIXTURE.slots());
        Map<KeyId, AEAmount> outputs = reverseMap(FIXTURE.outputs());
        Map<KeyId, AEAmount> remainders = reverseMap(FIXTURE.remainders());

        ExactWorkCommand command = command(selections, custody, slots, outputs, remainders);
        selections.clear();
        custody.clear();
        slots.clear();
        outputs.clear();
        remainders.clear();

        assertEquals(FIXTURE.selections(), command.plannedSelections());
        assertEquals(List.of(INPUT_A, INPUT_B, INPUT_C), List.copyOf(command.custodyInputs().keySet()));
        assertEquals(List.of(OUTPUT), List.copyOf(command.expectedOutputs().keySet()));
        assertEquals(List.of(REMAINDER), List.copyOf(command.expectedRemainders().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> command.plannedSelections().clear());
        assertThrows(UnsupportedOperationException.class, () -> command.custodyInputs().clear());
        assertThrows(UnsupportedOperationException.class, () -> command.expectedOutputSlots().clear());
        assertThrows(UnsupportedOperationException.class, () -> command.expectedOutputs().clear());
        assertThrows(UnsupportedOperationException.class, () -> command.expectedRemainders().clear());
    }

    @Test
    void oversizedCallerCollectionsAreRejectedBeforeIterationOrCopy() {
        List<PlannedInputSelection> oversizedSelections = new AbstractList<>() {
            @Override
            public PlannedInputSelection get(int index) {
                throw new AssertionError("oversized list must be rejected before get");
            }

            @Override
            public int size() {
                return PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS + 1;
            }
        };
        Map<KeyId, AEAmount> oversizedMap = new AbstractMap<>() {
            @Override
            public Set<Entry<KeyId, AEAmount>> entrySet() {
                throw new AssertionError("oversized map must be rejected before entrySet");
            }

            @Override
            public int size() {
                return PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS + 1;
            }
        };

        assertThrows(IllegalArgumentException.class,
                () -> command(oversizedSelections, FIXTURE.custody(), FIXTURE.slots(), FIXTURE.outputs(),
                        FIXTURE.remainders()));
        assertThrows(IllegalArgumentException.class,
                () -> command(FIXTURE.selections(), oversizedMap, FIXTURE.slots(), FIXTURE.outputs(),
                        FIXTURE.remainders()));
    }

    @Test
    void malformedSelectionIndicesOrderCandidateKeyGrossInitialAndRemainderAreRejected() {
        PlannedInputSelection first = FIXTURE.selections().get(0);
        PlannedInputSelection second = FIXTURE.selections().get(1);
        PlannedInputSelection third = FIXTURE.selections().get(2);

        assertInvalid(List.of(new PlannedInputSelection(2, first.candidateIndex(), first.templateUnits(),
                first.consumedKey(), first.grossConsumedAmount(), first.initialRequiredAmount(),
                first.remainderReturn()),
                second, third));
        assertInvalid(List.of(second, first, third));
        assertInvalid(List.of(new PlannedInputSelection(first.inputIndex(), 2, first.templateUnits(),
                first.consumedKey(), first.grossConsumedAmount(), first.initialRequiredAmount(),
                first.remainderReturn()),
                second, third));
        assertInvalid(List.of(
                new PlannedInputSelection(first.inputIndex(), first.candidateIndex(), first.templateUnits(),
                        OTHER, first.grossConsumedAmount(), first.initialRequiredAmount(), first.remainderReturn()),
                second, third));
        assertInvalid(List.of(
                new PlannedInputSelection(first.inputIndex(), first.candidateIndex(), first.templateUnits(),
                        first.consumedKey(), AEAmount.of(5L), first.initialRequiredAmount(), first.remainderReturn()),
                second,
                third));
        assertInvalid(List.of(
                new PlannedInputSelection(first.inputIndex(), first.candidateIndex(), first.templateUnits(),
                        first.consumedKey(), first.grossConsumedAmount(), AEAmount.of(3L), first.remainderReturn()),
                second, third));
        assertInvalid(
                List.of(new PlannedInputSelection(first.inputIndex(), first.candidateIndex(), first.templateUnits(),
                        first.consumedKey(), first.grossConsumedAmount(), first.initialRequiredAmount(),
                        Optional.of(new PlannedRemainderReturn(REMAINDER, AEAmount.of(3L)))), second, third));
    }

    @Test
    void malformedInputAndRemainderAggregatesAreRejected() {
        Map<KeyId, AEAmount> wrongCustody = new LinkedHashMap<>(FIXTURE.custody());
        wrongCustody.put(INPUT_A, AEAmount.of(5L));
        Map<KeyId, AEAmount> wrongRemainders = Map.of(REMAINDER, AEAmount.of(5L));

        assertInvalid(FIXTURE.selections(), wrongCustody, FIXTURE.slots(), FIXTURE.outputs(), FIXTURE.remainders());
        assertInvalid(FIXTURE.selections(), FIXTURE.custody(), FIXTURE.slots(), FIXTURE.outputs(), wrongRemainders);
    }

    @Test
    void malformedOutputSlotIndexKeyAmountAndAggregateAreRejected() {
        List<ExactWorkOutput> wrongIndex = List.of(new ExactWorkOutput(1, OUTPUT, AEAmount.of(12L)),
                new ExactWorkOutput(1, OUTPUT, AEAmount.of(3L)));
        List<ExactWorkOutput> wrongKey = List.of(new ExactWorkOutput(0, OUTPUT, AEAmount.of(12L)),
                new ExactWorkOutput(1, OTHER, AEAmount.of(3L)));
        List<ExactWorkOutput> wrongAmount = List.of(new ExactWorkOutput(0, OUTPUT, AEAmount.of(11L)),
                new ExactWorkOutput(1, OUTPUT, AEAmount.of(3L)));

        assertInvalid(FIXTURE.selections(), FIXTURE.custody(), wrongIndex, FIXTURE.outputs(), FIXTURE.remainders());
        assertInvalid(FIXTURE.selections(), FIXTURE.custody(), wrongKey, FIXTURE.outputs(), FIXTURE.remainders());
        assertInvalid(FIXTURE.selections(), FIXTURE.custody(), wrongAmount, FIXTURE.outputs(), FIXTURE.remainders());
        assertInvalid(FIXTURE.selections(), FIXTURE.custody(), FIXTURE.slots(), Map.of(OUTPUT, AEAmount.of(14L)),
                FIXTURE.remainders());
    }

    @Test
    void signedLongMaximumIsPhysicalButLongMaximumPlusOneIsNot() {
        AEAmount longMaximum = AEAmount.of(Long.MAX_VALUE);
        AEAmount justOverLong = AEAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));

        assertDoesNotThrow(() -> commandWithOutputAmount(longMaximum));
        assertDoesNotThrow(() -> new ExactWorkOutput(0, OUTPUT, justOverLong));
        assertThrows(IllegalArgumentException.class, () -> commandWithOutputAmount(justOverLong));
    }

    @Test
    void nullsAreRejectedAtEveryCommandAndSlotBoundary() {
        assertThrows(NullPointerException.class, () -> new ExactWorkCommand(null, HANDLE, RESERVATION_ID, BATCH_ID,
                FIXTURE.pattern(), 3L, FIXTURE.selections(), FIXTURE.custody(), FIXTURE.slots(), FIXTURE.outputs(),
                FIXTURE.remainders()));
        assertThrows(NullPointerException.class, () -> new ExactWorkCommand(new WorkCommandId(PLAN_ID, LEASE_ID,
                WORK_ORDER_ID, 19L), null, RESERVATION_ID, BATCH_ID, FIXTURE.pattern(), 3L, FIXTURE.selections(),
                FIXTURE.custody(), FIXTURE.slots(), FIXTURE.outputs(), FIXTURE.remainders()));
        assertThrows(NullPointerException.class, () -> new ExactWorkCommand(new WorkCommandId(PLAN_ID, LEASE_ID,
                WORK_ORDER_ID, 19L), HANDLE, null, BATCH_ID, FIXTURE.pattern(), 3L, FIXTURE.selections(),
                FIXTURE.custody(), FIXTURE.slots(), FIXTURE.outputs(), FIXTURE.remainders()));
        assertThrows(NullPointerException.class, () -> new ExactWorkCommand(new WorkCommandId(PLAN_ID, LEASE_ID,
                WORK_ORDER_ID, 19L), HANDLE, RESERVATION_ID, null, FIXTURE.pattern(), 3L, FIXTURE.selections(),
                FIXTURE.custody(), FIXTURE.slots(), FIXTURE.outputs(), FIXTURE.remainders()));
        assertThrows(NullPointerException.class, () -> command(null, FIXTURE.custody(), FIXTURE.slots(),
                FIXTURE.outputs(), FIXTURE.remainders(), FIXTURE.pattern()));
        assertThrows(NullPointerException.class, () -> command(FIXTURE.selections(), null, FIXTURE.slots(),
                FIXTURE.outputs(), FIXTURE.remainders()));
        assertThrows(NullPointerException.class, () -> command(FIXTURE.selections(), FIXTURE.custody(), null,
                FIXTURE.outputs(), FIXTURE.remainders()));
        assertThrows(NullPointerException.class, () -> command(FIXTURE.selections(), FIXTURE.custody(), FIXTURE.slots(),
                null, FIXTURE.remainders()));
        assertThrows(NullPointerException.class, () -> command(FIXTURE.selections(), FIXTURE.custody(), FIXTURE.slots(),
                FIXTURE.outputs(), null));

        List<ExactWorkOutput> nullSlot = new ArrayList<>(FIXTURE.slots());
        nullSlot.set(0, null);
        assertThrows(NullPointerException.class,
                () -> command(FIXTURE.selections(), FIXTURE.custody(), nullSlot, FIXTURE.outputs(),
                        FIXTURE.remainders()));
        assertThrows(NullPointerException.class, () -> new ExactWorkOutput(0, null, AEAmount.ONE));
        assertThrows(NullPointerException.class, () -> new ExactWorkOutput(0, OUTPUT, null));
    }

    @Test
    void discrepancyBindsExpectedMapsRequiresMismatchAndRetainsExactActualOverflow() {
        ExactWorkCommand command = validCommand();
        AEAmount actualBeyondLong = AEAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));

        assertThrows(IllegalArgumentException.class,
                () -> new ExactWorkDiscrepancy(command, Map.of(OUTPUT, AEAmount.of(14L)),
                        command.expectedOutputs(), command.expectedRemainders(), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ExactWorkDiscrepancy(command, command.expectedOutputs(), Map.of(OUTPUT, AEAmount.of(14L)),
                        Map.of(REMAINDER, AEAmount.of(5L)), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ExactWorkDiscrepancy(command, command.expectedOutputs(), command.expectedOutputs(),
                        command.expectedRemainders(), command.expectedRemainders()));

        ExactWorkDiscrepancy discrepancy = new ExactWorkDiscrepancy(command, command.expectedOutputs(),
                Map.of(OUTPUT, actualBeyondLong), command.expectedRemainders(), Map.of());
        assertEquals(actualBeyondLong, discrepancy.actualOutputs().get(OUTPUT));
        assertEquals(Map.of(OUTPUT, actualBeyondLong), discrepancy.actualOutputs());
        assertThrows(UnsupportedOperationException.class, () -> discrepancy.actualOutputs().clear());
    }

    @Test
    void executorEvidenceIsPackagePrivateAndWorkOrderHasNoPublicAcknowledgementSurface() {
        for (Class<?> evidence : List.of(WorkCommandAcceptance.class, WorkCommandCompletion.class,
                WorkCommandRejection.class)) {
            assertFalse(Modifier.isPublic(evidence.getModifiers()));
            assertEquals(0, evidence.getConstructors().length);
            Arrays.stream(evidence.getDeclaredConstructors())
                    .forEach(constructor -> assertFalse(Modifier.isPublic(constructor.getModifiers())));
        }

        Set<String> publicMethods = Arrays.stream(ExactWorkOrder.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> method.getName())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("snapshot"), publicMethods);
        assertTrue(Arrays.stream(ExactWorkOrder.class.getDeclaredMethods())
                .filter(method -> Set.of("issueNext", "accept", "reject", "complete").contains(method.getName()))
                .allMatch(method -> !Modifier.isPublic(method.getModifiers())));
    }

    private static void assertInvalid(List<PlannedInputSelection> selections) {
        assertInvalid(selections, FIXTURE.custody(), FIXTURE.slots(), FIXTURE.outputs(), FIXTURE.remainders());
    }

    private static void assertInvalid(List<PlannedInputSelection> selections, Map<KeyId, AEAmount> custody,
            List<ExactWorkOutput> slots, Map<KeyId, AEAmount> outputs, Map<KeyId, AEAmount> remainders) {
        assertThrows(IllegalArgumentException.class, () -> command(selections, custody, slots, outputs, remainders));
    }

    private static ExactWorkCommand validCommand() {
        return command(FIXTURE.selections(), FIXTURE.custody(), FIXTURE.slots(), FIXTURE.outputs(),
                FIXTURE.remainders());
    }

    private static ExactWorkCommand command(List<PlannedInputSelection> selections, Map<KeyId, AEAmount> custody,
            List<ExactWorkOutput> slots, Map<KeyId, AEAmount> outputs, Map<KeyId, AEAmount> remainders) {
        return command(selections, custody, slots, outputs, remainders, FIXTURE.pattern());
    }

    private static ExactWorkCommand command(List<PlannedInputSelection> selections, Map<KeyId, AEAmount> custody,
            List<ExactWorkOutput> slots, Map<KeyId, AEAmount> outputs, Map<KeyId, AEAmount> remainders,
            CompiledPattern pattern) {
        return new ExactWorkCommand(new WorkCommandId(PLAN_ID, LEASE_ID, WORK_ORDER_ID, 19L), HANDLE,
                RESERVATION_ID, BATCH_ID, pattern, 3L, selections, custody, slots, outputs, remainders);
    }

    private static ExactWorkCommand commandWithOutputAmount(AEAmount outputAmount) {
        CompiledPattern pattern = new CompiledPattern(new PatternId("physical-window"), PatternKind.CRAFTING,
                List.of(new CompiledInputSpec(
                        List.of(new CompiledCandidateSpec(INPUT_A, AEAmount.ONE, Optional.empty())),
                        AEAmount.ONE, SubstitutionPolicy.EXACT)),
                List.of(new CompiledOutputSpec(OUTPUT, outputAmount, true)), Optional.empty(), new PatternRevision(1L),
                1L);
        PlannedInputSelection selection = new PlannedInputSelection(0, 0, AEAmount.ONE, INPUT_A, AEAmount.ONE,
                AEAmount.ONE, Optional.empty());
        return new ExactWorkCommand(new WorkCommandId(PLAN_ID, LEASE_ID, WORK_ORDER_ID, 19L), HANDLE,
                RESERVATION_ID, BATCH_ID, pattern, 1L, List.of(selection), Map.of(INPUT_A, AEAmount.ONE),
                List.of(new ExactWorkOutput(0, OUTPUT, outputAmount)), Map.of(OUTPUT, outputAmount), Map.of());
    }

    private static Fixture fixture() {
        CompiledCandidateSpec first = new CompiledCandidateSpec(INPUT_A, AEAmount.of(2L),
                Optional.of(new appeng.rebuild.pattern.CompiledRemainderSpec(REMAINDER, AEAmount.ONE)));
        CompiledCandidateSpec second = new CompiledCandidateSpec(INPUT_B, AEAmount.of(3L),
                Optional.of(new appeng.rebuild.pattern.CompiledRemainderSpec(REMAINDER, AEAmount.of(2L))));
        CompiledCandidateSpec third = new CompiledCandidateSpec(INPUT_C, AEAmount.of(5L), Optional.empty());
        CompiledPattern pattern = new CompiledPattern(new PatternId("command-schema"), PatternKind.CRAFTING,
                List.of(new CompiledInputSpec(List.of(first, second), AEAmount.ONE,
                        SubstitutionPolicy.ALLOW_ALTERNATIVES),
                        new CompiledInputSpec(List.of(third), AEAmount.ONE, SubstitutionPolicy.EXACT)),
                List.of(new CompiledOutputSpec(OUTPUT, AEAmount.of(4L), true),
                        new CompiledOutputSpec(OUTPUT, AEAmount.ONE, false)),
                Optional.empty(), new PatternRevision(7L),
                8L);
        List<PlannedInputSelection> selections = List.of(
                new PlannedInputSelection(0, 0, AEAmount.of(2L), INPUT_A, AEAmount.of(4L), AEAmount.of(4L),
                        Optional.of(new PlannedRemainderReturn(REMAINDER, AEAmount.of(2L)))),
                new PlannedInputSelection(0, 1, AEAmount.ONE, INPUT_B, AEAmount.of(3L), AEAmount.of(3L),
                        Optional.of(new PlannedRemainderReturn(REMAINDER, AEAmount.of(2L)))),
                new PlannedInputSelection(1, 0, AEAmount.of(3L), INPUT_C, AEAmount.of(15L), AEAmount.of(15L),
                        Optional.empty()));
        Map<KeyId, AEAmount> custody = new LinkedHashMap<>();
        custody.put(INPUT_C, AEAmount.of(15L));
        custody.put(INPUT_B, AEAmount.of(3L));
        custody.put(INPUT_A, AEAmount.of(4L));
        List<ExactWorkOutput> slots = List.of(new ExactWorkOutput(0, OUTPUT, AEAmount.of(12L)),
                new ExactWorkOutput(1, OUTPUT, AEAmount.of(3L)));
        return new Fixture(pattern, selections, custody, slots, Map.of(OUTPUT, AEAmount.of(15L)),
                Map.of(REMAINDER, AEAmount.of(4L)));
    }

    private static Map<KeyId, AEAmount> reverseMap(Map<KeyId, AEAmount> source) {
        ArrayList<Map.Entry<KeyId, AEAmount>> entries = new ArrayList<>(source.entrySet());
        Collections.reverse(entries);
        LinkedHashMap<KeyId, AEAmount> result = new LinkedHashMap<>();
        for (Map.Entry<KeyId, AEAmount> entry : entries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private record Fixture(CompiledPattern pattern, List<PlannedInputSelection> selections,
            Map<KeyId, AEAmount> custody, List<ExactWorkOutput> slots, Map<KeyId, AEAmount> outputs,
            Map<KeyId, AEAmount> remainders) {
    }
}

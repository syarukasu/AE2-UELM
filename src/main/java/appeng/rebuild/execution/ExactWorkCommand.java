package appeng.rebuild.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.CompiledCandidateSpec;
import appeng.rebuild.pattern.CompiledInputSpec;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.planner.PlannedBatchId;
import appeng.rebuild.planner.PlannedInputSelection;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/**
 * Immutable, sealed, one-window physical execution instruction.
 *
 * <p>
 * It retains the compiled pattern rather than a registry lookup key, so recipe reload cannot alter a command already
 * handed to an executor. This is data, not acknowledgement authority.
 */
public record ExactWorkCommand(WorkCommandId id, CpuPlanHandle handle, ReservationId reservationId,
        PlannedBatchId batchId, ExactWorkCommandLocation location, CompiledPattern pattern, long executionWindow,
        List<PlannedInputSelection> plannedSelections, Map<KeyId, AEAmount> custodyInputs,
        List<ExactWorkOutput> expectedOutputSlots, Map<KeyId, AEAmount> expectedOutputs,
        Map<KeyId, AEAmount> expectedRemainders) {
    public ExactWorkCommand {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(pattern, "pattern");
        if (executionWindow <= 0) {
            throw new IllegalArgumentException("executionWindow must be positive");
        }
        if (AEAmount.of(executionWindow).compareTo(location.remainingMemberExecutions()) > 0) {
            throw new IllegalArgumentException("executionWindow exceeds its exact causal member progress");
        }
        Objects.requireNonNull(plannedSelections, "plannedSelections");
        if (plannedSelections.size() > PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS) {
            throw new IllegalArgumentException("Too many planned selections in one command");
        }
        plannedSelections = List.copyOf(plannedSelections);
        validateSelections(pattern, executionWindow, plannedSelections);
        custodyInputs = copyPhysicalAmounts(custodyInputs, "custodyInputs");
        expectedOutputSlots = copyOutputSlots(expectedOutputSlots, pattern, executionWindow);
        expectedOutputs = copyPhysicalAmounts(expectedOutputs, "expectedOutputs");
        expectedRemainders = copyPhysicalAmounts(expectedRemainders, "expectedRemainders");
        if (!aggregate(expectedOutputSlots).equals(expectedOutputs)) {
            throw new IllegalArgumentException("Expected output aggregate must preserve every output slot");
        }
        if (!aggregateInputs(plannedSelections).equals(custodyInputs)
                || !aggregateRemainders(plannedSelections).equals(expectedRemainders)) {
            throw new IllegalArgumentException("Command custody and remainders must be derived from its selections");
        }
    }

    static AEAmount requirePositiveBounded(AEAmount amount, String name) {
        amount = Objects.requireNonNull(amount, name);
        if (amount.equals(AEAmount.ZERO)
                || amount.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
            throw new IllegalArgumentException(name + " must be positive and within the exact planning bound");
        }
        return amount;
    }

    static Map<KeyId, AEAmount> copyAmounts(Map<KeyId, AEAmount> source, String name) {
        Objects.requireNonNull(source, name);
        if (source.size() > PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS) {
            throw new IllegalArgumentException(name + " exceeds the exact key bound");
        }
        TreeMap<KeyId, AEAmount> copy = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (Map.Entry<KeyId, AEAmount> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), name + " key"),
                    requirePositiveBounded(entry.getValue(), name + " amount"));
        }
        return Collections.unmodifiableMap(copy);
    }

    static Map<KeyId, AEAmount> copyPhysicalAmounts(Map<KeyId, AEAmount> source, String name) {
        Map<KeyId, AEAmount> copy = copyAmounts(source, name);
        for (AEAmount amount : copy.values()) {
            requirePhysical(amount, name + " amount");
        }
        return copy;
    }

    private static List<ExactWorkOutput> copyOutputSlots(List<ExactWorkOutput> source, CompiledPattern pattern,
            long executionWindow) {
        Objects.requireNonNull(source, "expectedOutputSlots");
        if (source.size() != pattern.outputs().size()) {
            throw new IllegalArgumentException("Expected output slots must match the sealed pattern");
        }
        ArrayList<ExactWorkOutput> copy = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            ExactWorkOutput output = Objects.requireNonNull(source.get(index), "expectedOutputSlots entry");
            if (output.outputIndex() != index || !output.key().equals(pattern.outputs().get(index).key())) {
                throw new IllegalArgumentException("Expected output slots must retain compiled output order");
            }
            if (!output.amount().equals(pattern.outputs().get(index).amountPerExecution()
                    .multiply(AEAmount.of(executionWindow)))) {
                throw new IllegalArgumentException("Expected output amount must match the sealed pattern window");
            }
            requirePhysical(output.amount(), "expected output slot amount");
            copy.add(output);
        }
        return List.copyOf(copy);
    }

    private static Map<KeyId, AEAmount> aggregate(List<ExactWorkOutput> slots) {
        TreeMap<KeyId, AEAmount> result = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (ExactWorkOutput slot : slots) {
            result.merge(slot.key(), slot.amount(), AEAmount::add);
        }
        return Collections.unmodifiableMap(result);
    }

    private static void validateSelections(CompiledPattern pattern, long executionWindow,
            List<PlannedInputSelection> selections) {
        int cursor = 0;
        AEAmount executions = AEAmount.of(executionWindow);
        for (int inputIndex = 0; inputIndex < pattern.inputs().size(); inputIndex++) {
            CompiledInputSpec input = pattern.inputs().get(inputIndex);
            AEAmount expectedUnits = input.multiplier().multiply(executions);
            AEAmount selectedUnits = AEAmount.ZERO;
            int priorCandidate = -1;
            while (cursor < selections.size() && selections.get(cursor).inputIndex() == inputIndex) {
                PlannedInputSelection selection = Objects.requireNonNull(selections.get(cursor++),
                        "plannedSelections entry");
                if (selection.candidateIndex() <= priorCandidate
                        || selection.candidateIndex() >= input.candidates().size()) {
                    throw new IllegalArgumentException("Command selection does not match the sealed pattern");
                }
                priorCandidate = selection.candidateIndex();
                CompiledCandidateSpec candidate = input.candidates().get(selection.candidateIndex());
                AEAmount gross = candidate.amountPerTemplate().multiply(selection.templateUnits());
                if (!candidate.key().equals(selection.consumedKey())
                        || !gross.equals(selection.grossConsumedAmount())) {
                    throw new IllegalArgumentException("Command selection gross amount does not match its candidate");
                }
                requirePhysical(selection.templateUnits(), "selection template units");
                requirePhysical(selection.grossConsumedAmount(), "selection gross amount");
                requirePhysical(selection.initialRequiredAmount(), "selection initial amount");
                boolean raw = selection.initialRequiredAmount().equals(gross);
                if (!raw) {
                    if (executionWindow != 1L || !isReusable(input, candidate, pattern)
                            || !selection.initialRequiredAmount().equals(reusableSeed(input, candidate,
                                    selection.templateUnits()))) {
                        throw new IllegalArgumentException("Command selection has an invalid reusable initial amount");
                    }
                }
                if (!selection.remainderReturn().equals(expectedRemainder(input, candidate, pattern, selection, raw))) {
                    throw new IllegalArgumentException("Command selection remainder does not match the sealed pattern");
                }
                selection.remainderReturn().ifPresent(value -> requirePhysical(value.amount(), "selection remainder"));
                selectedUnits = selectedUnits.add(selection.templateUnits());
            }
            if (!selectedUnits.equals(expectedUnits)) {
                throw new IllegalArgumentException("Command selections do not cover the sealed input window");
            }
        }
        if (cursor != selections.size()) {
            throw new IllegalArgumentException("Command has selections outside its sealed pattern inputs");
        }
    }

    private static java.util.Optional<appeng.rebuild.planner.PlannedRemainderReturn> expectedRemainder(
            CompiledInputSpec input, CompiledCandidateSpec candidate, CompiledPattern pattern,
            PlannedInputSelection selection, boolean raw) {
        return candidate.remainder().map(remainder -> new appeng.rebuild.planner.PlannedRemainderReturn(remainder.key(),
                raw ? remainder.amountPerTemplate().multiply(selection.templateUnits())
                        : reusableFinalReturn(input, candidate, selection.templateUnits())));
    }

    private static boolean isReusable(CompiledInputSpec input, CompiledCandidateSpec candidate,
            CompiledPattern pattern) {
        return input.candidates().size() == 1 && candidate.remainder().isPresent()
                && candidate.remainder().get().key().equals(candidate.key())
                && pattern.outputs().stream().noneMatch(output -> output.key().equals(candidate.key()));
    }

    private static AEAmount reusableSeed(CompiledInputSpec input, CompiledCandidateSpec candidate, AEAmount units) {
        AEAmount inputPerExecution = candidate.amountPerTemplate().multiply(input.multiplier());
        AEAmount returnedPerExecution = candidate.remainder().orElseThrow().amountPerTemplate()
                .multiply(input.multiplier());
        AEAmount executions = units.divide(input.multiplier());
        AEAmount net = inputPerExecution.compareTo(returnedPerExecution) > 0
                ? inputPerExecution.subtractExact(returnedPerExecution)
                : AEAmount.ZERO;
        return inputPerExecution.add(executions.subtractExact(AEAmount.ONE).multiply(net));
    }

    private static AEAmount reusableFinalReturn(CompiledInputSpec input, CompiledCandidateSpec candidate,
            AEAmount units) {
        AEAmount inputPerExecution = candidate.amountPerTemplate().multiply(input.multiplier());
        AEAmount returnedPerExecution = candidate.remainder().orElseThrow().amountPerTemplate()
                .multiply(input.multiplier());
        AEAmount executions = units.divide(input.multiplier());
        return returnedPerExecution.compareTo(inputPerExecution) < 0 ? returnedPerExecution
                : inputPerExecution.add(executions.multiply(returnedPerExecution.subtractExact(inputPerExecution)));
    }

    private static Map<KeyId, AEAmount> aggregateInputs(List<PlannedInputSelection> selections) {
        TreeMap<KeyId, AEAmount> result = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (PlannedInputSelection selection : selections) {
            result.merge(selection.consumedKey(), selection.initialRequiredAmount(), AEAmount::add);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<KeyId, AEAmount> aggregateRemainders(List<PlannedInputSelection> selections) {
        TreeMap<KeyId, AEAmount> result = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (PlannedInputSelection selection : selections) {
            selection.remainderReturn().ifPresent(value -> result.merge(value.key(), value.amount(), AEAmount::add));
        }
        return Collections.unmodifiableMap(result);
    }

    private static void requirePhysical(AEAmount amount, String name) {
        if (amount.compareTo(AEAmount.of(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(name + " exceeds the signed-long physical window");
        }
    }

}

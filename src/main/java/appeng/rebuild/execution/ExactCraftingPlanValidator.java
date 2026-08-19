package appeng.rebuild.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.CompiledCandidateSpec;
import appeng.rebuild.pattern.CompiledInputSpec;
import appeng.rebuild.pattern.CompiledOutputSpec;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.NormalizedPatternSnapshot;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.planner.DependencyValidationResult;
import appeng.rebuild.planner.ExactCraftPlanDraft;
import appeng.rebuild.planner.GridRevision;
import appeng.rebuild.planner.PlannedBatchCause;
import appeng.rebuild.planner.PlannedBatchId;
import appeng.rebuild.planner.PlannedCausalStep;
import appeng.rebuild.planner.PlannedCycleBatch;
import appeng.rebuild.planner.PlannedCycleLink;
import appeng.rebuild.planner.PlannedCycleMember;
import appeng.rebuild.planner.PlannedCycleOutput;
import appeng.rebuild.planner.PlannedInputSelection;
import appeng.rebuild.planner.PlannedPatternBatch;
import appeng.rebuild.planner.PlannedRemainderReturn;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.storage.StorageSnapshot;

/**
 * Pure bounded revalidation and sealing for an {@link ExactCraftPlanDraft}.
 *
 * <p>
 * No world, grid, service, thread, or mutable inventory reference is accepted here. The caller supplies immutable
 * snapshots, and a successful result still requires a CPU-ledger reservation before physical execution.
 */
public final class ExactCraftingPlanValidator {
    /** Revalidates a draft without mutating either draft or supplied snapshots. */
    public ExactPlanValidationResult validate(ExactCraftPlanDraft draft, StorageSnapshot storage,
            NormalizedPatternSnapshot patterns) {
        if (draft == null || storage == null || patterns == null) {
            return new ExactPlanValidationResult.Failure(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        try {
            // This is intentionally the first operation on a well-formed request: no stale draft may get a fallback.
            DependencyValidationResult dependency = draft.dependencies().validate(storage, patterns);
            if (dependency instanceof DependencyValidationResult.Invalid invalid) {
                return new ExactPlanValidationResult.Failure(
                        ExactPlanValidationResult.FailureReason.DEPENDENCY_MISMATCH, Optional.of(invalid.reason()));
            }
            return validateCurrent(draft, storage, patterns);
        } catch (Abort abort) {
            return new ExactPlanValidationResult.Failure(abort.reason);
        } catch (RuntimeException ignored) {
            // Draft values are externally constructible. Never leak their malformed shape through this API.
            return new ExactPlanValidationResult.Failure(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
    }

    private ExactPlanValidationResult validateCurrent(ExactCraftPlanDraft draft, StorageSnapshot storage,
            NormalizedPatternSnapshot patterns) {
        Work work = new Work();
        GridRevision validationRevision;
        try {
            validationRevision = GridRevision.capture(storage, patterns);
        } catch (RuntimeException exception) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_PATTERN);
        }

        List<PlannedCausalStep> steps = draft.causalSteps();
        if (steps.isEmpty() || steps.size() > PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        Map<PatternId, PatternRevision> usedRevisions = new TreeMap<>();
        Map<PatternId, AEAmount> executions = new TreeMap<>();
        List<StepData> data = new ArrayList<>(steps.size());
        for (PlannedCausalStep step : steps) {
            work.tick();
            if (step instanceof PlannedPatternBatch normal) {
                data.add(validateNormal(normal, patterns, usedRevisions, executions, work));
            } else if (step instanceof PlannedCycleBatch cycle) {
                data.add(validateCycle(cycle, patterns, usedRevisions, executions, work));
            } else {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
        }
        requireEveryPatternWasObserved(draft, usedRevisions, patterns, work);
        if (!executions.equals(draft.patternExecutions())) {
            throw abort(ExactPlanValidationResult.FailureReason.SUMMARY_MISMATCH);
        }

        validateCausalStructure(draft, steps, data, work);
        DerivedSummary summary = deriveSummaries(draft, data, work);
        validateRootStorageExclusion(draft, summary, work);
        if (!summary.debits.equals(draft.storageConsumed()) || !summary.surplus.equals(draft.surplus())) {
            throw abort(ExactPlanValidationResult.FailureReason.SUMMARY_MISMATCH);
        }
        validateStorageDebits(draft, summary.debits, storage, work);

        List<ExecutionManifest> manifests = new ArrayList<>(data.size());
        for (StepData step : data) {
            manifests.add(step.manifest);
        }
        return new ExactPlanValidationResult.Success(new ExactCraftingPlan(ExactPlanId.fresh(), draft.gridRevision(),
                validationRevision, draft.request(), steps, manifests, usedRevisions, executions, summary.debits,
                summary.surplus, draft.dependencies()));
    }

    private StepData validateNormal(PlannedPatternBatch batch, NormalizedPatternSnapshot patterns,
            Map<PatternId, PatternRevision> usedRevisions, Map<PatternId, AEAmount> executions, Work work) {
        work.tick();
        CompiledPattern pattern = resolve(batch.patternId(), patterns, usedRevisions, work);
        AEAmount count = amount(batch.executions());
        SelectionData selections = validateSelections(pattern, count, batch.inputs(), -1, work);
        Map<KeyId, AEAmount> outputs = newKeyAmounts();
        for (CompiledOutputSpec output : pattern.outputs()) {
            add(outputs, output.key(), multiply(output.amountPerExecution(), count, work), work);
        }
        for (PlannedInputSelection selection : selections.byIdentity.values()) {
            selection.remainderReturn().ifPresent(remainder -> add(outputs, remainder.key(), remainder.amount(), work));
        }
        Map<KeyId, AEAmount> initial = newKeyAmounts();
        for (PlannedInputSelection selection : selections.byIdentity.values()) {
            add(initial, selection.consumedKey(), selection.initialRequiredAmount(), work);
        }
        add(executions, pattern.id(), count, work);
        return new StepData(outputs, initial, selections.byIdentity, Set.of(), new NormalExecutionManifest(batch.id(),
                batch.cause(), new SealedPatternExecution(pattern, count, batch.inputs(), List.of())));
    }

    private StepData validateCycle(PlannedCycleBatch cycle, NormalizedPatternSnapshot patterns,
            Map<PatternId, PatternRevision> usedRevisions, Map<PatternId, AEAmount> executions, Work work) {
        work.tick();
        List<PlannedCycleMember> members = cycle.members();
        if (members.isEmpty() || members.size() > PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS
                || cycle.links().size() != members.size()) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        AEAmount repetitions = amount(cycle.repetitions());
        AEAmount seed = amount(cycle.seedAmount());
        List<CompiledPattern> compiled = new ArrayList<>(members.size());
        List<SealedPatternExecution> sealedMembers = new ArrayList<>(members.size());
        List<SelectionData> selections = new ArrayList<>(members.size());
        List<Map<Integer, PlannedCycleOutput>> outputsBySlot = new ArrayList<>(members.size());
        Map<KeyId, AEAmount> perTurnCredits = newKeyAmounts();
        Map<SelectionRef, PlannedInputSelection> allSelections = new HashMap<>();
        Set<PatternId> memberPatterns = new HashSet<>();
        for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
            work.tick();
            PlannedCycleMember member = members.get(memberIndex);
            if (member == null || !memberPatterns.add(member.patternId())) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
            CompiledPattern pattern = resolve(member.patternId(), patterns, usedRevisions, work);
            AEAmount perTurnExecutions = amount(member.executionsPerTurn());
            SelectionData memberSelections = validateSelections(pattern, perTurnExecutions, member.inputsPerTurn(),
                    memberIndex, work);
            compiled.add(pattern);
            selections.add(memberSelections);
            allSelections.putAll(memberSelections.byIdentity);
            add(executions, pattern.id(), multiply(perTurnExecutions, repetitions, work), work);
            Map<Integer, PlannedCycleOutput> slots = validateCycleOutputs(member, pattern, perTurnCredits, work);
            outputsBySlot.add(slots);
            sealedMembers.add(new SealedPatternExecution(pattern, perTurnExecutions, member.inputsPerTurn(),
                    member.outputsPerTurn()));
            for (PlannedInputSelection selection : memberSelections.byIdentity.values()) {
                selection.remainderReturn().ifPresent(remainder -> add(perTurnCredits, remainder.key(),
                        remainder.amount(), work));
            }
        }

        Set<SelectionRef> linked = new HashSet<>();
        Set<KeyId> internalKeys = new HashSet<>();
        for (int linkIndex = 0; linkIndex < cycle.links().size(); linkIndex++) {
            work.tick();
            PlannedCycleLink link = cycle.links().get(linkIndex);
            int expectedConsumer = (linkIndex + 1) % members.size();
            if (link == null || link.producerMemberIndex() != linkIndex
                    || link.consumerMemberIndex() != expectedConsumer
                    || !internalKeys.add(link.key())) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
            SelectionRef selectionRef = new SelectionRef(expectedConsumer, link.inputIndex(), link.candidateIndex());
            PlannedInputSelection selected = allSelections.get(selectionRef);
            PlannedCycleOutput supplied = outputsBySlot.get(linkIndex).get(link.outputIndex());
            CompiledPattern consumerPattern = compiled.get(expectedConsumer);
            if (link.inputIndex() >= consumerPattern.inputs().size()) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
            CompiledInputSpec internalInput = consumerPattern.inputs().get(link.inputIndex());
            if (link.candidateIndex() != 0 || internalInput.candidates().size() != 1) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
            CompiledCandidateSpec internalCandidate = internalInput.candidates().get(0);
            if (selected == null || supplied == null || !linked.add(selectionRef)
                    || !selected.consumedKey().equals(link.key())
                    || !selected.grossConsumedAmount().equals(link.amountPerTurn())
                    || !selected.initialRequiredAmount().equals(selected.grossConsumedAmount())
                    || selected.remainderReturn().isPresent() || internalCandidate.remainder().isPresent()
                    || !internalCandidate.key().equals(link.key()) || !supplied.key().equals(link.key())
                    || supplied.amountPerTurn().compareTo(link.amountPerTurn()) < 0) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
            if (linkIndex < cycle.links().size() - 1 && !supplied.amountPerTurn().equals(link.amountPerTurn())) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
            subtract(perTurnCredits, link.key(), link.amountPerTurn(), work);
        }
        PlannedCycleLink closing = cycle.links().get(cycle.links().size() - 1);
        if (!closing.key().equals(cycle.seedKey()) || !closing.amountPerTurn().equals(seed)) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        validateNoHiddenInternalChannels(compiled, cycle.links(), internalKeys, work);

        Map<KeyId, AEAmount> finalCredits = newKeyAmounts();
        for (Map.Entry<KeyId, AEAmount> entry : perTurnCredits.entrySet()) {
            add(finalCredits, entry.getKey(), multiply(entry.getValue(), repetitions, work), work);
        }
        add(finalCredits, cycle.seedKey(), seed, work);
        if (!finalCredits.equals(cycle.finalCredits())) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        AEAmount closingOutput = outputsBySlot.get(cycle.links().size() - 1).get(closing.outputIndex()).amountPerTurn();
        validateCycleProductivity(cycle, finalCredits, closingOutput, work);

        Map<KeyId, AEAmount> initial = newKeyAmounts();
        add(initial, cycle.seedKey(), seed, work);
        for (SelectionData memberSelections : selections) {
            for (Map.Entry<SelectionRef, PlannedInputSelection> entry : memberSelections.byIdentity.entrySet()) {
                if (!linked.contains(entry.getKey())) {
                    add(initial, entry.getValue().consumedKey(),
                            multiply(entry.getValue().initialRequiredAmount(), repetitions, work), work);
                }
            }
        }
        return new StepData(finalCredits, initial, allSelections, linked, new CycleExecutionManifest(cycle.id(),
                cycle.cause(), repetitions, cycle.seedKey(), seed, sealedMembers, cycle.links(), cycle.finalCredits()));
    }

    private CompiledPattern resolve(PatternId id, NormalizedPatternSnapshot patterns,
            Map<PatternId, PatternRevision> usedRevisions, Work work) {
        work.tick();
        CompiledPattern pattern = patterns.patternsById().get(Objects.requireNonNull(id, "patternId"));
        if (pattern == null || !pattern.id().equals(id)
                || pattern.keyRegistryGeneration() != patterns.keyRegistryGeneration()) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_PATTERN);
        }
        PatternRevision previous = usedRevisions.putIfAbsent(id, pattern.revision());
        if (previous != null && !previous.equals(pattern.revision())) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_PATTERN);
        }
        return pattern;
    }

    private void requireEveryPatternWasObserved(ExactCraftPlanDraft draft, Map<PatternId, PatternRevision> used,
            NormalizedPatternSnapshot patterns, Work work) {
        if (used.size() > PatternLimits.MAX_GRAPH_NODES) {
            throw abort(ExactPlanValidationResult.FailureReason.WORK_LIMIT);
        }
        for (Map.Entry<PatternId, PatternRevision> entry : used.entrySet()) {
            work.tick();
            PatternRevision observed = draft.dependencies().patternRevisions().get(entry.getKey());
            CompiledPattern current = patterns.patternsById().get(entry.getKey());
            if (observed == null || current == null || !observed.equals(entry.getValue())
                    || !current.revision().equals(entry.getValue())) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_PATTERN);
            }
        }
    }

    private SelectionData validateSelections(CompiledPattern pattern, AEAmount executions,
            List<PlannedInputSelection> supplied, int memberIndex, Work work) {
        if (supplied == null || supplied.size() > PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        Map<SelectionRef, PlannedInputSelection> selected = new HashMap<>();
        int cursor = 0;
        for (int inputIndex = 0; inputIndex < pattern.inputs().size(); inputIndex++) {
            work.tick();
            CompiledInputSpec input = pattern.inputs().get(inputIndex);
            AEAmount expectedUnits = multiply(input.multiplier(), executions, work);
            AEAmount selectedUnits = AEAmount.ZERO;
            int priorCandidate = -1;
            while (cursor < supplied.size() && supplied.get(cursor).inputIndex() == inputIndex) {
                work.tick();
                PlannedInputSelection selection = supplied.get(cursor++);
                if (selection == null || selection.candidateIndex() <= priorCandidate
                        || selection.candidateIndex() >= input.candidates().size()) {
                    throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
                }
                priorCandidate = selection.candidateIndex();
                CompiledCandidateSpec candidate = input.candidates().get(selection.candidateIndex());
                validateSelection(pattern, input, candidate, selection, work);
                SelectionRef ref = new SelectionRef(memberIndex, inputIndex, selection.candidateIndex());
                if (selected.put(ref, selection) != null) {
                    throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
                }
                selectedUnits = add(selectedUnits, selection.templateUnits(), work);
            }
            if (!selectedUnits.equals(expectedUnits)) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
        }
        if (cursor != supplied.size()) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        return new SelectionData(selected);
    }

    private void validateSelection(CompiledPattern pattern, CompiledInputSpec input, CompiledCandidateSpec candidate,
            PlannedInputSelection selection, Work work) {
        AEAmount units = amount(selection.templateUnits());
        AEAmount gross = multiply(candidate.amountPerTemplate(), units, work);
        if (!selection.consumedKey().equals(candidate.key()) || !selection.grossConsumedAmount().equals(gross)) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        boolean reusable = reusableSeedAllowed(input, candidate, pattern);
        AEAmount initial = amount(selection.initialRequiredAmount());
        if (!reusable && !initial.equals(gross)) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        if (reusable) {
            AEAmount seed = reusableSeed(input, candidate, units, work);
            if (!initial.equals(gross) && !initial.equals(seed)) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
        }
        Optional<PlannedRemainderReturn> expected = expectedReturn(input, candidate, pattern, units, initial, work);
        if (!expected.equals(selection.remainderReturn())) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
    }

    private static boolean reusableSeedAllowed(CompiledInputSpec input, CompiledCandidateSpec candidate,
            CompiledPattern pattern) {
        if (input.candidates().size() != 1 || candidate.remainder().isEmpty()
                || !candidate.remainder().get().key().equals(candidate.key())) {
            return false;
        }
        return pattern.outputs().stream().noneMatch(output -> output.key().equals(candidate.key()));
    }

    private AEAmount reusableSeed(CompiledInputSpec input, CompiledCandidateSpec candidate, AEAmount units, Work work) {
        if (!units.toBigInteger().remainder(input.multiplier().toBigInteger()).equals(java.math.BigInteger.ZERO)) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        AEAmount executions = units.divide(input.multiplier());
        AEAmount inputPerExecution = multiply(candidate.amountPerTemplate(), input.multiplier(), work);
        AEAmount returnedPerExecution = multiply(candidate.remainder().orElseThrow().amountPerTemplate(),
                input.multiplier(), work);
        AEAmount net = inputPerExecution.compareTo(returnedPerExecution) > 0
                ? subtractValue(inputPerExecution, returnedPerExecution, work)
                : AEAmount.ZERO;
        return add(inputPerExecution, multiply(subtractValue(executions, AEAmount.ONE, work), net, work), work);
    }

    private Optional<PlannedRemainderReturn> expectedReturn(CompiledInputSpec input, CompiledCandidateSpec candidate,
            CompiledPattern pattern, AEAmount units, AEAmount initial, Work work) {
        if (candidate.remainder().isEmpty()) {
            return Optional.empty();
        }
        AEAmount raw = multiply(candidate.remainder().get().amountPerTemplate(), units, work);
        AEAmount gross = multiply(candidate.amountPerTemplate(), units, work);
        if (!reusableSeedAllowed(input, candidate, pattern) || initial.equals(gross)) {
            return Optional.of(new PlannedRemainderReturn(candidate.remainder().get().key(), raw));
        }
        AEAmount seed = reusableSeed(input, candidate, units, work);
        if (!initial.equals(seed)) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        AEAmount executions = units.divide(input.multiplier());
        AEAmount inputPerExecution = multiply(candidate.amountPerTemplate(), input.multiplier(), work);
        AEAmount returnedPerExecution = multiply(candidate.remainder().get().amountPerTemplate(), input.multiplier(),
                work);
        AEAmount returned = returnedPerExecution.compareTo(inputPerExecution) < 0 ? returnedPerExecution
                : add(inputPerExecution, multiply(executions,
                        subtractValue(returnedPerExecution, inputPerExecution, work), work), work);
        return Optional.of(new PlannedRemainderReturn(candidate.remainder().get().key(), returned));
    }

    private Map<Integer, PlannedCycleOutput> validateCycleOutputs(PlannedCycleMember member, CompiledPattern pattern,
            Map<KeyId, AEAmount> credits, Work work) {
        if (member.outputsPerTurn().size() != pattern.outputs().size()) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        Map<Integer, PlannedCycleOutput> slots = new HashMap<>();
        for (PlannedCycleOutput output : member.outputsPerTurn()) {
            work.tick();
            if (output == null || output.outputIndex() >= pattern.outputs().size()
                    || slots.put(output.outputIndex(), output) != null) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
            CompiledOutputSpec source = pattern.outputs().get(output.outputIndex());
            AEAmount expected = multiply(source.amountPerExecution(), member.executionsPerTurn(), work);
            if (!source.key().equals(output.key()) || !expected.equals(output.amountPerTurn())) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
            add(credits, output.key(), output.amountPerTurn(), work);
        }
        if (slots.size() != pattern.outputs().size()) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        return slots;
    }

    private void validateNoHiddenInternalChannels(List<CompiledPattern> members, List<PlannedCycleLink> links,
            Set<KeyId> internalKeys, Work work) {
        for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
            work.tick();
            CompiledPattern pattern = members.get(memberIndex);
            PlannedCycleLink incoming = links.get((memberIndex - 1 + members.size()) % members.size());
            PlannedCycleLink outgoing = links.get(memberIndex);
            for (int inputIndex = 0; inputIndex < pattern.inputs().size(); inputIndex++) {
                List<CompiledCandidateSpec> candidates = pattern.inputs().get(inputIndex).candidates();
                for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
                    work.tick();
                    CompiledCandidateSpec candidate = candidates.get(candidateIndex);
                    if (inputIndex == incoming.inputIndex()) {
                        if (candidateIndex != incoming.candidateIndex() || candidate.remainder().isPresent()
                                || !candidate.key().equals(incoming.key())) {
                            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
                        }
                        continue;
                    }
                    if (internalKeys.contains(candidate.key()) || candidate.remainder().isPresent()
                            && internalKeys.contains(candidate.remainder().get().key())) {
                        throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
                    }
                }
            }
            for (int outputIndex = 0; outputIndex < pattern.outputs().size(); outputIndex++) {
                work.tick();
                if (outputIndex != outgoing.outputIndex()
                        && internalKeys.contains(pattern.outputs().get(outputIndex).key())) {
                    throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
                }
            }
        }
    }

    private void validateCycleProductivity(PlannedCycleBatch cycle, Map<KeyId, AEAmount> credits,
            AEAmount closingOutput, Work work) {
        work.tick();
        KeyId demandedKey;
        AEAmount demanded;
        boolean root;
        if (cycle.cause() instanceof PlannedBatchCause.Root value) {
            demandedKey = value.output();
            demanded = value.demandedAmount();
            root = true;
        } else if (cycle.cause() instanceof PlannedBatchCause.Input value) {
            demandedKey = value.key();
            demanded = value.demandedAmount();
            root = false;
        } else {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        if (!demandedKey.equals(cycle.seedKey())) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        AEAmount credited = credits.get(demandedKey);
        if (credited == null || credited.compareTo(demanded) < 0) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        if (demandedKey.equals(cycle.seedKey())) {
            if (root && credited.compareTo(add(cycle.seedAmount(), demanded, work)) < 0
                    || !root && credited.compareTo(cycle.seedAmount()) <= 0) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
        }
        if (closingOutput.compareTo(cycle.seedAmount()) <= 0) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        AEAmount gain = subtractValue(closingOutput, cycle.seedAmount(), work);
        AEAmount required = root ? demanded
                : demanded.compareTo(cycle.seedAmount()) > 0
                        ? subtractValue(demanded, cycle.seedAmount(), work)
                        : AEAmount.ZERO;
        AEAmount expectedRepetitions = required.equals(AEAmount.ZERO) ? AEAmount.ZERO : required.ceilDiv(gain);
        if (!root && expectedRepetitions.equals(AEAmount.ZERO)) {
            expectedRepetitions = AEAmount.ONE;
        }
        if (!expectedRepetitions.equals(cycle.repetitions())) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
    }

    private void validateCausalStructure(ExactCraftPlanDraft draft, List<PlannedCausalStep> steps,
            List<StepData> data, Work work) {
        Map<PlannedBatchId, Integer> positions = new HashMap<>();
        int roots = 0;
        for (int index = 0; index < steps.size(); index++) {
            work.tick();
            PlannedBatchId id = steps.get(index).id();
            if (id == null || positions.put(id, index) != null) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
        }
        for (int index = 0; index < steps.size(); index++) {
            work.tick();
            PlannedCausalStep step = steps.get(index);
            StepData source = data.get(index);
            if (step.cause() instanceof PlannedBatchCause.Root root) {
                if (!root.output().equals(draft.request().output())
                        || !root.demandedAmount().equals(draft.request().amount())
                        || !source.outputs.containsKey(root.output())) {
                    throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
                }
                roots++;
                continue;
            }
            if (!(step.cause() instanceof PlannedBatchCause.Input input)) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
            Integer consumerPosition = positions.get(input.consumerBatchId());
            if (consumerPosition == null || consumerPosition <= index || !source.outputs.containsKey(input.key())) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
            PlannedCausalStep consumer = steps.get(consumerPosition);
            StepData consumerData = data.get(consumerPosition);
            SelectionRef ref = new SelectionRef(input.cycleMemberIndex(), input.inputIndex(), input.candidateIndex());
            PlannedInputSelection selection;
            if (input.cycleMemberIndex() == PlannedBatchCause.Input.NORMAL_CONSUMER_MEMBER) {
                if (!(consumer instanceof PlannedPatternBatch)) {
                    throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
                }
                selection = consumerData.selections.get(ref);
                if (selection == null || !selection.initialRequiredAmount().equals(input.demandedAmount())) {
                    throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
                }
            } else {
                if (!(consumer instanceof PlannedCycleBatch cycle) || input.cycleMemberIndex() >= cycle.members().size()
                        || consumerData.linkedSelections.contains(ref)) {
                    throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
                }
                selection = consumerData.selections.get(ref);
                if (selection == null || !multiply(selection.initialRequiredAmount(), cycle.repetitions(), work)
                        .equals(input.demandedAmount())) {
                    throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
                }
            }
            if (selection == null || !selection.consumedKey().equals(input.key())) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
        }
        if (roots == 0) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
    }

    private DerivedSummary deriveSummaries(ExactCraftPlanDraft draft, List<StepData> data, Work work) {
        Map<KeyId, AEAmount> needed = newKeyAmounts();
        add(needed, draft.request().output(), amount(draft.request().amount()), work);
        Map<KeyId, AEAmount> surplus = newKeyAmounts();
        for (int index = data.size() - 1; index >= 0; index--) {
            work.tick();
            StepData step = data.get(index);
            for (Map.Entry<KeyId, AEAmount> credit : step.outputs.entrySet()) {
                consumeCredit(needed, surplus, credit.getKey(), credit.getValue(), work);
            }
            for (Map.Entry<KeyId, AEAmount> input : step.initialInputs.entrySet()) {
                add(needed, input.getKey(), input.getValue(), work);
            }
        }
        return new DerivedSummary(needed, surplus);
    }

    /**
     * A request must never be paid from its own initial storage. Any apparent seed/reuse exception is safe only when
     * the reverse causal ledger returns at least that exact request key as final surplus.
     */
    private void validateRootStorageExclusion(ExactCraftPlanDraft draft, DerivedSummary summary, Work work) {
        work.tick();
        AEAmount debit = summary.debits.get(draft.request().output());
        if (debit != null
                && debit.compareTo(summary.surplus.getOrDefault(draft.request().output(), AEAmount.ZERO)) > 0) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
    }

    private void validateStorageDebits(ExactCraftPlanDraft draft, Map<KeyId, AEAmount> debits,
            StorageSnapshot storage, Work work) {
        for (Map.Entry<KeyId, AEAmount> entry : debits.entrySet()) {
            work.tick();
            KeyId key = entry.getKey();
            if (key.value() >= storage.keyCount() || !draft.dependencies().storageKeyRevisions().containsKey(key)) {
                throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
            }
            if (storage.amount(key).compareTo(entry.getValue()) < 0) {
                throw abort(ExactPlanValidationResult.FailureReason.INSUFFICIENT_STORAGE);
            }
        }
    }

    private static Map<KeyId, AEAmount> newKeyAmounts() {
        return new TreeMap<>(Comparator.comparingInt(KeyId::value));
    }

    private static void add(Map<KeyId, AEAmount> target, KeyId key, AEAmount amount, Work work) {
        work.tick();
        amount(amount);
        AEAmount previous = target.get(key);
        if (previous == null && target.size() >= PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS) {
            throw abort(ExactPlanValidationResult.FailureReason.WORK_LIMIT);
        }
        target.put(key, previous == null ? amount : add(previous, amount, work));
    }

    private static void add(Map<PatternId, AEAmount> target, PatternId key, AEAmount amount, Work work) {
        work.tick();
        amount(amount);
        AEAmount previous = target.get(key);
        if (previous == null && target.size() >= PatternLimits.MAX_GRAPH_NODES) {
            throw abort(ExactPlanValidationResult.FailureReason.WORK_LIMIT);
        }
        target.put(key, previous == null ? amount : add(previous, amount, work));
    }

    private static void subtract(Map<KeyId, AEAmount> target, KeyId key, AEAmount amount, Work work) {
        work.tick();
        AEAmount current = target.get(key);
        if (current == null || current.compareTo(amount) < 0) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        AEAmount result = subtractValue(current, amount, work);
        if (result.equals(AEAmount.ZERO)) {
            target.remove(key);
        } else {
            target.put(key, result);
        }
    }

    private static void consumeCredit(Map<KeyId, AEAmount> needed, Map<KeyId, AEAmount> surplus, KeyId key,
            AEAmount credit, Work work) {
        work.tick();
        AEAmount need = needed.get(key);
        if (need == null) {
            add(surplus, key, credit, work);
            return;
        }
        if (need.compareTo(credit) <= 0) {
            needed.remove(key);
            AEAmount excess = subtractValue(credit, need, work);
            if (!excess.equals(AEAmount.ZERO)) {
                add(surplus, key, excess, work);
            }
        } else {
            needed.put(key, subtractValue(need, credit, work));
        }
    }

    private static AEAmount amount(AEAmount value) {
        value = Objects.requireNonNull(value, "amount");
        if (value.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
            throw abort(ExactPlanValidationResult.FailureReason.QUANTITY_LIMIT);
        }
        if (value.equals(AEAmount.ZERO)) {
            throw abort(ExactPlanValidationResult.FailureReason.MALFORMED_STEP);
        }
        return value;
    }

    private static AEAmount add(AEAmount left, AEAmount right, Work work) {
        work.tick();
        AEAmount result = left.add(right);
        return amount(result);
    }

    private static AEAmount multiply(AEAmount left, AEAmount right, Work work) {
        work.tick();
        AEAmount result = left.multiply(right);
        return amount(result);
    }

    private static AEAmount subtractValue(AEAmount left, AEAmount right, Work work) {
        work.tick();
        AEAmount result = left.subtractExact(right);
        if (result.equals(AEAmount.ZERO)) {
            return result;
        }
        return amount(result);
    }

    private static Abort abort(ExactPlanValidationResult.FailureReason reason) {
        return new Abort(reason);
    }

    private record SelectionRef(int memberIndex, int inputIndex, int candidateIndex) {
    }

    private record SelectionData(Map<SelectionRef, PlannedInputSelection> byIdentity) {
        private SelectionData {
            byIdentity = Collections.unmodifiableMap(new HashMap<>(byIdentity));
        }
    }

    private record StepData(Map<KeyId, AEAmount> outputs, Map<KeyId, AEAmount> initialInputs,
            Map<SelectionRef, PlannedInputSelection> selections, Set<SelectionRef> linkedSelections,
            ExecutionManifest manifest) {
        private StepData {
            TreeMap<KeyId, AEAmount> sortedOutputs = new TreeMap<>(Comparator.comparingInt(KeyId::value));
            sortedOutputs.putAll(outputs);
            outputs = Collections.unmodifiableMap(sortedOutputs);
            TreeMap<KeyId, AEAmount> sortedInputs = new TreeMap<>(Comparator.comparingInt(KeyId::value));
            sortedInputs.putAll(initialInputs);
            initialInputs = Collections.unmodifiableMap(sortedInputs);
            selections = Collections.unmodifiableMap(new HashMap<>(selections));
            linkedSelections = Set.copyOf(linkedSelections);
            Objects.requireNonNull(manifest, "manifest");
        }
    }

    private record DerivedSummary(Map<KeyId, AEAmount> debits, Map<KeyId, AEAmount> surplus) {
        private DerivedSummary {
            TreeMap<KeyId, AEAmount> sortedDebits = new TreeMap<>(Comparator.comparingInt(KeyId::value));
            sortedDebits.putAll(debits);
            debits = Collections.unmodifiableMap(sortedDebits);
            TreeMap<KeyId, AEAmount> sortedSurplus = new TreeMap<>(Comparator.comparingInt(KeyId::value));
            sortedSurplus.putAll(surplus);
            surplus = Collections.unmodifiableMap(sortedSurplus);
        }
    }

    private static final class Work {
        private int operations;

        private void tick() {
            if (operations++ >= PlannerLimits.MAX_CRAFT_MUTATIONS) {
                throw abort(ExactPlanValidationResult.FailureReason.WORK_LIMIT);
            }
        }
    }

    private static final class Abort extends RuntimeException {
        private final ExactPlanValidationResult.FailureReason reason;

        private Abort(ExactPlanValidationResult.FailureReason reason) {
            this.reason = reason;
        }
    }
}

package appeng.rebuild.execution;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.CompiledCandidateSpec;
import appeng.rebuild.pattern.CompiledInputSpec;
import appeng.rebuild.pattern.CompiledOutputSpec;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.planner.DependencySet;
import appeng.rebuild.planner.ExactCraftRequest;
import appeng.rebuild.planner.GridRevision;
import appeng.rebuild.planner.PlannedCausalStep;
import appeng.rebuild.planner.PlannedCycleBatch;
import appeng.rebuild.planner.PlannedCycleLink;
import appeng.rebuild.planner.PlannedCycleMember;
import appeng.rebuild.planner.PlannedInputSelection;
import appeng.rebuild.planner.PlannedPatternBatch;
import appeng.rebuild.planner.PlannedRemainderReturn;
import appeng.rebuild.quantity.AEAmount;

/**
 * Immutable exact plan sealed against immutable storage and pattern snapshots.
 *
 * <p>
 * This value is deliberately not execution authority. A later CPU ledger must atomically reserve its
 * {@link #initialStorageDebits()} and recheck its dependencies before any physical mutation takes place.
 */
public final class ExactCraftingPlan {
    private final ExactPlanId planId;
    private final GridRevision planningRevision;
    private final GridRevision validationRevision;
    private final ExactCraftRequest request;
    private final List<PlannedCausalStep> causalSteps;
    private final List<ExecutionManifest> executionManifests;
    private final Map<PatternId, PatternRevision> usedPatternRevisions;
    private final Map<PatternId, AEAmount> patternExecutions;
    private final Map<KeyId, AEAmount> initialStorageDebits;
    private final Map<KeyId, AEAmount> finalSurplus;
    private final DependencySet dependencies;

    ExactCraftingPlan(ExactPlanId planId, GridRevision planningRevision, GridRevision validationRevision,
            ExactCraftRequest request,
            List<PlannedCausalStep> causalSteps, List<ExecutionManifest> executionManifests,
            Map<PatternId, PatternRevision> usedPatternRevisions,
            Map<PatternId, AEAmount> patternExecutions, Map<KeyId, AEAmount> initialStorageDebits,
            Map<KeyId, AEAmount> finalSurplus, DependencySet dependencies) {
        this.planId = Objects.requireNonNull(planId, "planId");
        this.planningRevision = Objects.requireNonNull(planningRevision, "planningRevision");
        this.validationRevision = Objects.requireNonNull(validationRevision, "validationRevision");
        this.request = Objects.requireNonNull(request, "request");
        this.causalSteps = List.copyOf(Objects.requireNonNull(causalSteps, "causalSteps"));
        this.usedPatternRevisions = copyPatternRevisions(usedPatternRevisions);
        this.executionManifests = copyAndValidateManifests(executionManifests, this.causalSteps,
                this.usedPatternRevisions);
        this.patternExecutions = copyPatternAmounts(patternExecutions, "patternExecutions");
        this.initialStorageDebits = copyKeyAmounts(initialStorageDebits, "initialStorageDebits");
        this.finalSurplus = copyKeyAmounts(finalSurplus, "finalSurplus");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
    }

    /** Durable identity assigned by {@link ExactCraftingPlanValidator} when this plan was successfully sealed. */
    public ExactPlanId planId() {
        return planId;
    }

    public GridRevision planningRevision() {
        return planningRevision;
    }

    /** Identity captured from the immutable snapshots that successfully sealed this plan. */
    public GridRevision validationRevision() {
        return validationRevision;
    }

    public ExactCraftRequest request() {
        return request;
    }

    public List<PlannedCausalStep> causalSteps() {
        return causalSteps;
    }

    /**
     * Immutable compiled execution data in the same causal order as {@link #causalSteps()}.
     *
     * <p>
     * Executors must use this sealed data rather than consulting the current pattern registry after a reload.
     */
    public List<ExecutionManifest> executionManifests() {
        return executionManifests;
    }

    public Map<PatternId, PatternRevision> usedPatternRevisions() {
        return usedPatternRevisions;
    }

    public Map<PatternId, AEAmount> patternExecutions() {
        return patternExecutions;
    }

    public Map<KeyId, AEAmount> initialStorageDebits() {
        return initialStorageDebits;
    }

    public Map<KeyId, AEAmount> finalSurplus() {
        return finalSurplus;
    }

    public DependencySet dependencies() {
        return dependencies;
    }

    /**
     * Restores a persisted sealed plan with its original durable identity.
     *
     * <p>
     * This is deliberately a validating construction boundary, not a state injector: all aggregate summaries are
     * recomputed from the causal steps and sealed manifests before the immutable plan is exposed. Persistence codecs
     * must have already rebound every registry-scoped key to one current registry generation.
     */
    public static ExactCraftingPlan restoreValidated(ExactPlanId planId, GridRevision planningRevision,
            GridRevision validationRevision, ExactCraftRequest request, List<PlannedCausalStep> causalSteps,
            List<ExecutionManifest> executionManifests, Map<PatternId, PatternRevision> usedPatternRevisions,
            Map<PatternId, AEAmount> patternExecutions, Map<KeyId, AEAmount> initialStorageDebits,
            Map<KeyId, AEAmount> finalSurplus, DependencySet dependencies) {
        preflightRestore(planId, planningRevision, validationRevision, request, causalSteps, executionManifests,
                usedPatternRevisions, patternExecutions, initialStorageDebits, finalSurplus, dependencies);
        ExactCraftingPlan restored = new ExactCraftingPlan(planId, planningRevision, validationRevision, request,
                causalSteps, executionManifests, usedPatternRevisions, patternExecutions, initialStorageDebits,
                finalSurplus, dependencies);
        if (!planningRevision.equals(dependencies.gridRevision()))
            throw new IllegalArgumentException("Persisted plan dependencies must use its planning revision");
        if (validationRevision.serverGeneration() != planningRevision.serverGeneration()
                || validationRevision.keyRegistryGeneration() != planningRevision.keyRegistryGeneration()
                || !validationRevision.graphGeneration().equals(planningRevision.graphGeneration())
                || !validationRevision.recipeRevision().equals(planningRevision.recipeRevision())
                || validationRevision.storageRevision().value() < planningRevision.storageRevision().value())
            throw new IllegalArgumentException("Persisted validation revision is incompatible with plan provenance");
        for (Map.Entry<PatternId, PatternRevision> used : restored.usedPatternRevisions.entrySet()) {
            if (!used.getValue().equals(restored.dependencies.patternRevisions().get(used.getKey())))
                throw new IllegalArgumentException("Persisted plan used pattern lacks its matching dependency read");
        }
        // Draft construction independently checks causal references, planning-dependency identity and execution totals.
        new appeng.rebuild.planner.ExactCraftPlanDraft(planningRevision, request, restored.patternExecutions,
                restored.initialStorageDebits, restored.finalSurplus, restored.causalSteps, dependencies);
        validateSealedPatternSemantics(restored.causalSteps, restored.executionManifests);
        DerivedRestoreSummary derived = deriveRestoreSummary(restored.request, restored.causalSteps,
                restored.executionManifests);
        if (!derived.debits.equals(restored.initialStorageDebits) || !derived.surplus.equals(restored.finalSurplus)) {
            throw new IllegalArgumentException("Persisted plan summaries differ from its sealed causal ledger");
        }
        AEAmount rootDebit = restored.initialStorageDebits.get(restored.request.output());
        if (rootDebit != null && rootDebit.compareTo(restored.finalSurplus.getOrDefault(restored.request.output(),
                AEAmount.ZERO)) > 0)
            throw new IllegalArgumentException("Persisted plan improperly pays its request from root storage");
        return restored;
    }

    private static void preflightRestore(ExactPlanId planId, GridRevision planningRevision,
            GridRevision validationRevision, ExactCraftRequest request, List<PlannedCausalStep> causalSteps,
            List<ExecutionManifest> executionManifests, Map<PatternId, PatternRevision> usedPatternRevisions,
            Map<PatternId, AEAmount> patternExecutions, Map<KeyId, AEAmount> initialStorageDebits,
            Map<KeyId, AEAmount> finalSurplus, DependencySet dependencies) {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(planningRevision, "planningRevision");
        Objects.requireNonNull(validationRevision, "validationRevision");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(causalSteps, "causalSteps");
        Objects.requireNonNull(executionManifests, "executionManifests");
        Objects.requireNonNull(usedPatternRevisions, "usedPatternRevisions");
        Objects.requireNonNull(patternExecutions, "patternExecutions");
        Objects.requireNonNull(initialStorageDebits, "initialStorageDebits");
        Objects.requireNonNull(finalSurplus, "finalSurplus");
        Objects.requireNonNull(dependencies, "dependencies");
        if (causalSteps.size() > appeng.rebuild.planner.PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS
                || executionManifests.size() > appeng.rebuild.planner.PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS
                || usedPatternRevisions.size() > appeng.rebuild.pattern.PatternLimits.MAX_GRAPH_NODES
                || patternExecutions.size() > appeng.rebuild.pattern.PatternLimits.MAX_GRAPH_NODES
                || initialStorageDebits.size() > appeng.rebuild.planner.PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS
                || finalSurplus.size() > appeng.rebuild.planner.PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS)
            throw new IllegalArgumentException("Persisted plan exceeds bounded restore shape");
        requireBounded(request.amount());
        for (PlannedCausalStep step : causalSteps)
            Objects.requireNonNull(step, "causal step");
        for (ExecutionManifest manifest : executionManifests)
            Objects.requireNonNull(manifest, "execution manifest");
        for (Map.Entry<PatternId, AEAmount> entry : patternExecutions.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "pattern execution key");
            requireBounded(entry.getValue());
        }
        preflightKeyAmounts(initialStorageDebits, "initial storage debit");
        preflightKeyAmounts(finalSurplus, "final surplus");
    }

    private static void preflightKeyAmounts(Map<KeyId, AEAmount> values, String name) {
        for (Map.Entry<KeyId, AEAmount> entry : values.entrySet()) {
            Objects.requireNonNull(entry.getKey(), name + " key");
            requireBounded(entry.getValue());
        }
    }

    /** Replays all sealed-pattern arithmetic without consulting a live pattern or storage snapshot. */
    private static void validateSealedPatternSemantics(List<PlannedCausalStep> steps,
            List<ExecutionManifest> manifests) {
        for (int index = 0; index < steps.size(); index++) {
            PlannedCausalStep step = steps.get(index);
            ExecutionManifest manifest = manifests.get(index);
            if (step instanceof PlannedPatternBatch) {
                validateSelections(((NormalExecutionManifest) manifest).execution());
            } else {
                PlannedCycleBatch cycle = (PlannedCycleBatch) step;
                CycleExecutionManifest sealed = (CycleExecutionManifest) manifest;
                for (int member = 0; member < sealed.memberExecutions().size(); member++) {
                    SealedPatternExecution execution = sealed.memberExecutions().get(member);
                    validateSelections(execution);
                    validateCycleOutputProjection(execution);
                }
                validateCycleProductivity(cycle, sealed);
                // PlannedCycleBatch's constructor validates ordered link algebra, final credits and productivity. The
                // projection check above makes that existing algebra authoritative over sealed compiled outputs too.
                if (!cycle.links().equals(sealed.links()) || !cycle.finalCredits().equals(sealed.finalCredits()))
                    throw new IllegalArgumentException(
                            "Sealed cycle link or credit projection differs from causal step");
            }
        }
    }

    private static void validateSelections(SealedPatternExecution execution) {
        CompiledPattern pattern = execution.pattern();
        List<PlannedInputSelection> selected = execution.plannedSelections();
        int cursor = 0;
        for (int inputIndex = 0; inputIndex < pattern.inputs().size(); inputIndex++) {
            CompiledInputSpec input = pattern.inputs().get(inputIndex);
            AEAmount expectedUnits = multiplyRestore(input.multiplier(), execution.executions());
            AEAmount selectedUnits = AEAmount.ZERO;
            int priorCandidate = -1;
            while (cursor < selected.size() && selected.get(cursor).inputIndex() == inputIndex) {
                PlannedInputSelection selection = selected.get(cursor++);
                if (selection.candidateIndex() <= priorCandidate
                        || selection.candidateIndex() >= input.candidates().size())
                    throw new IllegalArgumentException("Sealed input selections are not canonical");
                priorCandidate = selection.candidateIndex();
                CompiledCandidateSpec candidate = input.candidates().get(selection.candidateIndex());
                validateSelection(pattern, input, candidate, selection);
                selectedUnits = selectedUnits.equals(AEAmount.ZERO) ? selection.templateUnits()
                        : requireBounded(selectedUnits.add(selection.templateUnits()));
            }
            if (!selectedUnits.equals(expectedUnits))
                throw new IllegalArgumentException("Sealed input selections do not cover their exact input group");
        }
        if (cursor != selected.size())
            throw new IllegalArgumentException("Sealed input selection has an out-of-range input group");
    }

    private static void validateSelection(CompiledPattern pattern, CompiledInputSpec input,
            CompiledCandidateSpec candidate, PlannedInputSelection selection) {
        AEAmount units = requireBounded(selection.templateUnits());
        AEAmount gross = multiplyRestore(candidate.amountPerTemplate(), units);
        if (!candidate.key().equals(selection.consumedKey()) || !gross.equals(selection.grossConsumedAmount()))
            throw new IllegalArgumentException("Sealed input selection does not match its compiled candidate");
        AEAmount initial = requireBounded(selection.initialRequiredAmount());
        boolean reusable = reusableSeedAllowed(pattern, input, candidate);
        if (!reusable && !initial.equals(gross))
            throw new IllegalArgumentException("Non-reusable selection cannot reduce initial requirement");
        if (reusable) {
            AEAmount seed = reusableSeed(input, candidate, units);
            if (!initial.equals(gross) && !initial.equals(seed))
                throw new IllegalArgumentException("Reusable selection initial requirement is invalid");
        }
        if (!expectedReturn(pattern, input, candidate, units, initial).equals(selection.remainderReturn()))
            throw new IllegalArgumentException("Sealed input remainder differs from compiled candidate");
    }

    private static boolean reusableSeedAllowed(CompiledPattern pattern, CompiledInputSpec input,
            CompiledCandidateSpec candidate) {
        if (input.candidates().size() != 1 || candidate.remainder().isEmpty()
                || !candidate.key().equals(candidate.remainder().get().key()))
            return false;
        for (CompiledOutputSpec output : pattern.outputs()) {
            if (output.key().equals(candidate.key()))
                return false;
        }
        return true;
    }

    private static AEAmount reusableSeed(CompiledInputSpec input, CompiledCandidateSpec candidate, AEAmount units) {
        if (!units.toBigInteger().remainder(input.multiplier().toBigInteger()).equals(java.math.BigInteger.ZERO))
            throw new IllegalArgumentException("Reusable selection units are not divisible by its multiplier");
        AEAmount executions = units.divide(input.multiplier());
        AEAmount inputPerExecution = multiplyRestore(candidate.amountPerTemplate(), input.multiplier());
        AEAmount returnedPerExecution = multiplyRestore(candidate.remainder().orElseThrow().amountPerTemplate(),
                input.multiplier());
        AEAmount net = inputPerExecution.compareTo(returnedPerExecution) > 0
                ? inputPerExecution.subtractExact(returnedPerExecution)
                : AEAmount.ZERO;
        return requireBounded(
                inputPerExecution.add(multiplyRestore(executions.subtractExact(AEAmount.ONE), net)));
    }

    private static java.util.Optional<PlannedRemainderReturn> expectedReturn(CompiledPattern pattern,
            CompiledInputSpec input, CompiledCandidateSpec candidate, AEAmount units, AEAmount initial) {
        if (candidate.remainder().isEmpty())
            return java.util.Optional.empty();
        AEAmount raw = multiplyRestore(candidate.remainder().get().amountPerTemplate(), units);
        AEAmount gross = multiplyRestore(candidate.amountPerTemplate(), units);
        if (!reusableSeedAllowed(pattern, input, candidate) || initial.equals(gross))
            return java.util.Optional.of(new PlannedRemainderReturn(candidate.remainder().get().key(), raw));
        AEAmount seed = reusableSeed(input, candidate, units);
        if (!initial.equals(seed))
            throw new IllegalArgumentException("Reusable selection has an unrecognised initial requirement");
        AEAmount executions = units.divide(input.multiplier());
        AEAmount inputPerExecution = multiplyRestore(candidate.amountPerTemplate(), input.multiplier());
        AEAmount returnedPerExecution = multiplyRestore(candidate.remainder().get().amountPerTemplate(),
                input.multiplier());
        AEAmount returned = returnedPerExecution.compareTo(inputPerExecution) < 0 ? returnedPerExecution
                : requireBounded(inputPerExecution.add(multiplyRestore(executions,
                        returnedPerExecution.subtractExact(inputPerExecution))));
        return java.util.Optional.of(new PlannedRemainderReturn(candidate.remainder().get().key(), returned));
    }

    private static void validateCycleOutputProjection(SealedPatternExecution execution) {
        if (execution.plannedOutputs().size() != execution.pattern().outputs().size())
            throw new IllegalArgumentException("Sealed cycle output count differs from compiled pattern");
        int expectedIndex = 0;
        for (var output : execution.plannedOutputs()) {
            if (output.outputIndex() != expectedIndex++)
                throw new IllegalArgumentException("Sealed cycle outputs are not in complete canonical order");
            CompiledOutputSpec source = execution.pattern().outputs().get(output.outputIndex());
            if (!source.key().equals(output.key())
                    || !multiplyRestore(source.amountPerExecution(), execution.executions())
                            .equals(output.amountPerTurn()))
                throw new IllegalArgumentException("Sealed cycle output differs from compiled pattern output");
        }
    }

    private static void validateCycleProductivity(PlannedCycleBatch cycle, CycleExecutionManifest sealed) {
        PlannedCycleLink closing = cycle.links().get(cycle.links().size() - 1);
        AEAmount closingOutput = sealed.memberExecutions().get(closing.producerMemberIndex()).plannedOutputs().stream()
                .filter(output -> output.outputIndex() == closing.outputIndex()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Cycle closing output is absent")).amountPerTurn();
        KeyId demandedKey;
        AEAmount demanded;
        boolean root;
        if (cycle.cause() instanceof appeng.rebuild.planner.PlannedBatchCause.Root value) {
            demandedKey = value.output();
            demanded = value.demandedAmount();
            root = true;
        } else {
            appeng.rebuild.planner.PlannedBatchCause.Input value = (appeng.rebuild.planner.PlannedBatchCause.Input) cycle
                    .cause();
            demandedKey = value.key();
            demanded = value.demandedAmount();
            root = false;
        }
        if (!demandedKey.equals(cycle.seedKey()) || closingOutput.compareTo(cycle.seedAmount()) <= 0)
            throw new IllegalArgumentException("Cycle does not restore and grow its demanded seed");
        AEAmount credited = cycle.finalCredits().get(demandedKey);
        if (credited == null || credited.compareTo(demanded) < 0
                || root && credited.compareTo(requireBounded(cycle.seedAmount().add(demanded))) < 0
                || !root && credited.compareTo(cycle.seedAmount()) <= 0)
            throw new IllegalArgumentException("Cycle final credits do not satisfy its causal demand");
        AEAmount gain = closingOutput.subtractExact(cycle.seedAmount());
        AEAmount required = root ? demanded
                : demanded.compareTo(cycle.seedAmount()) > 0 ? demanded.subtractExact(cycle.seedAmount())
                        : AEAmount.ZERO;
        AEAmount repetitions = required.equals(AEAmount.ZERO) ? AEAmount.ZERO : required.ceilDiv(gain);
        if (!root && repetitions.equals(AEAmount.ZERO))
            repetitions = AEAmount.ONE;
        if (!repetitions.equals(cycle.repetitions()))
            throw new IllegalArgumentException("Cycle repetitions do not equal exact productivity requirement");
    }

    private static DerivedRestoreSummary deriveRestoreSummary(ExactCraftRequest request,
            List<PlannedCausalStep> steps, List<ExecutionManifest> manifests) {
        TreeMap<KeyId, AEAmount> needed = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        TreeMap<KeyId, AEAmount> surplus = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        addRestore(needed, request.output(), requireBounded(request.amount()));
        for (int index = steps.size() - 1; index >= 0; index--) {
            PlannedCausalStep step = steps.get(index);
            ExecutionManifest manifest = manifests.get(index);
            TreeMap<KeyId, AEAmount> outputs = new TreeMap<>(Comparator.comparingInt(KeyId::value));
            TreeMap<KeyId, AEAmount> inputs = new TreeMap<>(Comparator.comparingInt(KeyId::value));
            if (step instanceof PlannedPatternBatch) {
                SealedPatternExecution execution = ((NormalExecutionManifest) manifest).execution();
                for (var output : execution.pattern().outputs())
                    addRestore(outputs, output.key(),
                            multiplyRestore(output.amountPerExecution(), execution.executions()));
                for (PlannedInputSelection selection : execution.plannedSelections()) {
                    addRestore(inputs, selection.consumedKey(), selection.initialRequiredAmount());
                    selection.remainderReturn().ifPresent(remainder -> addRestore(outputs, remainder.key(),
                            remainder.amount()));
                }
            } else {
                PlannedCycleBatch cycle = (PlannedCycleBatch) step;
                CycleExecutionManifest cycleManifest = (CycleExecutionManifest) manifest;
                outputs.putAll(cycleManifest.finalCredits());
                addRestore(inputs, cycle.seedKey(), cycle.seedAmount());
                for (int memberIndex = 0; memberIndex < cycleManifest.memberExecutions().size(); memberIndex++) {
                    SealedPatternExecution member = cycleManifest.memberExecutions().get(memberIndex);
                    for (PlannedInputSelection selection : member.plannedSelections()) {
                        if (!isLinked(cycle.links(), memberIndex, selection))
                            addRestore(inputs, selection.consumedKey(),
                                    multiplyRestore(selection.initialRequiredAmount(), cycle.repetitions()));
                    }
                }
            }
            for (Map.Entry<KeyId, AEAmount> output : outputs.entrySet())
                consumeRestore(needed, surplus, output.getKey(), output.getValue());
            for (Map.Entry<KeyId, AEAmount> input : inputs.entrySet())
                addRestore(needed, input.getKey(), input.getValue());
        }
        return new DerivedRestoreSummary(Collections.unmodifiableMap(needed), Collections.unmodifiableMap(surplus));
    }

    private static boolean isLinked(List<PlannedCycleLink> links, int memberIndex, PlannedInputSelection selection) {
        for (PlannedCycleLink link : links) {
            if (link.consumerMemberIndex() == memberIndex && link.inputIndex() == selection.inputIndex()
                    && link.candidateIndex() == selection.candidateIndex())
                return true;
        }
        return false;
    }

    private static void addRestore(Map<KeyId, AEAmount> target, KeyId key, AEAmount amount) {
        amount = requireBounded(amount);
        AEAmount current = target.get(key);
        target.put(key, current == null ? amount : requireBounded(current.add(amount)));
    }

    private static AEAmount multiplyRestore(AEAmount left, AEAmount right) {
        return requireBounded(requireBounded(left).multiply(requireBounded(right)));
    }

    private static AEAmount requireBounded(AEAmount amount) {
        amount = Objects.requireNonNull(amount, "amount");
        if (amount.equals(AEAmount.ZERO)
                || amount.toBigInteger().bitLength() > appeng.rebuild.planner.PlannerLimits.MAX_CRAFT_QUANTITY_BITS)
            throw new IllegalArgumentException("Restored exact quantity is outside planner bounds");
        return amount;
    }

    private static void consumeRestore(Map<KeyId, AEAmount> needed, Map<KeyId, AEAmount> surplus, KeyId key,
            AEAmount credit) {
        credit = requireBounded(credit);
        AEAmount required = needed.get(key);
        if (required == null) {
            addRestore(surplus, key, credit);
        } else if (required.compareTo(credit) <= 0) {
            needed.remove(key);
            AEAmount excess = required.equals(credit) ? AEAmount.ZERO : credit.subtractExact(required);
            if (!excess.equals(AEAmount.ZERO))
                addRestore(surplus, key, excess);
        } else {
            needed.put(key, required.subtractExact(credit));
        }
    }

    private record DerivedRestoreSummary(Map<KeyId, AEAmount> debits, Map<KeyId, AEAmount> surplus) {
    }

    private static Map<PatternId, PatternRevision> copyPatternRevisions(Map<PatternId, PatternRevision> source) {
        Objects.requireNonNull(source, "usedPatternRevisions");
        TreeMap<PatternId, PatternRevision> copy = new TreeMap<>();
        for (Map.Entry<PatternId, PatternRevision> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "usedPatternRevisions key"),
                    Objects.requireNonNull(entry.getValue(), "usedPatternRevisions value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<ExecutionManifest> copyAndValidateManifests(List<ExecutionManifest> source,
            List<PlannedCausalStep> steps, Map<PatternId, PatternRevision> usedRevisions) {
        source = List.copyOf(Objects.requireNonNull(source, "executionManifests"));
        if (source.size() != steps.size()) {
            throw new IllegalArgumentException("Each causal step requires exactly one execution manifest");
        }
        TreeMap<PatternId, PatternRevision> sealedRevisions = new TreeMap<>();
        for (int index = 0; index < steps.size(); index++) {
            PlannedCausalStep step = Objects.requireNonNull(steps.get(index), "causalSteps cannot contain null");
            ExecutionManifest manifest = Objects.requireNonNull(source.get(index),
                    "executionManifests cannot contain null");
            if (!step.id().equals(manifest.batchId()) || !step.cause().equals(manifest.cause())) {
                throw new IllegalArgumentException("Execution manifest identity must match its causal step");
            }
            validateManifestShape(step, manifest);
            for (SealedPatternExecution execution : manifest.patternExecutions()) {
                PatternId id = execution.pattern().id();
                PatternRevision prior = sealedRevisions.putIfAbsent(id, execution.pattern().revision());
                if (prior != null && !prior.equals(execution.pattern().revision())) {
                    throw new IllegalArgumentException("One sealed plan cannot use conflicting pattern revisions");
                }
            }
        }
        if (!sealedRevisions.equals(usedRevisions)) {
            throw new IllegalArgumentException("Sealed execution manifests must cover every used pattern revision");
        }
        return source;
    }

    private static void validateManifestShape(PlannedCausalStep step, ExecutionManifest manifest) {
        if (step instanceof PlannedPatternBatch batch && manifest instanceof NormalExecutionManifest normal) {
            SealedPatternExecution execution = normal.execution();
            if (!batch.patternId().equals(execution.pattern().id())
                    || !batch.executions().equals(execution.executions())
                    || !batch.inputs().equals(execution.plannedSelections())) {
                throw new IllegalArgumentException("Normal execution manifest differs from its causal batch");
            }
            return;
        }
        if (step instanceof PlannedCycleBatch batch && manifest instanceof CycleExecutionManifest cycle) {
            if (!batch.repetitions().equals(cycle.repetitions()) || !batch.seedKey().equals(cycle.seedKey())
                    || !batch.seedAmount().equals(cycle.seedAmount()) || !batch.links().equals(cycle.links())
                    || !batch.finalCredits().equals(cycle.finalCredits())
                    || batch.members().size() != cycle.memberExecutions().size()) {
                throw new IllegalArgumentException("Cycle execution manifest differs from its causal batch");
            }
            for (int index = 0; index < batch.members().size(); index++) {
                PlannedCycleMember member = batch.members().get(index);
                SealedPatternExecution execution = cycle.memberExecutions().get(index);
                if (!member.patternId().equals(execution.pattern().id())
                        || !member.executionsPerTurn().equals(execution.executions())
                        || !member.inputsPerTurn().equals(execution.plannedSelections())
                        || !member.outputsPerTurn().equals(execution.plannedOutputs())) {
                    throw new IllegalArgumentException("Cycle member execution manifest differs from its causal batch");
                }
            }
            return;
        }
        throw new IllegalArgumentException("Execution manifest type must match its causal step");
    }

    private static Map<PatternId, AEAmount> copyPatternAmounts(Map<PatternId, AEAmount> source, String name) {
        Objects.requireNonNull(source, name);
        TreeMap<PatternId, AEAmount> copy = new TreeMap<>();
        for (Map.Entry<PatternId, AEAmount> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), name + " key"),
                    requirePositive(entry.getValue(), name + " value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<KeyId, AEAmount> copyKeyAmounts(Map<KeyId, AEAmount> source, String name) {
        Objects.requireNonNull(source, name);
        TreeMap<KeyId, AEAmount> copy = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (Map.Entry<KeyId, AEAmount> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), name + " key"),
                    requirePositive(entry.getValue(), name + " value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static AEAmount requirePositive(AEAmount amount, String name) {
        amount = Objects.requireNonNull(amount, name);
        if (amount.equals(AEAmount.ZERO)
                || amount.toBigInteger().bitLength() > appeng.rebuild.planner.PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return amount;
    }
}

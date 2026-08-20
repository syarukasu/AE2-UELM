package appeng.rebuild.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.CompiledCandidateSpec;
import appeng.rebuild.pattern.CompiledInputSpec;
import appeng.rebuild.pattern.CompiledOutputSpec;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.CompiledPatternGraph;
import appeng.rebuild.pattern.CompiledPatternGraphBuilder;
import appeng.rebuild.pattern.CompiledRemainderSpec;
import appeng.rebuild.pattern.GraphBuildResult;
import appeng.rebuild.pattern.GraphGeneration;
import appeng.rebuild.pattern.NormalizedPatternSnapshot;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternKind;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.pattern.RecipeRevision;
import appeng.rebuild.pattern.SubstitutionPolicy;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Contract tests for T005B3-C2 planner integration of productive cycle templates. */
class ExactCraftPlannerC2ContractTest {
    private static final long KEY_GENERATION = 7L;
    private static final long SERVER_GENERATION = 9L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(3L);
    private static final RecipeRevision RECIPE_REVISION = new RecipeRevision(5L);
    private static final ExactCraftPlanner PLANNER = new ExactCraftPlanner();

    @Test
    void productiveSelfCycleBootstrapsFromOneStoredSeedAndRetainsOnlySeedSurplus() {
        CompiledPattern self = selfCycle("c2-self-bootstrap", 1, 2L);

        ExactCraftPlanDraft draft = success(snapshot(2, Map.of(1, AEAmount.ONE)), List.of(self),
                Map.of(self.id(), 10), request(1, 5L));

        PlannedCycleBatch cycle = onlyCycle(draft);
        assertEquals(new PlannedBatchCause.Root(new KeyId(1), AEAmount.of(5L)), cycle.cause());
        assertEquals(AEAmount.of(5L), cycle.repetitions());
        assertEquals(AEAmount.ONE, cycle.seedAmount());
        assertEquals(Map.of(self.id(), AEAmount.of(5L)), draft.patternExecutions());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE), draft.storageConsumed());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE), draft.surplus());
        assertConserves(draft, snapshot(2, Map.of(1, AEAmount.ONE)), Map.of(self.id(), self));
    }

    @Test
    void twoMemberRingUsesNormalizedVectorSeedGainAndExactAggregateExecutions() {
        CompiledPattern first = pattern("c2-ring-a", List.of(input(1, AEAmount.ONE,
                List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(2, 3L, true)));
        CompiledPattern second = pattern("c2-ring-b", List.of(input(2, AEAmount.ONE,
                List.of(candidate(2, 2L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)));

        ExactCraftPlanDraft draft = success(snapshot(3, Map.of(1, AEAmount.of(2L))), List.of(first, second),
                Map.of(first.id(), 10, second.id(), 10), request(1, 4L));

        PlannedCycleBatch cycle = onlyCycle(draft);
        assertEquals(List.of(first.id(), second.id()),
                cycle.members().stream().map(PlannedCycleMember::patternId).toList());
        assertEquals(List.of(AEAmount.of(2L), AEAmount.of(3L)),
                cycle.members().stream().map(PlannedCycleMember::executionsPerTurn).toList());
        assertEquals(AEAmount.of(2L), cycle.seedAmount());
        assertEquals(AEAmount.of(4L), cycle.repetitions());
        assertEquals(Map.of(first.id(), AEAmount.of(8L), second.id(), AEAmount.of(12L)), draft.patternExecutions());
        assertEquals(Map.of(new KeyId(1), AEAmount.of(2L)), draft.storageConsumed());
        assertEquals(Map.of(new KeyId(1), AEAmount.of(2L)), draft.surplus());
        assertConserves(draft, snapshot(3, Map.of(1, AEAmount.of(2L))), Map.of(first.id(), first, second.id(), second));
    }

    @Test
    void externalProducerPrecedesCycleAndOuterConsumerInCausalOrder() {
        CompiledPattern external = noInput("c2-external-seed", 2, 2L);
        CompiledPattern cyclePattern = pattern("c2-external-cycle", List.of(
                input(1, AEAmount.ONE, List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT),
                input(2, AEAmount.ONE, List.of(candidate(2, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 2L, true)));
        CompiledPattern outer = pattern("c2-outer-consumer", List.of(
                input(1, AEAmount.ONE, List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(9, 1L, true)));

        ExactCraftPlanDraft draft = success(snapshot(10, Map.of(1, AEAmount.ONE)),
                List.of(outer, cyclePattern, external),
                Map.of(outer.id(), 10, cyclePattern.id(), 10, external.id(), 0), request(9, 3L));

        assertEquals(List.of(external.id(), cyclePattern.id(), outer.id()), draft.causalSteps().stream()
                .map(ExactCraftPlannerC2ContractTest::patternOrCycleId).toList());
        PlannedCycleBatch cycle = assertInstanceOf(PlannedCycleBatch.class, draft.causalSteps().get(1));
        assertEquals(new PlannedBatchCause.Input(new PlannedBatchId(0L), PlannedBatchCause.Input.NORMAL_CONSUMER_MEMBER,
                0, 0, new KeyId(1), AEAmount.of(3L)), cycle.cause());
        PlannedPatternBatch externalBatch = assertInstanceOf(PlannedPatternBatch.class, draft.causalSteps().get(0));
        assertEquals(new PlannedBatchCause.Input(cycle.id(), 0, 1, 0, new KeyId(2), AEAmount.of(2L)),
                externalBatch.cause());
        assertEquals(Map.of(external.id(), AEAmount.of(1L), cyclePattern.id(), AEAmount.of(2L), outer.id(),
                AEAmount.of(3L)), draft.patternExecutions());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE), draft.storageConsumed());
        assertEquals(Map.of(), draft.surplus());
        assertConserves(draft, snapshot(10, Map.of(1, AEAmount.ONE)),
                Map.of(external.id(), external, cyclePattern.id(), cyclePattern,
                        outer.id(), outer));
    }

    @Test
    void cycleExternalCandidatesMayBeMixedPerTurnAndChildAcquisitionScalesByRepetitions() {
        CompiledPattern sourceA = pattern("c2-mix-source-a", List.of(input(4, AEAmount.ONE,
                List.of(candidate(4, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(2, 2L, true)));
        CompiledPattern sourceB = pattern("c2-mix-source-b", List.of(input(5, AEAmount.ONE,
                List.of(candidate(5, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(3, 2L, true)));
        CompiledPattern cyclePattern = pattern("c2-mixed-external", List.of(
                input(1, AEAmount.ONE, List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT),
                input(2, AEAmount.of(2L),
                        List.of(candidate(2, 1L, Optional.empty()), candidate(3, 1L, Optional.empty())),
                        SubstitutionPolicy.ALLOW_ALTERNATIVES)),
                List.of(output(1, 2L, true)));
        CompiledPattern outer = pattern("c2-mixed-outer", List.of(
                input(1, AEAmount.ONE, List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(9, 1L, true)));

        ExactCraftPlanDraft draft = success(snapshot(10, Map.of(1, AEAmount.ONE, 4, AEAmount.ONE, 5, AEAmount.ONE)),
                List.of(outer, cyclePattern, sourceA, sourceB),
                Map.of(outer.id(), 10, cyclePattern.id(), 10, sourceA.id(), 0, sourceB.id(), 0), request(9, 3L));

        PlannedCycleBatch cycle = assertInstanceOf(PlannedCycleBatch.class, draft.causalSteps().get(2));
        PlannedCycleMember member = cycle.members().get(0);
        assertEquals(List.of(0, 1), member.inputsPerTurn().stream().filter(selection -> selection.inputIndex() == 1)
                .map(PlannedInputSelection::candidateIndex).toList());
        assertEquals(List.of(AEAmount.ONE, AEAmount.ONE), member.inputsPerTurn().stream()
                .filter(selection -> selection.inputIndex() == 1).map(PlannedInputSelection::templateUnits).toList());
        assertEquals(List.of(sourceA.id(), sourceB.id()),
                draft.causalSteps().stream().filter(PlannedPatternBatch.class::isInstance)
                        .map(PlannedPatternBatch.class::cast).map(PlannedPatternBatch::patternId)
                        .filter(id -> !id.equals(outer.id()))
                        .toList());
        assertEquals(Map.of(sourceA.id(), AEAmount.ONE, sourceB.id(), AEAmount.ONE, cyclePattern.id(), AEAmount.of(2L),
                outer.id(), AEAmount.of(3L)), draft.patternExecutions());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE, new KeyId(4), AEAmount.ONE, new KeyId(5), AEAmount.ONE),
                draft.storageConsumed());
        assertEquals(Map.of(), draft.surplus());
        assertConserves(draft, snapshot(10, Map.of(1, AEAmount.ONE, 4, AEAmount.ONE, 5, AEAmount.ONE)),
                Map.of(sourceA.id(), sourceA, sourceB.id(), sourceB,
                        cyclePattern.id(), cyclePattern, outer.id(), outer));
    }

    @Test
    void storedSeedIsAcquiredOnceAndSameKeyExternalRemainderIsConserved() {
        CompiledPattern cyclePattern = pattern("c2-same-key-remainder", List.of(
                input(1, AEAmount.ONE, List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT),
                input(2, AEAmount.ONE, List.of(candidate(2, 1L,
                        Optional.of(remainder(2, 1L)))), SubstitutionPolicy.EXACT)),
                List.of(output(1, 2L, true)));

        ExactCraftPlanDraft draft = success(snapshot(3, Map.of(1, AEAmount.ONE, 2, AEAmount.of(3L))),
                List.of(cyclePattern), Map.of(cyclePattern.id(), 10), request(1, 3L));

        PlannedCycleBatch cycle = onlyCycle(draft);
        PlannedInputSelection external = cycle.members().get(0).inputsPerTurn().stream()
                .filter(selection -> selection.inputIndex() == 1).findFirst().orElseThrow();
        assertEquals(AEAmount.ONE, external.templateUnits());
        assertEquals(AEAmount.ONE, external.initialRequiredAmount());
        assertEquals(Optional.of(new PlannedRemainderReturn(new KeyId(2), AEAmount.ONE)), external.remainderReturn());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE, new KeyId(2), AEAmount.of(3L)), draft.storageConsumed());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE, new KeyId(2), AEAmount.of(3L)), draft.surplus());
        assertConserves(draft, snapshot(3, Map.of(1, AEAmount.ONE, 2, AEAmount.of(3L))),
                Map.of(cyclePattern.id(), cyclePattern));
    }

    @Test
    void laterExternalFailureRotatesEarlierGroupTransactionally() {
        CompiledPattern cyclePattern = pattern("c2-rotate-external", List.of(
                input(1, AEAmount.ONE, List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT),
                input(2, AEAmount.ONE, List.of(candidate(2, 1L, Optional.empty()), candidate(3, 1L, Optional.empty())),
                        SubstitutionPolicy.ALLOW_ALTERNATIVES),
                input(2, AEAmount.ONE, List.of(candidate(2, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 2L, true)));

        ExactCraftPlanDraft draft = success(
                snapshot(4, Map.of(1, AEAmount.ONE, 2, AEAmount.of(3L), 3, AEAmount.of(3L))),
                List.of(cyclePattern), Map.of(cyclePattern.id(), 10), request(1, 3L));

        PlannedCycleBatch cycle = onlyCycle(draft);
        assertEquals(List.of(1, 0), cycle.members().get(0).inputsPerTurn().stream()
                .filter(selection -> selection.inputIndex() > 0).map(PlannedInputSelection::candidateIndex).toList());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE, new KeyId(2), AEAmount.of(3L), new KeyId(3), AEAmount.of(3L)),
                draft.storageConsumed());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE), draft.surplus());
        assertConserves(draft, snapshot(4, Map.of(1, AEAmount.ONE, 2, AEAmount.of(3L), 3, AEAmount.of(3L))),
                Map.of(cyclePattern.id(), cyclePattern));
    }

    @Test
    void absentSeedRejectsCycleAndSelectsAlternativeWithoutLooping() {
        CompiledPattern cycle = selfCycle("c2-absent-seed-cycle", 1, 2L);
        CompiledPattern direct = noInput("c2-absent-seed-direct", 1, 1L);

        ExactCraftPlanDraft draft = success(snapshot(2, Map.of()), List.of(cycle, direct),
                Map.of(cycle.id(), 100, direct.id(), 0), request(1, 1L));

        assertEquals(List.of(direct.id(), cycle.id()),
                draft.batches().stream().map(PlannedPatternBatch::patternId).toList());
        assertTrue(draft.causalSteps().stream().noneMatch(PlannedCycleBatch.class::isInstance));
        assertEquals(Map.of(direct.id(), AEAmount.ONE, cycle.id(), AEAmount.ONE), draft.patternExecutions());
        assertEquals(Map.of(), draft.storageConsumed());
        assertConserves(draft, snapshot(2, Map.of()), Map.of(cycle.id(), cycle, direct.id(), direct));
    }

    @Test
    void nonproductiveOrAmbiguousCycleIsTypedCycleAndAlternativeRemainsUsable() {
        CompiledPattern neutral = selfCycle("c2-neutral-cycle", 1, 1L);
        CompiledPattern ambiguous = pattern("c2-ambiguous-cycle", List.of(
                input(1, AEAmount.ONE, List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 2L, true), output(1, 1L, false)));
        CompiledPattern direct = noInput("c2-cycle-alternative", 1, 1L);

        ExactCraftPlanDraft draft = success(snapshot(2, Map.of()), List.of(neutral, ambiguous, direct),
                Map.of(neutral.id(), 100, ambiguous.id(), 90, direct.id(), 0), request(1, 1L));

        assertEquals(List.of(direct.id(), ambiguous.id(), neutral.id()),
                draft.batches().stream().map(PlannedPatternBatch::patternId).toList());
        assertTrue(draft.causalSteps().stream().noneMatch(PlannedCycleBatch.class::isInstance));
        assertEquals(Map.of(direct.id(), AEAmount.ONE, ambiguous.id(), AEAmount.ONE, neutral.id(), AEAmount.ONE),
                draft.patternExecutions());
        assertConserves(draft, snapshot(2, Map.of()),
                Map.of(neutral.id(), neutral, ambiguous.id(), ambiguous, direct.id(), direct));
    }

    @Test
    void hugeCycleUsesOneCausalStepAndConstantStructuralWork() {
        CompiledPattern self = selfCycle("c2-huge-cycle", 1, 2L);
        AEAmount demand = AEAmount.of(BigInteger.TEN.pow(1000));

        ExactCraftPlanDraft draft = success(snapshot(2, Map.of(1, AEAmount.ONE)), List.of(self),
                Map.of(self.id(), 10), request(1, demand));

        PlannedCycleBatch cycle = onlyCycle(draft);
        assertEquals(demand, cycle.repetitions());
        assertEquals(1, draft.causalSteps().size());
        assertEquals(Map.of(self.id(), demand), draft.patternExecutions());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE), draft.storageConsumed());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE), draft.surplus());
    }

    @Test
    void quantityLimitRemainsTypedForCycleDemand() {
        CompiledPattern self = selfCycle("c2-cycle-quantity-limit", 1, 2L);
        AEAmount overLimit = AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS));

        ExactCraftPlanResult.Failure failure = assertInstanceOf(ExactCraftPlanResult.Failure.class,
                PLANNER.plan(request(1, overLimit), snapshot(2, Map.of(1, AEAmount.ONE)),
                        patternSnapshot(List.of(self), Map.of(self.id(), 10))));
        assertEquals(ExactCraftPlanResult.FailureReason.QUANTITY_LIMIT, failure.reason());
    }

    @Test
    void cycleIdsAndAggregateExecutionsAreDeterministicWithAllowedReservationGaps() {
        CompiledPattern self = selfCycle("c2-deterministic-cycle", 1, 2L);
        CompiledPattern direct = noInput("c2-deterministic-direct", 1, 1L);
        StorageSnapshot storage = snapshot(2, Map.of(1, AEAmount.ONE));
        Map<PatternId, Integer> priorities = Map.of(self.id(), 100, direct.id(), 0);

        ExactCraftPlanDraft first = success(storage, List.of(self, direct), priorities, request(1, 3L));
        ExactCraftPlanDraft repeated = success(storage, List.of(direct, self), priorities, request(1, 3L));

        assertEquals(first.causalSteps().stream().map(PlannedCausalStep::id).toList(),
                repeated.causalSteps().stream().map(PlannedCausalStep::id).toList());
        assertTrue(first.causalSteps().stream().map(PlannedCausalStep::id).distinct().count() == first.causalSteps()
                .size());
        assertEquals(Map.of(self.id(), AEAmount.of(3L)), first.patternExecutions());
    }

    private static PatternId patternOrCycleId(PlannedCausalStep step) {
        if (step instanceof PlannedPatternBatch batch) {
            return batch.patternId();
        }
        return ((PlannedCycleBatch) step).members().get(0).patternId();
    }

    private static PlannedCycleBatch onlyCycle(ExactCraftPlanDraft draft) {
        List<PlannedCycleBatch> cycles = draft.causalSteps().stream().filter(PlannedCycleBatch.class::isInstance)
                .map(PlannedCycleBatch.class::cast).toList();
        assertEquals(1, cycles.size(), "causalSteps=" + draft.causalSteps() + ", executions="
                + draft.patternExecutions() + ", storageConsumed=" + draft.storageConsumed() + ", surplus="
                + draft.surplus());
        return cycles.get(0);
    }

    private static ExactCraftPlanDraft success(StorageSnapshot storage, List<CompiledPattern> patterns,
            Map<PatternId, Integer> priorities, ExactCraftRequest request) {
        return assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(request, storage, patternSnapshot(patterns, priorities))).draft();
    }

    private static void assertConserves(ExactCraftPlanDraft draft, StorageSnapshot storage,
            Map<PatternId, CompiledPattern> patterns) {
        Map<KeyId, BigInteger> balance = new HashMap<>();
        for (int key = 0; key < storage.keyCount(); key++) {
            BigInteger amount = storage.amount(new KeyId(key)).toBigInteger();
            if (amount.signum() != 0) {
                balance.put(new KeyId(key), amount);
            }
        }
        for (PlannedCausalStep step : draft.causalSteps()) {
            if (step instanceof PlannedPatternBatch batch) {
                CompiledPattern pattern = patterns.get(batch.patternId());
                for (PlannedInputSelection input : batch.inputs()) {
                    add(balance, input.consumedKey(), input.grossConsumedAmount().toBigInteger().negate());
                    input.remainderReturn().ifPresent(remainder -> add(balance, remainder.key(),
                            remainder.amount().toBigInteger()));
                }
                for (CompiledOutputSpec output : pattern.outputs()) {
                    add(balance, output.key(), output.amountPerExecution().toBigInteger()
                            .multiply(batch.executions().toBigInteger()));
                }
            } else {
                PlannedCycleBatch cycle = (PlannedCycleBatch) step;
                for (PlannedCycleMember member : cycle.members()) {
                    CompiledPattern pattern = patterns.get(member.patternId());
                    BigInteger repetitions = cycle.repetitions().toBigInteger();
                    BigInteger executions = member.executionsPerTurn().toBigInteger().multiply(repetitions);
                    for (PlannedInputSelection input : member.inputsPerTurn()) {
                        add(balance, input.consumedKey(),
                                input.grossConsumedAmount().toBigInteger().multiply(repetitions)
                                        .negate());
                        input.remainderReturn().ifPresent(remainder -> add(balance, remainder.key(),
                                remainder.amount().toBigInteger().multiply(repetitions)));
                    }
                    for (CompiledOutputSpec output : pattern.outputs()) {
                        add(balance, output.key(), output.amountPerExecution().toBigInteger().multiply(executions));
                    }
                }
            }
        }
        add(balance, draft.request().output(), draft.request().amount().toBigInteger().negate());
        Map<KeyId, BigInteger> expected = new HashMap<>();
        for (Map.Entry<KeyId, AEAmount> entry : draft.surplus().entrySet()) {
            expected.put(entry.getKey(), entry.getValue().toBigInteger());
        }
        balance.entrySet().removeIf(entry -> entry.getValue().signum() == 0);
        assertEquals(expected, balance);
    }

    private static void add(Map<KeyId, BigInteger> map, KeyId key, BigInteger amount) {
        map.merge(key, amount, BigInteger::add);
    }

    private static ExactCraftRequest request(int output, long amount) {
        return request(output, AEAmount.of(amount));
    }

    private static ExactCraftRequest request(int output, AEAmount amount) {
        return new ExactCraftRequest(new KeyId(output), amount);
    }

    private static CompiledPattern selfCycle(String id, int key, long outputAmount) {
        return pattern(id, List.of(input(key, AEAmount.ONE,
                List.of(candidate(key, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(key, outputAmount, true)));
    }

    private static CompiledPattern noInput(String id, int outputKey, long outputAmount) {
        return pattern(id, List.of(), List.of(output(outputKey, outputAmount, true)));
    }

    private static CompiledPattern pattern(String id, List<CompiledInputSpec> inputs,
            List<CompiledOutputSpec> outputs) {
        return new CompiledPattern(new PatternId(id), PatternKind.CRAFTING, inputs, outputs, Optional.empty(),
                new PatternRevision(0L), KEY_GENERATION);
    }

    private static CompiledInputSpec input(int key, AEAmount multiplier, List<CompiledCandidateSpec> candidates,
            SubstitutionPolicy policy) {
        return new CompiledInputSpec(candidates, multiplier, policy);
    }

    private static CompiledCandidateSpec candidate(int key, long amount, Optional<CompiledRemainderSpec> remainder) {
        return new CompiledCandidateSpec(new KeyId(key), AEAmount.of(amount), remainder);
    }

    private static CompiledRemainderSpec remainder(int key, long amount) {
        return new CompiledRemainderSpec(new KeyId(key), AEAmount.of(amount));
    }

    private static CompiledOutputSpec output(int key, long amount, boolean primary) {
        return new CompiledOutputSpec(new KeyId(key), AEAmount.of(amount), primary);
    }

    private static NormalizedPatternSnapshot patternSnapshot(List<CompiledPattern> patterns,
            Map<PatternId, Integer> priorities) {
        CompiledPatternGraph graph = ((GraphBuildResult.Success) new CompiledPatternGraphBuilder(GRAPH_GENERATION,
                KEY_GENERATION).build(patterns)).graph();
        return new NormalizedPatternSnapshot(SERVER_GENERATION, RECIPE_REVISION, KEY_GENERATION, graph.patternsById(),
                graph, priorities, patterns.size(), false, List.of());
    }

    private static StorageSnapshot snapshot(int keyCount, Map<Integer, AEAmount> values) {
        AmountVector amounts = new AmountVector(keyCount);
        long[] revisions = new long[keyCount];
        for (Map.Entry<Integer, AEAmount> value : values.entrySet()) {
            amounts.set(value.getKey(), value.getValue());
        }
        return new StorageSnapshot(KEY_GENERATION, StorageRevision.ZERO, keyCount, amounts, revisions);
    }
}

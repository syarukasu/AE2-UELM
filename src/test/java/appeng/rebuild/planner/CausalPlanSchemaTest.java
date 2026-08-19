package appeng.rebuild.planner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.Collection;
import java.util.Iterator;
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
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.pattern.RecipeRevision;
import appeng.rebuild.pattern.SubstitutionPolicy;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Contract tests for immutable causal-plan ids, references and compact productive-cycle steps. */
class CausalPlanSchemaTest {
    private static final long KEY_GENERATION = 7L;
    private static final long SERVER_GENERATION = 9L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(3L);
    private static final RecipeRevision RECIPE_REVISION = new RecipeRevision(5L);
    private static final ExactCraftPlanner PLANNER = new ExactCraftPlanner();

    @Test
    void plannerEmitsProducerBeforeConsumerWithExactForwardCauseAndImmutableProjection() {
        CompiledCandidateSpec sourceCandidate = candidate(0, AEAmount.of(2L),
                Optional.of(remainder(0, 2L)));
        CompiledPattern source = pattern("causal-source",
                List.of(input(AEAmount.ONE, List.of(sourceCandidate), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)));
        CompiledPattern consumer = pattern("causal-consumer", List.of(
                input(AEAmount.ONE, List.of(candidate(1, AEAmount.ONE, Optional.empty())),
                        SubstitutionPolicy.EXACT),
                input(AEAmount.ONE, List.of(candidate(0, AEAmount.of(2L), Optional.empty())),
                        SubstitutionPolicy.EXACT)),
                List.of(output(2, 1L, true)));
        ExactCraftRequest request = request(2, 1L);

        ExactCraftPlanDraft draft = success(snapshot(3, Map.of(0, AEAmount.of(2L))),
                List.of(source, consumer), Map.of(source.id(), 10, consumer.id(), 0), request);
        List<PlannedCausalStep> steps = draft.causalSteps();
        PlannedPatternBatch producer = assertInstanceOf(PlannedPatternBatch.class, steps.get(0));
        PlannedPatternBatch exactConsumer = assertInstanceOf(PlannedPatternBatch.class, steps.get(1));

        assertEquals(source.id(), producer.patternId());
        assertEquals(consumer.id(), exactConsumer.patternId());
        assertNotEquals(producer.id(), exactConsumer.id());
        assertEquals(new PlannedBatchId(1L), producer.id());
        assertEquals(new PlannedBatchId(0L), exactConsumer.id());

        PlannedBatchCause.Input cause = assertInstanceOf(PlannedBatchCause.Input.class, producer.cause());
        assertEquals(exactConsumer.id(), cause.consumerBatchId());
        assertEquals(PlannedBatchCause.Input.NORMAL_CONSUMER_MEMBER, cause.cycleMemberIndex());
        assertEquals(0, cause.inputIndex());
        assertEquals(0, cause.candidateIndex());
        assertEquals(new KeyId(1), cause.key());
        assertEquals(AEAmount.ONE, cause.demandedAmount());
        assertTrue(exactConsumer.inputs().stream().anyMatch(input -> input.inputIndex() == cause.inputIndex()
                && input.candidateIndex() == cause.candidateIndex() && input.consumedKey().equals(cause.key())));

        assertEquals(new PlannedBatchCause.Root(request.output(), request.amount()), exactConsumer.cause());
        assertEquals(List.of(producer, exactConsumer), draft.batches());
        assertThrows(UnsupportedOperationException.class, () -> draft.causalSteps().clear());
        assertThrows(UnsupportedOperationException.class, () -> draft.batches().clear());

        ExactCraftPlanDraft repeated = success(snapshot(3, Map.of(0, AEAmount.of(2L))),
                List.of(consumer, source), Map.of(source.id(), 10, consumer.id(), 0), request);
        assertEquals(steps.stream().map(PlannedCausalStep::id).toList(),
                repeated.causalSteps().stream().map(PlannedCausalStep::id).toList());
    }

    @Test
    void plannerIdsAreDeterministicUniqueAndPermitGapsFromRolledBackAttempts() {
        CompiledPattern failing = pattern("a-failing", List.of(input(AEAmount.ONE,
                List.of(candidate(0, AEAmount.of(2L), Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)));
        CompiledPattern fallback = pattern("b-fallback", List.of(), List.of(output(1, 1L, true)));
        ExactCraftRequest request = request(1, 1L);
        Map<PatternId, Integer> priorities = Map.of(failing.id(), 10, fallback.id(), 0);

        ExactCraftPlanDraft first = success(snapshot(2, Map.of(0, AEAmount.ONE)), List.of(failing, fallback),
                priorities, request);
        ExactCraftPlanDraft second = success(snapshot(2, Map.of(0, AEAmount.ONE)), List.of(fallback, failing),
                priorities, request);

        assertEquals(1, first.causalSteps().size());
        assertEquals(new PlannedBatchId(2L), first.causalSteps().get(0).id());
        assertEquals(first.causalSteps().stream().map(PlannedCausalStep::id).toList(),
                second.causalSteps().stream().map(PlannedCausalStep::id).toList());
        assertEquals(1L, first.causalSteps().stream().map(PlannedCausalStep::id).distinct().count());
        assertEquals(new PlannedBatchCause.Root(request.output(), request.amount()),
                first.causalSteps().get(0).cause());
    }

    @Test
    void validOneMemberCycleDerivesExactCreditsAndHasNoNormalBatchProjection() {
        KeyId seed = new KeyId(0);
        KeyId externalOutput = new KeyId(2);
        KeyId externalInput = new KeyId(3);
        KeyId externalRemainder = new KeyId(4);
        PatternId pattern = new PatternId("cycle-self");
        ExactCraftRequest request = request(externalOutput.value(), 6L);

        PlannedInputSelection internal = selection(0, 0, seed, 1L, 1L, Optional.empty());
        PlannedInputSelection external = selection(1, 0, externalInput, 2L, 2L,
                Optional.of(new PlannedRemainderReturn(externalRemainder, AEAmount.ONE)));
        PlannedCycleMember member = new PlannedCycleMember(pattern, AEAmount.ONE, List.of(internal, external),
                List.of(cycleOutput(0, seed, 2L), cycleOutput(1, externalOutput, 3L)));
        PlannedCycleLink link = cycleLink(0, 0, 0, 0, 0, seed, 1L);
        Map<KeyId, AEAmount> exactCredits = Map.of(seed, AEAmount.of(3L), externalOutput, AEAmount.of(6L),
                externalRemainder, AEAmount.of(2L));
        PlannedCycleBatch cycle = new PlannedCycleBatch(new PlannedBatchId(7L), root(request), AEAmount.of(2L), seed,
                AEAmount.ONE, List.of(member), List.of(link), exactCredits);

        ExactCraftPlanDraft draft = draft(request, Map.of(pattern, AEAmount.of(2L)), List.of(cycle));

        assertEquals(List.of(cycle), draft.causalSteps());
        assertTrue(draft.batches().isEmpty());
        assertEquals(Map.of(pattern, AEAmount.of(2L)), draft.patternExecutions());
        assertEquals(exactCredits, cycle.finalCredits());
        assertEquals(AEAmount.of(2L), member.outputsPerTurn().get(0).amountPerTurn(),
                "the closing output may exceed the one-unit restored seed");
        assertThrows(UnsupportedOperationException.class, () -> cycle.members().clear());
        assertThrows(UnsupportedOperationException.class, () -> cycle.links().clear());
        assertThrows(UnsupportedOperationException.class, () -> cycle.finalCredits().clear());
    }

    @Test
    void validTwoMemberOrderedRingAggregatesExecutionsByExactRepetitions() {
        KeyId seed = new KeyId(0);
        KeyId linkKey = new KeyId(1);
        KeyId externalOutput = new KeyId(2);
        PatternId firstPattern = new PatternId("cycle-a");
        PatternId secondPattern = new PatternId("cycle-b");
        ExactCraftRequest request = request(externalOutput.value(), 4L);

        PlannedCycleMember first = new PlannedCycleMember(firstPattern, AEAmount.of(2L),
                List.of(selection(0, 0, seed, 1L, 1L, Optional.empty())),
                List.of(cycleOutput(0, linkKey, 1L), cycleOutput(1, externalOutput, 1L)));
        PlannedCycleMember second = new PlannedCycleMember(secondPattern, AEAmount.of(3L),
                List.of(selection(0, 0, linkKey, 1L, 1L, Optional.empty())),
                List.of(cycleOutput(0, seed, 2L)));
        List<PlannedCycleLink> links = List.of(
                cycleLink(0, 0, 1, 0, 0, linkKey, 1L),
                cycleLink(1, 0, 0, 0, 0, seed, 1L));
        AEAmount repetitions = AEAmount.of(4L);
        Map<KeyId, AEAmount> exactCredits = Map.of(seed, AEAmount.of(5L), externalOutput, AEAmount.of(4L));
        PlannedCycleBatch cycle = new PlannedCycleBatch(new PlannedBatchId(3L), root(request), repetitions, seed,
                AEAmount.ONE, List.of(first, second), links, exactCredits);
        Map<PatternId, AEAmount> exactExecutions = Map.of(firstPattern, AEAmount.of(8L), secondPattern,
                AEAmount.of(12L));

        ExactCraftPlanDraft draft = draft(request, exactExecutions, List.of(cycle));

        assertEquals(exactExecutions, draft.patternExecutions());
        assertEquals(List.of(firstPattern, secondPattern),
                cycle.members().stream().map(PlannedCycleMember::patternId).toList());
        assertEquals(List.of(0, 1), cycle.links().stream().map(PlannedCycleLink::producerMemberIndex).toList());
        assertEquals(List.of(1, 0), cycle.links().stream().map(PlannedCycleLink::consumerMemberIndex).toList());
        assertEquals(exactCredits, cycle.finalCredits());
    }

    @Test
    void draftRejectsDuplicateSelfDanglingBackwardAndMismatchedRootReferences() {
        ExactCraftRequest request = request(7, 1L);
        PlannedBatchCause.Root root = root(request);
        PatternId firstPattern = new PatternId("ref-a");
        PatternId secondPattern = new PatternId("ref-b");

        PlannedPatternBatch duplicateFirst = normal(0L, root, firstPattern, List.of());
        PlannedPatternBatch duplicateSecond = normal(0L, root, secondPattern, List.of());
        assertThrows(IllegalArgumentException.class, () -> draft(request,
                Map.of(firstPattern, AEAmount.ONE, secondPattern, AEAmount.ONE),
                List.of(duplicateFirst, duplicateSecond)));

        PlannedBatchId selfId = new PlannedBatchId(1L);
        PlannedPatternBatch self = new PlannedPatternBatch(selfId,
                inputCause(selfId, PlannedBatchCause.Input.NORMAL_CONSUMER_MEMBER, 0, 0, 0), firstPattern,
                AEAmount.ONE, List.of(selection(0, 0, new KeyId(0), 1L, 1L, Optional.empty())));
        assertThrows(IllegalArgumentException.class,
                () -> draft(request, Map.of(firstPattern, AEAmount.ONE), List.of(self)));

        PlannedPatternBatch dangling = new PlannedPatternBatch(new PlannedBatchId(2L),
                inputCause(new PlannedBatchId(99L), PlannedBatchCause.Input.NORMAL_CONSUMER_MEMBER, 0, 0, 0),
                firstPattern, AEAmount.ONE, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> draft(request, Map.of(firstPattern, AEAmount.ONE), List.of(dangling)));

        PlannedPatternBatch earlierConsumer = normal(3L, root, firstPattern,
                List.of(selection(0, 0, new KeyId(0), 1L, 1L, Optional.empty())));
        PlannedPatternBatch backwardProducer = new PlannedPatternBatch(new PlannedBatchId(4L),
                inputCause(earlierConsumer.id(), PlannedBatchCause.Input.NORMAL_CONSUMER_MEMBER, 0, 0, 0),
                secondPattern, AEAmount.ONE, List.of());
        assertThrows(IllegalArgumentException.class, () -> draft(request,
                Map.of(firstPattern, AEAmount.ONE, secondPattern, AEAmount.ONE),
                List.of(earlierConsumer, backwardProducer)));

        PlannedPatternBatch wrongRoot = normal(5L,
                new PlannedBatchCause.Root(new KeyId(6), request.amount()), firstPattern, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> draft(request, Map.of(firstPattern, AEAmount.ONE), List.of(wrongRoot)));
    }

    @Test
    void draftValidatesNormalAndCycleConsumerMemberTypesAndExactSelections() {
        ExactCraftRequest request = request(2, 1L);
        KeyId seed = new KeyId(0);
        PatternId producerPattern = new PatternId("cause-producer");
        PatternId cyclePattern = new PatternId("cause-cycle");
        PlannedBatchId cycleId = new PlannedBatchId(11L);
        PlannedCycleMember member = new PlannedCycleMember(cyclePattern, AEAmount.ONE,
                List.of(selection(0, 0, seed, 1L, 1L, Optional.empty())),
                List.of(cycleOutput(0, seed, 1L), cycleOutput(1, request.output(), 1L)));
        PlannedCycleBatch cycle = new PlannedCycleBatch(cycleId, root(request), AEAmount.ONE, seed, AEAmount.ONE,
                List.of(member), List.of(cycleLink(0, 0, 0, 0, 0, seed, 1L)),
                Map.of(seed, AEAmount.ONE, request.output(), AEAmount.ONE));
        PlannedPatternBatch cycleInputProducer = new PlannedPatternBatch(new PlannedBatchId(10L),
                inputCause(cycleId, 0, 0, 0, seed.value()), producerPattern, AEAmount.ONE, List.of());
        Map<PatternId, AEAmount> exactExecutions = Map.of(producerPattern, AEAmount.ONE, cyclePattern, AEAmount.ONE);

        assertDoesNotThrow(() -> draft(request, exactExecutions, List.of(cycleInputProducer, cycle)));

        PlannedPatternBatch normalTypeForCycle = new PlannedPatternBatch(new PlannedBatchId(12L),
                inputCause(cycleId, PlannedBatchCause.Input.NORMAL_CONSUMER_MEMBER, 0, 0, seed.value()),
                producerPattern, AEAmount.ONE, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> draft(request, exactExecutions, List.of(normalTypeForCycle, cycle)));

        PatternId normalConsumerPattern = new PatternId("cause-normal-consumer");
        PlannedPatternBatch normalConsumer = normal(14L, root(request), normalConsumerPattern,
                List.of(selection(0, 0, seed, 1L, 1L, Optional.empty())));
        PlannedPatternBatch cycleTypeForNormal = new PlannedPatternBatch(new PlannedBatchId(13L),
                inputCause(normalConsumer.id(), 0, 0, 0, seed.value()), producerPattern, AEAmount.ONE, List.of());
        assertThrows(IllegalArgumentException.class, () -> draft(request,
                Map.of(producerPattern, AEAmount.ONE, normalConsumerPattern, AEAmount.ONE),
                List.of(cycleTypeForNormal, normalConsumer)));

        PlannedPatternBatch wrongSelection = new PlannedPatternBatch(new PlannedBatchId(15L),
                inputCause(normalConsumer.id(), PlannedBatchCause.Input.NORMAL_CONSUMER_MEMBER, 0, 1, seed.value()),
                producerPattern, AEAmount.ONE, List.of());
        assertThrows(IllegalArgumentException.class, () -> draft(request,
                Map.of(producerPattern, AEAmount.ONE, normalConsumerPattern, AEAmount.ONE),
                List.of(wrongSelection, normalConsumer)));
    }

    @Test
    void cycleRejectsNonRingDuplicateKeysAndProducerOutputOwnershipKeyOrAmountMismatch() {
        KeyId seed = new KeyId(0);
        KeyId linkKey = new KeyId(1);
        PatternId firstId = new PatternId("invalid-link-a");
        PatternId secondId = new PatternId("invalid-link-b");
        PlannedCycleMember first = new PlannedCycleMember(firstId, AEAmount.ONE,
                List.of(selection(0, 0, seed, 1L, 1L, Optional.empty())),
                List.of(cycleOutput(0, linkKey, 1L)));
        PlannedCycleMember second = new PlannedCycleMember(secondId, AEAmount.ONE,
                List.of(selection(0, 0, linkKey, 1L, 1L, Optional.empty())),
                List.of(cycleOutput(0, seed, 1L)));
        List<PlannedCycleLink> validLinks = List.of(
                cycleLink(0, 0, 1, 0, 0, linkKey, 1L),
                cycleLink(1, 0, 0, 0, 0, seed, 1L));

        assertThrows(IllegalArgumentException.class,
                () -> cycle(seed, 1L, List.of(first, second), List.of(
                        cycleLink(0, 0, 0, 0, 0, seed, 1L), validLinks.get(1)), Map.of(seed, AEAmount.ONE)));

        PlannedCycleMember duplicateKeyFirst = new PlannedCycleMember(firstId, AEAmount.ONE,
                List.of(selection(0, 0, seed, 1L, 1L, Optional.empty())),
                List.of(cycleOutput(0, seed, 1L)));
        PlannedCycleMember duplicateKeySecond = new PlannedCycleMember(secondId, AEAmount.ONE,
                List.of(selection(0, 0, seed, 1L, 1L, Optional.empty())),
                List.of(cycleOutput(0, seed, 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> cycle(seed, 1L, List.of(duplicateKeyFirst, duplicateKeySecond), List.of(
                        cycleLink(0, 0, 1, 0, 0, seed, 1L), cycleLink(1, 0, 0, 0, 0, seed, 1L)),
                        Map.of(seed, AEAmount.ONE)));

        assertThrows(IllegalArgumentException.class,
                () -> cycle(seed, 1L, List.of(first, second), List.of(
                        cycleLink(0, 1, 1, 0, 0, linkKey, 1L), validLinks.get(1)),
                        Map.of(seed, AEAmount.ONE)));

        PlannedCycleMember wrongKeyProducer = new PlannedCycleMember(firstId, AEAmount.ONE,
                first.inputsPerTurn(), List.of(cycleOutput(0, new KeyId(5), 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> cycle(seed, 1L, List.of(wrongKeyProducer, second), validLinks, Map.of(seed, AEAmount.ONE)));

        PlannedCycleMember amountConsumer = new PlannedCycleMember(secondId, AEAmount.ONE,
                List.of(selection(0, 0, linkKey, 2L, 2L, Optional.empty())), second.outputsPerTurn());
        assertThrows(IllegalArgumentException.class,
                () -> cycle(seed, 1L, List.of(first, amountConsumer), List.of(
                        cycleLink(0, 0, 1, 0, 0, linkKey, 2L), validLinks.get(1)),
                        Map.of(seed, AEAmount.ONE)));
    }

    @Test
    void cycleRequiresExactNonClosingTransferButAllowsClosingOutputGrowth() {
        KeyId seed = new KeyId(0);
        KeyId linkKey = new KeyId(1);
        PlannedCycleMember partialProducer = new PlannedCycleMember(new PatternId("partial-a"), AEAmount.ONE,
                List.of(selection(0, 0, seed, 1L, 1L, Optional.empty())),
                List.of(cycleOutput(0, linkKey, 2L)));
        PlannedCycleMember closing = new PlannedCycleMember(new PatternId("partial-b"), AEAmount.ONE,
                List.of(selection(0, 0, linkKey, 1L, 1L, Optional.empty())),
                List.of(cycleOutput(0, seed, 2L)));
        List<PlannedCycleLink> links = List.of(
                cycleLink(0, 0, 1, 0, 0, linkKey, 1L),
                cycleLink(1, 0, 0, 0, 0, seed, 1L));

        assertThrows(IllegalArgumentException.class,
                () -> cycle(seed, 1L, List.of(partialProducer, closing), links,
                        Map.of(seed, AEAmount.of(2L), linkKey, AEAmount.ONE)));

        PlannedCycleMember exactProducer = new PlannedCycleMember(new PatternId("exact-a"), AEAmount.ONE,
                partialProducer.inputsPerTurn(), List.of(cycleOutput(0, linkKey, 1L)));
        assertDoesNotThrow(() -> cycle(seed, 1L, List.of(exactProducer, closing), links,
                Map.of(seed, AEAmount.of(2L))));
    }

    @Test
    void cycleRejectsAmbiguousMissingShortcutAndRemainderInternalSelections() {
        KeyId seed = new KeyId(0);
        PlannedCycleOutput seedOutput = cycleOutput(0, seed, 2L);
        PlannedCycleLink link = cycleLink(0, 0, 0, 0, 0, seed, 2L);
        PatternId pattern = new PatternId("invalid-input-cycle");

        PlannedCycleMember shortcut = new PlannedCycleMember(pattern, AEAmount.ONE,
                List.of(selection(0, 0, seed, 2L, 1L, Optional.empty())), List.of(seedOutput));
        assertThrows(IllegalArgumentException.class,
                () -> cycle(seed, 2L, List.of(shortcut), List.of(link), Map.of(seed, AEAmount.of(2L))));

        PlannedCycleMember remainder = new PlannedCycleMember(pattern, AEAmount.ONE,
                List.of(selection(0, 0, seed, 2L, 2L,
                        Optional.of(new PlannedRemainderReturn(new KeyId(3), AEAmount.ONE)))),
                List.of(seedOutput));
        assertThrows(IllegalArgumentException.class,
                () -> cycle(seed, 2L, List.of(remainder), List.of(link), Map.of(seed, AEAmount.of(3L))));

        PlannedCycleMember ambiguous = new PlannedCycleMember(pattern, AEAmount.ONE,
                List.of(selection(0, 0, seed, 2L, 2L, Optional.empty()),
                        selection(0, 1, new KeyId(1), 1L, 1L, Optional.empty())),
                List.of(seedOutput));
        assertThrows(IllegalArgumentException.class,
                () -> cycle(seed, 2L, List.of(ambiguous), List.of(link), Map.of(seed, AEAmount.of(2L))));

        PlannedCycleMember missing = new PlannedCycleMember(pattern, AEAmount.ONE, List.of(), List.of(seedOutput));
        assertThrows(IllegalArgumentException.class,
                () -> cycle(seed, 2L, List.of(missing), List.of(link), Map.of(seed, AEAmount.of(2L))));
    }

    @Test
    void cycleRejectsSeedMismatchDuplicateMembersAndForgedExactCredits() {
        KeyId seed = new KeyId(0);
        KeyId output = new KeyId(2);
        KeyId remainderKey = new KeyId(4);
        PatternId pattern = new PatternId("credit-cycle");
        PlannedCycleMember member = new PlannedCycleMember(pattern, AEAmount.ONE,
                List.of(selection(0, 0, seed, 1L, 1L, Optional.empty()),
                        selection(1, 0, new KeyId(3), 1L, 1L,
                                Optional.of(new PlannedRemainderReturn(remainderKey, AEAmount.ONE)))),
                List.of(cycleOutput(0, seed, 2L), cycleOutput(1, output, 3L)));
        PlannedCycleLink link = cycleLink(0, 0, 0, 0, 0, seed, 1L);
        Map<KeyId, AEAmount> exactCredits = Map.of(seed, AEAmount.of(3L), output, AEAmount.of(6L), remainderKey,
                AEAmount.of(2L));

        assertDoesNotThrow(() -> new PlannedCycleBatch(new PlannedBatchId(0L),
                new PlannedBatchCause.Root(output, AEAmount.of(6L)), AEAmount.of(2L), seed, AEAmount.ONE,
                List.of(member), List.of(link), exactCredits));
        assertThrows(IllegalArgumentException.class,
                () -> cycle(new KeyId(5), 1L, List.of(member), List.of(link), exactCredits));

        PlannedCycleMember duplicate = new PlannedCycleMember(pattern, AEAmount.ONE,
                List.of(selection(0, 0, seed, 1L, 1L, Optional.empty())),
                List.of(cycleOutput(0, seed, 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> cycle(seed, 1L, List.of(duplicate, duplicate), List.of(
                        cycleLink(0, 0, 1, 0, 0, seed, 1L), cycleLink(1, 0, 0, 0, 0, seed, 1L)),
                        Map.of(seed, AEAmount.ONE)));

        Map<KeyId, AEAmount> overCredit = Map.of(seed, AEAmount.of(4L), output, AEAmount.of(6L), remainderKey,
                AEAmount.of(2L));
        Map<KeyId, AEAmount> underCredit = Map.of(seed, AEAmount.of(3L), output, AEAmount.of(6L));
        assertThrows(IllegalArgumentException.class,
                () -> cycle(seed, 1L, List.of(member), List.of(link), overCredit));
        assertThrows(IllegalArgumentException.class,
                () -> cycle(seed, 1L, List.of(member), List.of(link), underCredit));
        assertThrows(IllegalArgumentException.class, () -> AEAmount.of(-1L));

        PlannedCycleMember twoSeedInput = new PlannedCycleMember(new PatternId("seed-credit"), AEAmount.ONE,
                List.of(selection(0, 0, seed, 2L, 2L, Optional.empty())),
                List.of(cycleOutput(0, seed, 2L)));
        assertThrows(IllegalArgumentException.class,
                () -> cycle(seed, 2L, List.of(twoSeedInput),
                        List.of(cycleLink(0, 0, 0, 0, 0, seed, 2L)), Map.of(seed, AEAmount.ONE)));
    }

    @Test
    void cycleProductivityGuardSeparatesRootAndInputDemandOnTheSeedKey() {
        KeyId seed = new KeyId(0);
        PlannedBatchId futureConsumer = new PlannedBatchId(99L);
        PlannedCycleMember neutral = new PlannedCycleMember(new PatternId("neutral-cycle"), AEAmount.ONE,
                List.of(selection(0, 0, seed, 1L, 1L, Optional.empty())),
                List.of(cycleOutput(0, seed, 1L)));
        PlannedCycleMember growing = new PlannedCycleMember(new PatternId("growing-cycle"), AEAmount.ONE,
                List.of(selection(0, 0, seed, 1L, 1L, Optional.empty())),
                List.of(cycleOutput(0, seed, 2L)));
        PlannedCycleLink link = cycleLink(0, 0, 0, 0, 0, seed, 1L);

        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleBatch(new PlannedBatchId(0L),
                        new PlannedBatchCause.Root(seed, AEAmount.ONE), AEAmount.ONE, seed, AEAmount.ONE,
                        List.of(neutral), List.of(link), Map.of(seed, AEAmount.ONE)));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleBatch(new PlannedBatchId(0L),
                        new PlannedBatchCause.Input(futureConsumer,
                                PlannedBatchCause.Input.NORMAL_CONSUMER_MEMBER, 0, 0, seed, AEAmount.ONE),
                        AEAmount.ONE, seed, AEAmount.ONE, List.of(neutral), List.of(link),
                        Map.of(seed, AEAmount.ONE)));

        assertDoesNotThrow(() -> new PlannedCycleBatch(new PlannedBatchId(0L),
                new PlannedBatchCause.Root(seed, AEAmount.ONE), AEAmount.ONE, seed, AEAmount.ONE,
                List.of(growing), List.of(link), Map.of(seed, AEAmount.of(2L))));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleBatch(new PlannedBatchId(0L),
                        new PlannedBatchCause.Root(seed, AEAmount.of(2L)), AEAmount.ONE, seed, AEAmount.ONE,
                        List.of(growing), List.of(link), Map.of(seed, AEAmount.of(2L))));

        assertDoesNotThrow(() -> new PlannedCycleBatch(new PlannedBatchId(0L),
                new PlannedBatchCause.Input(futureConsumer, PlannedBatchCause.Input.NORMAL_CONSUMER_MEMBER,
                        0, 0, seed, AEAmount.of(2L)),
                AEAmount.ONE, seed, AEAmount.ONE, List.of(growing), List.of(link),
                Map.of(seed, AEAmount.of(2L))));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleBatch(new PlannedBatchId(0L),
                        new PlannedBatchCause.Input(futureConsumer,
                                PlannedBatchCause.Input.NORMAL_CONSUMER_MEMBER, 0, 0, seed, AEAmount.of(3L)),
                        AEAmount.ONE, seed, AEAmount.ONE, List.of(growing), List.of(link),
                        Map.of(seed, AEAmount.of(2L))));
    }

    @Test
    void schemaRejectsInvalidOrOversizeIndicesAndQuantities() {
        AEAmount aboveLimit = AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS));
        KeyId key = new KeyId(0);

        assertThrows(IllegalArgumentException.class, () -> new PlannedBatchId(-1L));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedBatchCause.Root(key, AEAmount.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedBatchCause.Root(key, aboveLimit));
        assertThrows(IllegalArgumentException.class,
                () -> inputCause(new PlannedBatchId(0L), -2, 0, 0, key.value()));
        assertThrows(IllegalArgumentException.class,
                () -> inputCause(new PlannedBatchId(0L), PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS, 0, 0,
                        key.value()));
        assertThrows(IllegalArgumentException.class,
                () -> inputCause(new PlannedBatchId(0L), 0, PatternLimits.MAX_INPUT_GROUPS, 0, key.value()));
        assertThrows(IllegalArgumentException.class,
                () -> inputCause(new PlannedBatchId(0L), 0, 0, PatternLimits.MAX_CANDIDATES_PER_INPUT,
                        key.value()));

        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleOutput(-1, key, AEAmount.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleOutput(PatternLimits.MAX_OUTPUTS, key, AEAmount.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleOutput(0, key, AEAmount.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleOutput(0, key, aboveLimit));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleLink(-1, 0, 0, 0, 0, key, AEAmount.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleLink(0, PatternLimits.MAX_OUTPUTS, 0, 0, 0, key, AEAmount.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleLink(0, 0, PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS, 0, 0, key,
                        AEAmount.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleLink(0, 0, 0, PatternLimits.MAX_INPUT_GROUPS, 0, key, AEAmount.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleLink(0, 0, 0, 0, PatternLimits.MAX_CANDIDATES_PER_INPUT, key,
                        AEAmount.ONE));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleLink(0, 0, 0, 0, 0, key, aboveLimit));

        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleMember(new PatternId("zero-execution"), AEAmount.ZERO, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleMember(new PatternId("large-execution"), aboveLimit, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleMember(new PatternId("duplicate-output"), AEAmount.ONE, List.of(),
                        List.of(cycleOutput(0, key, 1L), cycleOutput(0, new KeyId(1), 1L))));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleMember(new PatternId("descending-output"), AEAmount.ONE, List.of(),
                        List.of(cycleOutput(1, new KeyId(1), 1L), cycleOutput(0, key, 1L))));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleBatch(new PlannedBatchId(0L),
                        new PlannedBatchCause.Root(key, AEAmount.ONE), AEAmount.ZERO, key, AEAmount.ONE,
                        List.of(), List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleBatch(new PlannedBatchId(0L),
                        new PlannedBatchCause.Root(key, AEAmount.ONE), aboveLimit, key, AEAmount.ONE,
                        List.of(), List.of(), Map.of()));
    }

    @Test
    void outputAndDraftCollectionsPreflightOversizeWithoutIterationOrAllocation() {
        List<PlannedCycleOutput> hostileOutputs = new AbstractList<>() {
            @Override
            public PlannedCycleOutput get(int index) {
                throw new AssertionError("oversize outputs must not be iterated");
            }

            @Override
            public int size() {
                return PatternLimits.MAX_OUTPUTS + 1;
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedCycleMember(new PatternId("oversize-outputs"), AEAmount.ONE, List.of(),
                        hostileOutputs));

        Collection<PlannedCausalStep> hostileSteps = new AbstractCollection<>() {
            @Override
            public Iterator<PlannedCausalStep> iterator() {
                throw new AssertionError("oversize causal steps must not be iterated");
            }

            @Override
            public int size() {
                return PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS + 1;
            }
        };
        ExactCraftRequest request = request(1, 1L);
        GridRevision revision = schemaRevision();
        DependencySet dependencies = new DependencySet.Builder(revision).build();
        assertThrows(IllegalArgumentException.class, () -> new ExactCraftPlanDraft(revision, request, Map.of(),
                Map.of(), Map.of(), hostileSteps, dependencies));
    }

    private static PlannedCycleBatch cycle(KeyId seed, long seedAmount, List<PlannedCycleMember> members,
            List<PlannedCycleLink> links, Map<KeyId, AEAmount> credits) {
        return new PlannedCycleBatch(new PlannedBatchId(0L),
                new PlannedBatchCause.Root(seed, AEAmount.ONE), AEAmount.ONE, seed,
                AEAmount.of(seedAmount), members, links, credits);
    }

    private static PlannedPatternBatch normal(long id, PlannedBatchCause cause, PatternId pattern,
            List<PlannedInputSelection> inputs) {
        return new PlannedPatternBatch(new PlannedBatchId(id), cause, pattern, AEAmount.ONE, inputs);
    }

    private static PlannedBatchCause.Root root(ExactCraftRequest request) {
        return new PlannedBatchCause.Root(request.output(), request.amount());
    }

    private static PlannedBatchCause.Input inputCause(PlannedBatchId consumer, int cycleMember, int inputIndex,
            int candidateIndex, int key) {
        return new PlannedBatchCause.Input(consumer, cycleMember, inputIndex, candidateIndex, new KeyId(key),
                AEAmount.ONE);
    }

    private static PlannedInputSelection selection(int inputIndex, int candidateIndex, KeyId key, long gross,
            long initial, Optional<PlannedRemainderReturn> remainder) {
        return new PlannedInputSelection(inputIndex, candidateIndex, AEAmount.ONE, key, AEAmount.of(gross),
                AEAmount.of(initial), remainder);
    }

    private static PlannedCycleOutput cycleOutput(int index, KeyId key, long amount) {
        return new PlannedCycleOutput(index, key, AEAmount.of(amount));
    }

    private static PlannedCycleLink cycleLink(int producer, int output, int consumer, int input, int candidate,
            KeyId key, long amount) {
        return new PlannedCycleLink(producer, output, consumer, input, candidate, key, AEAmount.of(amount));
    }

    private static ExactCraftPlanDraft draft(ExactCraftRequest request, Map<PatternId, AEAmount> executions,
            Collection<? extends PlannedCausalStep> steps) {
        GridRevision revision = schemaRevision();
        return new ExactCraftPlanDraft(revision, request, executions, Map.of(), Map.of(), steps,
                new DependencySet.Builder(revision).build());
    }

    private static GridRevision schemaRevision() {
        StorageSnapshot storage = snapshot(8, Map.of());
        CompiledPattern pattern = pattern("schema-revision", List.of(), List.of(output(7, 1L, true)));
        return GridRevision.capture(storage, patternSnapshot(List.of(pattern), Map.of(pattern.id(), 0)));
    }

    private static ExactCraftRequest request(int output, long amount) {
        return new ExactCraftRequest(new KeyId(output), AEAmount.of(amount));
    }

    private static ExactCraftPlanDraft success(StorageSnapshot storage, List<CompiledPattern> patterns,
            Map<PatternId, Integer> priorities, ExactCraftRequest request) {
        ExactCraftPlanResult result = PLANNER.plan(request, storage, patternSnapshot(patterns, priorities));
        return assertInstanceOf(ExactCraftPlanResult.Success.class, result).draft();
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

    private static CompiledPattern pattern(String id, List<CompiledInputSpec> inputs,
            List<CompiledOutputSpec> outputs) {
        return new CompiledPattern(new PatternId(id), PatternKind.CRAFTING, inputs, outputs, Optional.empty(),
                new PatternRevision(0L), KEY_GENERATION);
    }

    private static CompiledInputSpec input(AEAmount multiplier, List<CompiledCandidateSpec> candidates,
            SubstitutionPolicy policy) {
        return new CompiledInputSpec(candidates, multiplier, policy);
    }

    private static CompiledCandidateSpec candidate(int key, AEAmount amount,
            Optional<CompiledRemainderSpec> remainder) {
        return new CompiledCandidateSpec(new KeyId(key), amount, remainder);
    }

    private static CompiledRemainderSpec remainder(int key, long amount) {
        return new CompiledRemainderSpec(new KeyId(key), AEAmount.of(amount));
    }

    private static CompiledOutputSpec output(int key, long amount, boolean primary) {
        return new CompiledOutputSpec(new KeyId(key), AEAmount.of(amount), primary);
    }
}

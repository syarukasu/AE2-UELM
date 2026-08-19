package appeng.rebuild.pattern;

import static appeng.rebuild.pattern.GraphTestFixtures.candidateWithRemainder;
import static appeng.rebuild.pattern.GraphTestFixtures.output;
import static appeng.rebuild.pattern.GraphTestFixtures.pattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Deterministic, bounded dependency graph and SCC contract tests. */
class CompiledPatternGraphBuilderTest {
    private static final long KEY_REGISTRY_GENERATION = 91L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(17L);

    @Test
    void emptyGraphIsValidImmutableAndUnknownLookupsAreAbsent() {
        CompiledPatternGraph graph = success(List.of()).graph();

        assertEquals(GRAPH_GENERATION, graph.generation());
        assertEquals(KEY_REGISTRY_GENERATION, graph.keyRegistryGeneration());
        assertTrue(graph.patternsById().isEmpty());
        assertTrue(graph.edgesByConsumer().isEmpty());
        assertTrue(graph.sccComponents().isEmpty());

        PatternId missing = new PatternId("missing");
        assertTrue(graph.pattern(missing).isEmpty());
        assertFalse(graph.containsPattern(missing));
        assertEquals(List.of(), graph.edges(missing));
        assertTrue(graph.classification(missing).isEmpty());
        assertTrue(graph.component(missing).isEmpty());

        assertThrows(UnsupportedOperationException.class, () -> graph.patternsById().clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.edgesByConsumer().clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.sccComponents().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> graph.producerIndex().producers(new KeyId(0)).clear());
    }

    @Test
    void classifiesAcyclicChainAndOrdersLabelledEdgesDeterministically() {
        CompiledPattern first = pattern("a", KEY_REGISTRY_GENERATION, 0);
        CompiledPattern middle = pattern("b", KEY_REGISTRY_GENERATION, 1, 0);
        CompiledPattern last = pattern("c", KEY_REGISTRY_GENERATION, 2, 1);

        CompiledPatternGraph graph = success(List.of(last, first, middle)).graph();

        assertEquals(List.of(), graph.edges(new PatternId("a")));
        assertEquals(List.of(new DependencyEdge(new PatternId("b"), 0, 0, new KeyId(0), new PatternId("a"))),
                graph.edges(new PatternId("b")));
        assertEquals(List.of(new DependencyEdge(new PatternId("c"), 0, 0, new KeyId(1), new PatternId("b"))),
                graph.edges(new PatternId("c")));
        assertEquals(SccClassification.ACYCLIC, graph.classification(new PatternId("a")).orElseThrow());
        assertEquals(SccClassification.ACYCLIC, graph.classification(new PatternId("b")).orElseThrow());
        assertEquals(SccClassification.ACYCLIC, graph.classification(new PatternId("c")).orElseThrow());
        assertEquals(List.of(
                new SccComponent(List.of(new PatternId("a")), SccClassification.ACYCLIC),
                new SccComponent(List.of(new PatternId("b")), SccClassification.ACYCLIC),
                new SccComponent(List.of(new PatternId("c")), SccClassification.ACYCLIC)),
                graph.sccComponents());
    }

    @Test
    void classifiesSelfAndMultiNodeCyclesWithSortedMembers() {
        CompiledPattern self = pattern("self", KEY_REGISTRY_GENERATION, 0, 0);
        CompiledPatternGraph selfGraph = success(List.of(self)).graph();

        assertEquals(SccClassification.CYCLIC_SELF,
                selfGraph.classification(new PatternId("self")).orElseThrow());
        assertEquals(List.of(new DependencyEdge(new PatternId("self"), 0, 0, new KeyId(0),
                new PatternId("self"))), selfGraph.edges(new PatternId("self")));

        CompiledPattern left = pattern("left", KEY_REGISTRY_GENERATION, 0, 1);
        CompiledPattern right = pattern("right", KEY_REGISTRY_GENERATION, 1, 0);
        CompiledPatternGraph multiGraph = success(List.of(right, left)).graph();

        assertEquals(SccClassification.CYCLIC_MULTI,
                multiGraph.classification(new PatternId("left")).orElseThrow());
        assertEquals(SccClassification.CYCLIC_MULTI,
                multiGraph.classification(new PatternId("right")).orElseThrow());
        assertEquals(List.of(new PatternId("left"), new PatternId("right")),
                multiGraph.component(new PatternId("right")).orElseThrow().members());
    }

    @Test
    void retainsCyclicAndAcyclicAlternativeBranches() {
        CompiledPattern storage = pattern("storage", KEY_REGISTRY_GENERATION, 2);
        CompiledPattern cycleA = pattern("cycle-a", KEY_REGISTRY_GENERATION, 0, 1, 2);
        CompiledPattern cycleB = pattern("cycle-b", KEY_REGISTRY_GENERATION, 1, 0);

        GraphBuildResult.Success result = success(List.of(cycleB, storage, cycleA));
        CompiledPatternGraph graph = result.graph();

        assertEquals(List.of(
                new DependencyEdge(new PatternId("cycle-a"), 0, 0, new KeyId(1), new PatternId("cycle-b")),
                new DependencyEdge(new PatternId("cycle-a"), 0, 1, new KeyId(2), new PatternId("storage"))),
                graph.edges(new PatternId("cycle-a")));
        assertEquals(SccClassification.CYCLIC_MULTI,
                graph.classification(new PatternId("cycle-a")).orElseThrow());
        assertEquals(SccClassification.CYCLIC_MULTI,
                graph.classification(new PatternId("cycle-b")).orElseThrow());
        assertEquals(SccClassification.ACYCLIC,
                graph.classification(new PatternId("storage")).orElseThrow());
        assertEquals(List.of(new PatternId("cycle-a"), new PatternId("cycle-b")),
                graph.component(new PatternId("cycle-a")).orElseThrow().members());
    }

    @Test
    void ignoresRemaindersAndSecondaryOutputsWhenBuildingEdges() {
        CompiledPattern inputProducer = pattern("input-producer", KEY_REGISTRY_GENERATION, 10);
        CompiledPattern remainderProducer = pattern("remainder-producer", KEY_REGISTRY_GENERATION, 11);
        CompiledPattern secondarySource = pattern("secondary-source", KEY_REGISTRY_GENERATION,
                List.of(), List.of(output(12, true), output(13, false)));
        CompiledPattern consumer = pattern("consumer", KEY_REGISTRY_GENERATION,
                List.of(new CompiledInputSpec(List.of(
                        candidateWithRemainder(10, 11),
                        GraphTestFixtures.candidate(13)), AEAmount.ONE, SubstitutionPolicy.ALLOW_ALTERNATIVES)),
                List.of(output(14, true)));

        CompiledPatternGraph graph = success(List.of(consumer, secondarySource, remainderProducer, inputProducer))
                .graph();

        assertEquals(List.of(new DependencyEdge(new PatternId("consumer"), 0, 0, new KeyId(10),
                new PatternId("input-producer"))), graph.edges(new PatternId("consumer")));
        assertEquals(List.of(), graph.edges(new PatternId("remainder-producer")));
        assertEquals(List.of(), graph.edges(new PatternId("secondary-source")));
        assertEquals(List.of(new PatternId("remainder-producer")),
                graph.producerIndex().producers(new KeyId(11)));
        assertEquals(List.of(), graph.producerIndex().producers(new KeyId(13)));
    }

    @Test
    void retainsAllPrimaryProducersAndIgnoresSourceCollectionPermutation() {
        CompiledPattern producerZulu = pattern("producer-zulu", KEY_REGISTRY_GENERATION, 20);
        CompiledPattern producerAlpha = pattern("producer-alpha", KEY_REGISTRY_GENERATION, 20);
        CompiledPattern consumer = pattern("consumer", KEY_REGISTRY_GENERATION, 21, 20);
        List<CompiledPattern> source = new ArrayList<>(List.of(producerZulu, consumer, producerAlpha));

        CompiledPatternGraph first = success(source).graph();
        Collections.reverse(source);
        CompiledPatternGraph second = success(source).graph();

        assertEquals(List.of(new PatternId("producer-alpha"), new PatternId("producer-zulu")),
                first.producerIndex().producers(new KeyId(20)));
        assertEquals(first.patternsById(), second.patternsById());
        assertEquals(first.edgesByConsumer(), second.edgesByConsumer());
        assertEquals(first.sccComponents(), second.sccComponents());
        assertEquals(first.edges(new PatternId("consumer")), second.edges(new PatternId("consumer")));
        for (PatternId id : first.patternsById().keySet()) {
            assertEquals(first.classification(id), second.classification(id));
            assertEquals(first.component(id), second.component(id));
        }
    }

    @Test
    void sourceMutationCannotAffectGraphAndReturnedCollectionsAreImmutable() {
        CompiledPattern first = pattern("first", KEY_REGISTRY_GENERATION, 0);
        CompiledPattern second = pattern("second", KEY_REGISTRY_GENERATION, 1, 0);
        List<CompiledPattern> source = new ArrayList<>(List.of(first, second));
        CompiledPatternGraph graph = success(source).graph();

        source.clear();
        source.add(pattern("later", KEY_REGISTRY_GENERATION, 2));
        assertEquals(List.of(new PatternId("first"), new PatternId("second")),
                List.copyOf(graph.patternsById().keySet()));
        assertTrue(graph.containsPattern(new PatternId("first")));
        assertFalse(graph.containsPattern(new PatternId("later")));

        assertThrows(UnsupportedOperationException.class, () -> graph.patternsById().remove(new PatternId("first")));
        assertThrows(UnsupportedOperationException.class, () -> graph.edgesByConsumer().remove(new PatternId("first")));
        assertThrows(UnsupportedOperationException.class, () -> graph.edges(new PatternId("second")).clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.sccComponents().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> graph.sccComponents().get(0).members().clear());
    }

    @Test
    void rejectsDuplicateAndMixedGenerationInputsAsTypedFailuresWithoutPartialGraphs() {
        CompiledPattern duplicateFirst = pattern("duplicate", KEY_REGISTRY_GENERATION, 0);
        CompiledPattern duplicateSecond = pattern("duplicate", KEY_REGISTRY_GENERATION, 1);
        GraphBuildResult.Failure duplicate = failure(List.of(duplicateFirst, duplicateSecond));
        assertEquals(GraphBuildResult.FailureReason.DUPLICATE_PATTERN_ID, duplicate.reason());
        assertEquals("duplicate", duplicate.context());

        CompiledPattern mixed = pattern("mixed", KEY_REGISTRY_GENERATION + 1, 2);
        GraphBuildResult.Failure mixedGeneration = failure(List.of(duplicateFirst, mixed));
        assertEquals(GraphBuildResult.FailureReason.MIXED_KEY_REGISTRY_GENERATION, mixedGeneration.reason());
        assertEquals("mixed", mixedGeneration.context());
    }

    @Test
    void enforcesNodeAndEdgeLimitsAtTheBoundaryAndLimitPlusOne() {
        CompiledPattern first = pattern("first", KEY_REGISTRY_GENERATION, 0);
        CompiledPattern second = pattern("second", KEY_REGISTRY_GENERATION, 1, 0);
        List<CompiledPattern> chain = List.of(first, second);

        GraphBuildResult.Success nodeLimit = build(chain, new CompiledPatternGraphBuilder.Limits(2, 1));
        assertNotNull(nodeLimit.graph());
        GraphBuildResult.Failure nodeLimitPlusOne = failure(chain,
                new CompiledPatternGraphBuilder.Limits(1, 1));
        assertEquals(GraphBuildResult.FailureReason.GRAPH_LIMIT, nodeLimitPlusOne.reason());
        assertEquals("nodes", nodeLimitPlusOne.context());

        GraphBuildResult.Success edgeLimit = build(chain, new CompiledPatternGraphBuilder.Limits(2, 1));
        assertNotNull(edgeLimit.graph());
        GraphBuildResult.Failure edgeLimitPlusOne = failure(chain,
                new CompiledPatternGraphBuilder.Limits(2, 0));
        assertEquals(GraphBuildResult.FailureReason.GRAPH_LIMIT, edgeLimitPlusOne.reason());
        assertEquals("second:edges", edgeLimitPlusOne.context());
    }

    @Test
    void buildsLongChainIterativelyWithoutRecursion() {
        int nodeCount = 10_000;
        List<CompiledPattern> patterns = new ArrayList<>(nodeCount);
        patterns.add(pattern(chainId(0), KEY_REGISTRY_GENERATION, 0));
        for (int index = 1; index < nodeCount; index++) {
            patterns.add(pattern(chainId(index), KEY_REGISTRY_GENERATION, index, index - 1));
        }

        CompiledPatternGraph graph = success(patterns).graph();

        assertEquals(nodeCount, graph.patternsById().size());
        assertEquals(nodeCount - 1,
                graph.edgesByConsumer().values().stream().mapToInt(List::size).sum());
        assertEquals(SccClassification.ACYCLIC,
                graph.classification(new PatternId(chainId(0))).orElseThrow());
        assertEquals(SccClassification.ACYCLIC,
                graph.classification(new PatternId(chainId(nodeCount - 1))).orElseThrow());
    }

    @Test
    void randomizedSmallGraphsMatchIndependentReachabilitySccReference() {
        Random random = new Random(0xA2E5_004BL);
        for (int graphIndex = 0; graphIndex < 256; graphIndex++) {
            int nodeCount = 1 + random.nextInt(8);
            boolean[][] adjacency = new boolean[nodeCount][nodeCount];
            List<CompiledPattern> patterns = new ArrayList<>(nodeCount);
            for (int node = 0; node < nodeCount; node++) {
                List<Integer> targets = new ArrayList<>();
                for (int target = 0; target < nodeCount; target++) {
                    if (random.nextInt(4) == 0) {
                        adjacency[node][target] = true;
                        targets.add(target);
                    }
                }
                int[] targetKeys = targets.stream().mapToInt(Integer::intValue).toArray();
                patterns.add(pattern(randomPatternId(graphIndex, node), KEY_REGISTRY_GENERATION, node, targetKeys));
            }
            Collections.shuffle(patterns, random);

            CompiledPatternGraph graph = success(patterns).graph();
            boolean[][] reachable = transitiveClosure(adjacency);
            int currentGraphIndex = graphIndex;
            for (int node = 0; node < nodeCount; node++) {
                PatternId id = new PatternId(randomPatternId(currentGraphIndex, node));
                Set<Integer> expectedMembers = new TreeSet<>();
                for (int other = 0; other < nodeCount; other++) {
                    if (sameComponent(reachable, node, other)) {
                        expectedMembers.add(other);
                    }
                }
                List<PatternId> expectedMemberIds = expectedMembers.stream()
                        .map(other -> new PatternId(randomPatternId(currentGraphIndex, other))).toList();
                assertEquals(expectedMemberIds, graph.component(id).orElseThrow().members());

                SccClassification expectedClassification = expectedMembers.size() > 1
                        ? SccClassification.CYCLIC_MULTI
                        : adjacency[node][node] ? SccClassification.CYCLIC_SELF : SccClassification.ACYCLIC;
                assertEquals(expectedClassification, graph.classification(id).orElseThrow());
            }
        }
    }

    private static GraphBuildResult.Success success(List<CompiledPattern> patterns) {
        return build(patterns, new CompiledPatternGraphBuilder.Limits(
                PatternLimits.MAX_GRAPH_NODES, PatternLimits.MAX_GRAPH_EDGES));
    }

    private static GraphBuildResult.Success build(List<CompiledPattern> patterns,
            CompiledPatternGraphBuilder.Limits limits) {
        GraphBuildResult result = new CompiledPatternGraphBuilder(GRAPH_GENERATION, KEY_REGISTRY_GENERATION, limits)
                .build(patterns);
        return assertInstanceOf(GraphBuildResult.Success.class, result);
    }

    private static GraphBuildResult.Failure failure(List<CompiledPattern> patterns) {
        GraphBuildResult result = new CompiledPatternGraphBuilder(GRAPH_GENERATION, KEY_REGISTRY_GENERATION,
                new CompiledPatternGraphBuilder.Limits(PatternLimits.MAX_GRAPH_NODES, PatternLimits.MAX_GRAPH_EDGES))
                .build(patterns);
        return assertInstanceOf(GraphBuildResult.Failure.class, result);
    }

    private static GraphBuildResult.Failure failure(List<CompiledPattern> patterns,
            CompiledPatternGraphBuilder.Limits limits) {
        GraphBuildResult result = new CompiledPatternGraphBuilder(GRAPH_GENERATION, KEY_REGISTRY_GENERATION, limits)
                .build(patterns);
        return assertInstanceOf(GraphBuildResult.Failure.class, result);
    }

    private static String chainId(int index) {
        return "node-" + String.format(Locale.ROOT, "%05d", index);
    }

    private static String randomPatternId(int graphIndex, int node) {
        return "random-" + graphIndex + "-" + node;
    }

    private static boolean[][] transitiveClosure(boolean[][] adjacency) {
        int nodeCount = adjacency.length;
        boolean[][] reachable = new boolean[nodeCount][nodeCount];
        for (int source = 0; source < nodeCount; source++) {
            System.arraycopy(adjacency[source], 0, reachable[source], 0, nodeCount);
            reachable[source][source] = true;
        }
        for (int through = 0; through < nodeCount; through++) {
            for (int source = 0; source < nodeCount; source++) {
                if (!reachable[source][through]) {
                    continue;
                }
                for (int target = 0; target < nodeCount; target++) {
                    reachable[source][target] |= reachable[through][target];
                }
            }
        }
        return reachable;
    }

    private static boolean sameComponent(boolean[][] reachable, int left, int right) {
        return reachable[left][right] && reachable[right][left];
    }
}

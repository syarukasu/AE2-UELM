package appeng.rebuild.pattern;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Bounded, deterministic builder for immutable compiled pattern graph metadata. */
public final class CompiledPatternGraphBuilder {
    private final GraphGeneration graphGeneration;
    private final long keyRegistryGeneration;
    private final Limits limits;

    public CompiledPatternGraphBuilder(GraphGeneration graphGeneration, long keyRegistryGeneration) {
        this(graphGeneration, keyRegistryGeneration,
                new Limits(PatternLimits.MAX_GRAPH_NODES, PatternLimits.MAX_GRAPH_EDGES));
    }

    CompiledPatternGraphBuilder(GraphGeneration graphGeneration, long keyRegistryGeneration, Limits limits) {
        this.graphGeneration = Objects.requireNonNull(graphGeneration, "graphGeneration");
        if (keyRegistryGeneration < 0) {
            throw new IllegalArgumentException(
                    "Key registry generation must be non-negative: " + keyRegistryGeneration);
        }
        this.keyRegistryGeneration = keyRegistryGeneration;
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public GraphBuildResult build(Collection<CompiledPattern> sourcePatterns) {
        Objects.requireNonNull(sourcePatterns, "sourcePatterns");
        if (sourcePatterns.size() > limits.maxNodes) {
            return GraphBuildResult.failure(GraphBuildResult.FailureReason.GRAPH_LIMIT, "nodes");
        }

        TreeMap<PatternId, CompiledPattern> sortedPatterns = new TreeMap<>();
        for (CompiledPattern pattern : sourcePatterns) {
            Objects.requireNonNull(pattern, "sourcePatterns cannot contain null");
            if (pattern.keyRegistryGeneration() != keyRegistryGeneration) {
                return GraphBuildResult.failure(GraphBuildResult.FailureReason.MIXED_KEY_REGISTRY_GENERATION,
                        pattern.id().value());
            }
            if (sortedPatterns.putIfAbsent(pattern.id(), pattern) != null) {
                return GraphBuildResult.failure(GraphBuildResult.FailureReason.DUPLICATE_PATTERN_ID,
                        pattern.id().value());
            }
        }

        ProducerIndex producerIndex = new ProducerIndex(keyRegistryGeneration, sortedPatterns.values());
        Map<PatternId, List<DependencyEdge>> edges = new LinkedHashMap<>();
        int edgeCount = 0;
        for (CompiledPattern pattern : sortedPatterns.values()) {
            List<DependencyEdge> consumerEdges = new ArrayList<>();
            for (int inputIndex = 0; inputIndex < pattern.inputs().size(); inputIndex++) {
                CompiledInputSpec input = pattern.inputs().get(inputIndex);
                for (int candidateIndex = 0; candidateIndex < input.candidates().size(); candidateIndex++) {
                    CompiledCandidateSpec candidate = input.candidates().get(candidateIndex);
                    for (PatternId producer : producerIndex.producers(candidate.key())) {
                        if (edgeCount == limits.maxEdges) {
                            return GraphBuildResult.failure(GraphBuildResult.FailureReason.GRAPH_LIMIT,
                                    pattern.id().value() + ":edges");
                        }
                        consumerEdges.add(new DependencyEdge(pattern.id(), inputIndex, candidateIndex, candidate.key(),
                                producer));
                        edgeCount++;
                    }
                }
            }
            edges.put(pattern.id(), List.copyOf(consumerEdges));
        }

        SccMetadata sccMetadata = classifyIteratively(sortedPatterns.keySet(), edges);
        return GraphBuildResult.success(new CompiledPatternGraph(graphGeneration, keyRegistryGeneration, sortedPatterns,
                producerIndex, edges, sccMetadata.components, sccMetadata.classifications,
                sccMetadata.componentsByPattern));
    }

    private static SccMetadata classifyIteratively(Collection<PatternId> nodes,
            Map<PatternId, List<DependencyEdge>> edges) {
        Map<PatternId, List<PatternId>> forward = new TreeMap<>();
        Map<PatternId, List<PatternId>> reverse = new TreeMap<>();
        for (PatternId node : nodes) {
            forward.put(node, new ArrayList<>());
            reverse.put(node, new ArrayList<>());
        }
        for (Map.Entry<PatternId, List<DependencyEdge>> entry : edges.entrySet()) {
            for (DependencyEdge edge : entry.getValue()) {
                forward.get(edge.consumer()).add(edge.producer());
                reverse.get(edge.producer()).add(edge.consumer());
            }
        }
        forward.values().forEach(list -> Collections.sort(list));
        reverse.values().forEach(list -> Collections.sort(list));

        Set<PatternId> visited = new HashSet<>();
        List<PatternId> finishOrder = new ArrayList<>(nodes.size());
        for (PatternId start : nodes) {
            if (visited.add(start)) {
                finishForward(start, forward, visited, finishOrder);
            }
        }

        Set<PatternId> assigned = new HashSet<>();
        List<SccComponent> components = new ArrayList<>();
        Map<PatternId, SccClassification> classifications = new HashMap<>();
        Map<PatternId, SccComponent> componentsByPattern = new HashMap<>();
        for (int index = finishOrder.size() - 1; index >= 0; index--) {
            PatternId start = finishOrder.get(index);
            if (!assigned.add(start)) {
                continue;
            }
            TreeSet<PatternId> members = collectReverse(start, reverse, assigned);
            SccClassification classification = classify(members, forward);
            SccComponent component = new SccComponent(List.copyOf(members), classification);
            components.add(component);
            for (PatternId member : members) {
                classifications.put(member, classification);
                componentsByPattern.put(member, component);
            }
        }
        components.sort((left, right) -> left.members().get(0).compareTo(right.members().get(0)));
        return new SccMetadata(components, classifications, componentsByPattern);
    }

    private static void finishForward(PatternId start, Map<PatternId, List<PatternId>> forward, Set<PatternId> visited,
            List<PatternId> finishOrder) {
        ArrayDeque<TraversalFrame> stack = new ArrayDeque<>();
        stack.push(new TraversalFrame(start));
        while (!stack.isEmpty()) {
            TraversalFrame frame = stack.peek();
            List<PatternId> neighbors = forward.get(frame.pattern);
            if (frame.nextNeighbor < neighbors.size()) {
                PatternId neighbor = neighbors.get(frame.nextNeighbor++);
                if (visited.add(neighbor)) {
                    stack.push(new TraversalFrame(neighbor));
                }
            } else {
                finishOrder.add(frame.pattern);
                stack.pop();
            }
        }
    }

    private static TreeSet<PatternId> collectReverse(PatternId start, Map<PatternId, List<PatternId>> reverse,
            Set<PatternId> assigned) {
        TreeSet<PatternId> members = new TreeSet<>();
        ArrayDeque<PatternId> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            PatternId pattern = stack.pop();
            members.add(pattern);
            for (PatternId neighbor : reverse.get(pattern)) {
                if (assigned.add(neighbor)) {
                    stack.push(neighbor);
                }
            }
        }
        return members;
    }

    private static SccClassification classify(TreeSet<PatternId> members, Map<PatternId, List<PatternId>> forward) {
        if (members.size() > 1) {
            return SccClassification.CYCLIC_MULTI;
        }
        PatternId onlyMember = members.first();
        return forward.get(onlyMember).contains(onlyMember) ? SccClassification.CYCLIC_SELF : SccClassification.ACYCLIC;
    }

    record Limits(int maxNodes, int maxEdges) {
        Limits {
            if (maxNodes < 0 || maxEdges < 0) {
                throw new IllegalArgumentException("Graph limits must be non-negative");
            }
        }
    }

    private static final class TraversalFrame {
        private final PatternId pattern;
        private int nextNeighbor;

        private TraversalFrame(PatternId pattern) {
            this.pattern = pattern;
        }
    }

    private record SccMetadata(List<SccComponent> components, Map<PatternId, SccClassification> classifications,
            Map<PatternId, SccComponent> componentsByPattern) {
    }
}

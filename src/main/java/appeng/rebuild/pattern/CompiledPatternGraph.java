package appeng.rebuild.pattern;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable generation-scoped compiled dependency graph and iterative SCC metadata. */
public final class CompiledPatternGraph {
    private final GraphGeneration generation;
    private final long keyRegistryGeneration;
    private final Map<PatternId, CompiledPattern> patternsById;
    private final ProducerIndex producerIndex;
    private final Map<PatternId, List<DependencyEdge>> edgesByConsumer;
    private final List<SccComponent> sccComponents;
    private final Map<PatternId, SccClassification> classifications;
    private final Map<PatternId, SccComponent> componentsByPattern;

    CompiledPatternGraph(GraphGeneration generation, long keyRegistryGeneration,
            Map<PatternId, CompiledPattern> patternsById, ProducerIndex producerIndex,
            Map<PatternId, List<DependencyEdge>> edgesByConsumer, List<SccComponent> sccComponents,
            Map<PatternId, SccClassification> classifications, Map<PatternId, SccComponent> componentsByPattern) {
        this.generation = Objects.requireNonNull(generation, "generation");
        if (keyRegistryGeneration < 0) {
            throw new IllegalArgumentException(
                    "Key registry generation must be non-negative: " + keyRegistryGeneration);
        }
        this.keyRegistryGeneration = keyRegistryGeneration;
        this.patternsById = Collections
                .unmodifiableMap(new TreeMap<>(Objects.requireNonNull(patternsById, "patternsById")));
        this.producerIndex = Objects.requireNonNull(producerIndex, "producerIndex");
        Map<PatternId, List<DependencyEdge>> copiedEdges = new LinkedHashMap<>();
        for (Map.Entry<PatternId, List<DependencyEdge>> entry : Objects.requireNonNull(edgesByConsumer,
                "edgesByConsumer").entrySet()) {
            copiedEdges.put(Objects.requireNonNull(entry.getKey(), "edgesByConsumer cannot contain null keys"),
                    List.copyOf(
                            Objects.requireNonNull(entry.getValue(), "edgesByConsumer cannot contain null values")));
        }
        this.edgesByConsumer = Collections.unmodifiableMap(copiedEdges);
        this.sccComponents = List.copyOf(Objects.requireNonNull(sccComponents, "sccComponents"));
        this.classifications = Collections
                .unmodifiableMap(new TreeMap<>(Objects.requireNonNull(classifications, "classifications")));
        this.componentsByPattern = Collections.unmodifiableMap(
                new TreeMap<>(Objects.requireNonNull(componentsByPattern, "componentsByPattern")));
    }

    public GraphGeneration generation() {
        return generation;
    }

    public long keyRegistryGeneration() {
        return keyRegistryGeneration;
    }

    public Map<PatternId, CompiledPattern> patternsById() {
        return patternsById;
    }

    public Optional<CompiledPattern> pattern(PatternId patternId) {
        return Optional.ofNullable(patternsById.get(Objects.requireNonNull(patternId, "patternId")));
    }

    public boolean containsPattern(PatternId patternId) {
        return patternsById.containsKey(Objects.requireNonNull(patternId, "patternId"));
    }

    public ProducerIndex producerIndex() {
        return producerIndex;
    }

    /**
     * Returns the consumer's dependency edges, or an empty immutable list when the consumer is absent or has no edges.
     * Use {@link #containsPattern(PatternId)} to distinguish those cases.
     */
    public List<DependencyEdge> edges(PatternId consumer) {
        return edgesByConsumer.getOrDefault(Objects.requireNonNull(consumer, "consumer"), List.of());
    }

    public Map<PatternId, List<DependencyEdge>> edgesByConsumer() {
        return edgesByConsumer;
    }

    public Optional<SccClassification> classification(PatternId pattern) {
        return Optional.ofNullable(classifications.get(Objects.requireNonNull(pattern, "pattern")));
    }

    public Optional<SccComponent> component(PatternId pattern) {
        return Optional.ofNullable(componentsByPattern.get(Objects.requireNonNull(pattern, "pattern")));
    }

    public List<SccComponent> sccComponents() {
        return sccComponents;
    }
}

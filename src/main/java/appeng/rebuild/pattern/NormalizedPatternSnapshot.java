package appeng.rebuild.pattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable, server-thread-produced normalized shadow for one grid recipe revision.
 *
 * <p>
 * This metadata has no level, grid, node, or provider references and remains observational until a later planner phase
 * explicitly consumes it.
 */
public final class NormalizedPatternSnapshot {
    private final long serverGeneration;
    private final RecipeRevision recipeRevision;
    private final long keyRegistryGeneration;
    private final Map<PatternId, CompiledPattern> patternsById;
    private final CompiledPatternGraph graph;
    private final Map<PatternId, Integer> maxProviderPriorities;
    private final int eligiblePhysicalBindingCount;
    private final boolean hasLegacyFallback;
    private final List<NormalizedPatternDiagnostic> diagnostics;

    public NormalizedPatternSnapshot(long serverGeneration, RecipeRevision recipeRevision, long keyRegistryGeneration,
            Map<PatternId, CompiledPattern> patternsById, CompiledPatternGraph graph,
            Map<PatternId, Integer> maxProviderPriorities,
            int eligiblePhysicalBindingCount, boolean hasLegacyFallback,
            List<NormalizedPatternDiagnostic> diagnostics) {
        if (serverGeneration < 0 || keyRegistryGeneration < 0) {
            throw new IllegalArgumentException("Generations must be non-negative");
        }
        this.serverGeneration = serverGeneration;
        this.recipeRevision = Objects.requireNonNull(recipeRevision, "recipeRevision");
        this.keyRegistryGeneration = keyRegistryGeneration;
        this.patternsById = copyPatterns(patternsById, keyRegistryGeneration);
        this.graph = Objects.requireNonNull(graph, "graph");
        if (graph.keyRegistryGeneration() != keyRegistryGeneration || !graph.patternsById().equals(this.patternsById)) {
            throw new IllegalArgumentException(
                    "Normalized graph is incompatible with snapshot patterns or key generation");
        }
        this.maxProviderPriorities = copyPriorities(maxProviderPriorities, this.patternsById);
        if (eligiblePhysicalBindingCount < 0
                || eligiblePhysicalBindingCount > PatternLimits.MAX_NORMALIZED_PATTERN_BINDINGS_PER_GRID) {
            throw new IllegalArgumentException("Eligible physical binding count is out of bounds");
        }
        if (eligiblePhysicalBindingCount < this.patternsById.size()) {
            throw new IllegalArgumentException("Eligible physical binding count cannot be below normalized patterns");
        }
        this.eligiblePhysicalBindingCount = eligiblePhysicalBindingCount;
        this.diagnostics = copyDiagnostics(diagnostics);
        if (hasLegacyFallback != !this.diagnostics.isEmpty()) {
            throw new IllegalArgumentException("Legacy fallback flag must match fallback diagnostics");
        }
        this.hasLegacyFallback = hasLegacyFallback;
    }

    public long serverGeneration() {
        return serverGeneration;
    }

    public RecipeRevision recipeRevision() {
        return recipeRevision;
    }

    public long keyRegistryGeneration() {
        return keyRegistryGeneration;
    }

    /** PatternId-lexicographically sorted immutable patterns. */
    public Map<PatternId, CompiledPattern> patternsById() {
        return patternsById;
    }

    /** Immutable compiled graph built from exactly {@link #patternsById()}. */
    public CompiledPatternGraph graph() {
        return graph;
    }

    /** PatternId-lexicographically sorted immutable maximum legacy provider priorities. */
    public Map<PatternId, Integer> maxProviderPriorities() {
        return maxProviderPriorities;
    }

    public int eligiblePhysicalBindingCount() {
        return eligiblePhysicalBindingCount;
    }

    public boolean hasLegacyFallback() {
        return hasLegacyFallback;
    }

    public List<NormalizedPatternDiagnostic> diagnostics() {
        return diagnostics;
    }

    private static Map<PatternId, CompiledPattern> copyPatterns(Map<PatternId, CompiledPattern> patterns,
            long keyRegistryGeneration) {
        Objects.requireNonNull(patterns, "patternsById");
        if (patterns.size() > PatternLimits.MAX_NORMALIZED_PATTERNS_PER_GRID) {
            throw new IllegalArgumentException("Too many normalized patterns");
        }
        TreeMap<PatternId, CompiledPattern> result = new TreeMap<>();
        for (Map.Entry<PatternId, CompiledPattern> entry : patterns.entrySet()) {
            PatternId id = Objects.requireNonNull(entry.getKey(), "patternsById cannot contain null ids");
            CompiledPattern pattern = Objects.requireNonNull(entry.getValue(),
                    "patternsById cannot contain null values");
            if (!id.equals(pattern.id()) || pattern.keyRegistryGeneration() != keyRegistryGeneration) {
                throw new IllegalArgumentException("Normalized pattern has incompatible identity or key generation");
            }
            result.put(id, pattern);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<PatternId, Integer> copyPriorities(Map<PatternId, Integer> priorities,
            Map<PatternId, CompiledPattern> patterns) {
        Objects.requireNonNull(priorities, "maxProviderPriorities");
        if (!priorities.keySet().equals(patterns.keySet())) {
            throw new IllegalArgumentException("Every normalized pattern requires exactly one provider priority");
        }
        TreeMap<PatternId, Integer> result = new TreeMap<>();
        for (Map.Entry<PatternId, Integer> entry : priorities.entrySet()) {
            result.put(Objects.requireNonNull(entry.getKey(), "maxProviderPriorities cannot contain null ids"),
                    Objects.requireNonNull(entry.getValue(), "maxProviderPriorities cannot contain null values"));
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<NormalizedPatternDiagnostic> copyDiagnostics(List<NormalizedPatternDiagnostic> diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (diagnostics.size() > PatternLimits.MAX_NORMALIZED_PATTERN_DIAGNOSTICS) {
            throw new IllegalArgumentException("Too many normalized pattern diagnostics");
        }
        List<NormalizedPatternDiagnostic> result = new ArrayList<>(diagnostics.size());
        for (NormalizedPatternDiagnostic diagnostic : diagnostics) {
            result.add(Objects.requireNonNull(diagnostic, "diagnostics cannot contain null"));
        }
        result.sort(Comparator.comparing((NormalizedPatternDiagnostic diagnostic) -> diagnostic.reason().name())
                .thenComparing(NormalizedPatternDiagnostic::context));
        return List.copyOf(result);
    }
}

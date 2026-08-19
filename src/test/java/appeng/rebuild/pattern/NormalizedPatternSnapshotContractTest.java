package appeng.rebuild.pattern;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Immutable normalized-shadow identity checks pending graph publication integration. */
class NormalizedPatternSnapshotContractTest {
    @Test
    void snapshotRejectsPatternMapIdentityMismatch() {
        CompiledPattern pattern = GraphTestFixtures.pattern("actual", 7L, 0);
        CompiledPattern other = GraphTestFixtures.pattern("other", 7L, 0);
        CompiledPatternGraph graph = graph(pattern, 7L);

        assertThrows(IllegalArgumentException.class, () -> new NormalizedPatternSnapshot(
                1L, RecipeRevision.ZERO, 7L,
                Map.of(other.id(), other), graph, Map.of(other.id(), 0),
                1, false, List.of()));
    }

    @Test
    void snapshotRejectsMismatchedGraphKeyRegistryGeneration() {
        CompiledPattern pattern = GraphTestFixtures.pattern("actual", 7L, 0);
        CompiledPatternGraph graph = graph(pattern, 7L);

        assertThrows(IllegalArgumentException.class, () -> new NormalizedPatternSnapshot(
                1L, RecipeRevision.ZERO, 8L,
                Map.of(pattern.id(), pattern), graph, Map.of(pattern.id(), 0),
                1, false, List.of()));
    }

    @Test
    void snapshotMapsAndDiagnosticsAreImmutable() {
        CompiledPattern pattern = GraphTestFixtures.pattern("actual", 7L, 0);
        CompiledPatternGraph graph = graph(pattern, 7L);
        NormalizedPatternSnapshot snapshot = new NormalizedPatternSnapshot(
                1L, RecipeRevision.ZERO, 7L,
                Map.of(pattern.id(), pattern), graph, Map.of(pattern.id(), 0),
                1, true, List.of(new NormalizedPatternDiagnostic(
                        NormalizedPatternDiagnostic.Reason.LEGACY_FALLBACK, "custom-pattern")));

        assertTrue(snapshot.patternsById().get(pattern.id()).equals(pattern));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.patternsById().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.maxProviderPriorities().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.diagnostics().clear());
    }

    private static CompiledPatternGraph graph(CompiledPattern pattern, long keyRegistryGeneration) {
        return ((GraphBuildResult.Success) new CompiledPatternGraphBuilder(new GraphGeneration(0L),
                keyRegistryGeneration).build(List.of(pattern))).graph();
    }
}

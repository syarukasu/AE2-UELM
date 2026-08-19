package appeng.rebuild.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.CompiledInputSpec;
import appeng.rebuild.pattern.CompiledOutputSpec;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.CompiledPatternGraph;
import appeng.rebuild.pattern.CompiledPatternGraphBuilder;
import appeng.rebuild.pattern.GraphBuildResult;
import appeng.rebuild.pattern.GraphGeneration;
import appeng.rebuild.pattern.NormalizedPatternSnapshot;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternKind;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.pattern.RecipeRevision;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Identity and selective-dependency validation tests for the planner foundation. */
class GridRevisionDependencySetTest {

    @Test
    void capturesMatchingStorageAndPatternIdentityAndRejectsRegistryMismatch() {
        StorageSnapshot storage = storage(7L, new StorageRevision(4L), new long[] { 2L });
        NormalizedPatternSnapshot pattern = patterns(9L, 7L, new GraphGeneration(3L), new RecipeRevision(5L),
                List.of(pattern("a", 7L, 0L)));

        GridRevision revision = GridRevision.capture(storage, pattern);

        assertEquals(new GridRevision(9L, 7L, new GraphGeneration(3L), new RecipeRevision(5L),
                new StorageRevision(4L)), revision);
        NormalizedPatternSnapshot mismatched = patterns(9L, 8L, new GraphGeneration(3L), new RecipeRevision(5L),
                List.of(pattern("a", 8L, 0L)));
        assertThrows(IllegalArgumentException.class, () -> GridRevision.capture(storage, mismatched));
    }

    @Test
    void builderIsIdempotentForSameReadAndRejectsConflictingDuplicateRead() {
        Fixture fixture = fixture();
        DependencySet.Builder builder = new DependencySet.Builder(fixture.revision);

        builder.recordStorageRead(new KeyId(0), 2L);
        builder.recordStorageRead(new KeyId(0), 2L);
        builder.recordPatternRead(fixture.pattern.id(), new PatternRevision(7L));
        builder.recordPatternRead(fixture.pattern.id(), new PatternRevision(7L));

        DependencySet dependencies = builder.build();
        assertEquals(Map.of(new KeyId(0), 2L), dependencies.storageKeyRevisions());
        assertEquals(Map.of(fixture.pattern.id(), new PatternRevision(7L)), dependencies.patternRevisions());
        assertThrows(IllegalArgumentException.class, () -> builder.recordStorageRead(new KeyId(0), 3L));
        assertThrows(IllegalArgumentException.class,
                () -> builder.recordPatternRead(fixture.pattern.id(), new PatternRevision(8L)));
    }

    @Test
    void dependencyMapsAreSortedAndImmutable() {
        Fixture fixture = fixture();
        DependencySet.Builder builder = new DependencySet.Builder(fixture.revision);
        PatternId second = new PatternId("z-pattern");
        CompiledPattern secondPattern = pattern("z-pattern", 7L, 0L);
        NormalizedPatternSnapshot current = patterns(9L, 7L, new GraphGeneration(3L), new RecipeRevision(5L),
                List.of(fixture.pattern, secondPattern));
        builder.recordStorageRead(new KeyId(0), 2L);
        builder.recordPatternRead(second, secondPattern.revision());
        builder.recordPatternRead(fixture.pattern.id(), fixture.pattern.revision());
        DependencySet dependencies = builder.build();

        assertEquals(List.of(fixture.pattern.id(), second), List.copyOf(dependencies.patternRevisions().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> dependencies.storageKeyRevisions().clear());
        assertThrows(UnsupportedOperationException.class, () -> dependencies.patternRevisions().clear());
        assertInstanceOf(DependencyValidationResult.Valid.class,
                dependencies.validate(storage(7L, new StorageRevision(4L), new long[] { 2L }), current));
    }

    @Test
    void validationReportsEveryIdentityReasonBeforeSelectiveReads() {
        Fixture fixture = fixture();
        DependencySet dependencies = dependencies(fixture);

        assertReason(dependencies, storage(7L, new StorageRevision(4L), new long[] { 2L }),
                patterns(10L, 7L, new GraphGeneration(3L), new RecipeRevision(5L), List.of(fixture.pattern)),
                DependencyValidationResult.Reason.SERVER_GENERATION_MISMATCH);
        assertReason(dependencies, storage(7L, new StorageRevision(4L), new long[] { 2L }),
                patterns(9L, 8L, new GraphGeneration(3L), new RecipeRevision(5L), List.of(pattern("a", 8L, 0L))),
                DependencyValidationResult.Reason.KEY_REGISTRY_GENERATION_MISMATCH);
        assertReason(dependencies, storage(7L, new StorageRevision(4L), new long[] { 2L }),
                patterns(9L, 7L, new GraphGeneration(4L), new RecipeRevision(5L), List.of(fixture.pattern)),
                DependencyValidationResult.Reason.GRAPH_GENERATION_MISMATCH);
        assertReason(dependencies, storage(7L, new StorageRevision(4L), new long[] { 2L }),
                patterns(9L, 7L, new GraphGeneration(3L), new RecipeRevision(6L), List.of(fixture.pattern)),
                DependencyValidationResult.Reason.RECIPE_REVISION_MISMATCH);
        assertReason(dependencies, storage(7L, new StorageRevision(3L), new long[] { 2L }),
                patterns(9L, 7L, new GraphGeneration(3L), new RecipeRevision(5L), List.of(fixture.pattern)),
                DependencyValidationResult.Reason.STORAGE_REVISION_REGRESSION);
    }

    @Test
    void validationReportsEveryRecordedStorageAndPatternReason() {
        Fixture fixture = fixture();
        DependencySet dependencies = dependencies(fixture);
        NormalizedPatternSnapshot sameIdentity = patterns(9L, 7L, new GraphGeneration(3L), new RecipeRevision(5L),
                List.of(fixture.pattern));

        assertReason(dependencies, storage(7L, new StorageRevision(4L), new long[0]), sameIdentity,
                DependencyValidationResult.Reason.STORAGE_KEY_ABSENT);
        assertReason(dependencies, storage(7L, new StorageRevision(4L), new long[] { 3L }), sameIdentity,
                DependencyValidationResult.Reason.STORAGE_KEY_REVISION_MISMATCH);
        assertReason(dependencies, storage(7L, new StorageRevision(4L), new long[] { 2L }),
                patterns(9L, 7L, new GraphGeneration(3L), new RecipeRevision(5L), List.of()),
                DependencyValidationResult.Reason.PATTERN_ABSENT);
        assertReason(dependencies, storage(7L, new StorageRevision(4L), new long[] { 2L }),
                patterns(9L, 7L, new GraphGeneration(3L), new RecipeRevision(5L),
                        List.of(pattern("a", 7L, 0L, 8L, new PatternRevision(1L)))),
                DependencyValidationResult.Reason.PATTERN_REVISION_MISMATCH);
    }

    @Test
    void unrelatedGlobalKeyAndPatternChangesRemainValid() {
        Fixture fixture = fixture();
        DependencySet dependencies = dependencies(fixture);
        NormalizedPatternSnapshot currentPatterns = patterns(9L, 7L, new GraphGeneration(3L), new RecipeRevision(5L),
                List.of(fixture.pattern));

        DependencyValidationResult result = dependencies.validate(
                storage(7L, new StorageRevision(5L), new long[] { 2L, 99L }), currentPatterns);

        assertTrue(result instanceof DependencyValidationResult.Valid);
    }

    @Test
    void dependentKeyAndPatternChangesInvalidateWithoutFallback() {
        Fixture fixture = fixture();
        DependencySet dependencies = dependencies(fixture);
        NormalizedPatternSnapshot sameIdentity = patterns(9L, 7L, new GraphGeneration(3L), new RecipeRevision(5L),
                List.of(fixture.pattern));

        assertReason(dependencies, storage(7L, new StorageRevision(5L), new long[] { 3L }), sameIdentity,
                DependencyValidationResult.Reason.STORAGE_KEY_REVISION_MISMATCH);
        assertReason(dependencies, storage(7L, new StorageRevision(5L), new long[] { 2L }),
                patterns(9L, 7L, new GraphGeneration(3L), new RecipeRevision(5L),
                        List.of(pattern("a", 7L, 0L, 8L, new PatternRevision(1L)))),
                DependencyValidationResult.Reason.PATTERN_REVISION_MISMATCH);
    }

    private static DependencySet dependencies(Fixture fixture) {
        DependencySet.Builder builder = new DependencySet.Builder(fixture.revision);
        builder.recordStorageRead(new KeyId(0), 2L);
        builder.recordPatternRead(fixture.pattern.id(), fixture.pattern.revision());
        return builder.build();
    }

    private static void assertReason(DependencySet dependencies, StorageSnapshot storage,
            NormalizedPatternSnapshot patterns, DependencyValidationResult.Reason expected) {
        DependencyValidationResult.Invalid invalid = assertInstanceOf(DependencyValidationResult.Invalid.class,
                dependencies.validate(storage, patterns));
        assertEquals(expected, invalid.reason());
    }

    private static Fixture fixture() {
        CompiledPattern pattern = pattern("a", 7L, 0L);
        NormalizedPatternSnapshot patterns = patterns(9L, 7L, new GraphGeneration(3L), new RecipeRevision(5L),
                List.of(pattern));
        StorageSnapshot storage = storage(7L, new StorageRevision(4L), new long[] { 2L });
        GridRevision revision = GridRevision.capture(storage, patterns);
        return new Fixture(pattern, revision);
    }

    private static StorageSnapshot storage(long generation, StorageRevision revision, long[] keyRevisions) {
        AmountVector amounts = new AmountVector(keyRevisions.length);
        for (int i = 0; i < keyRevisions.length; i++) {
            amounts.set(i, i == 0 ? AEAmount.of(10L) : AEAmount.ZERO);
        }
        return new StorageSnapshot(generation, revision, keyRevisions.length, amounts, keyRevisions);
    }

    private static NormalizedPatternSnapshot patterns(long serverGeneration, long keyRegistryGeneration,
            GraphGeneration graphGeneration, RecipeRevision recipeRevision, List<CompiledPattern> source) {
        CompiledPatternGraph graph = ((GraphBuildResult.Success) new CompiledPatternGraphBuilder(graphGeneration,
                keyRegistryGeneration).build(source)).graph();
        Map<PatternId, CompiledPattern> byId = graph.patternsById();
        Map<PatternId, Integer> priorities = byId.keySet().stream().collect(java.util.stream.Collectors.toMap(id -> id,
                id -> 0));
        return new NormalizedPatternSnapshot(serverGeneration, recipeRevision, keyRegistryGeneration, byId, graph,
                priorities, byId.size(), false, List.of());
    }

    private static CompiledPattern pattern(String id, long generation, long primaryKey) {
        return pattern(id, generation, primaryKey, -1L);
    }

    private static CompiledPattern pattern(String id, long generation, long primaryKey, long inputKey) {
        return pattern(id, generation, primaryKey, inputKey, new PatternRevision(0L));
    }

    private static CompiledPattern pattern(String id, long generation, long primaryKey, long inputKey,
            PatternRevision revision) {
        List<CompiledInputSpec> inputs = inputKey < 0 ? List.of()
                : List.of(new CompiledInputSpec(
                        List.of(new appeng.rebuild.pattern.CompiledCandidateSpec(new KeyId((int) inputKey),
                                AEAmount.ONE,
                                Optional.empty())),
                        AEAmount.ONE, appeng.rebuild.pattern.SubstitutionPolicy.EXACT));
        return new CompiledPattern(new PatternId(id), PatternKind.CRAFTING, inputs,
                List.of(new CompiledOutputSpec(new KeyId((int) primaryKey), AEAmount.ONE, true)), Optional.empty(),
                revision, generation);
    }

    private record Fixture(CompiledPattern pattern, GridRevision revision) {
    }
}

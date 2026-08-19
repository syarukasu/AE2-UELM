package appeng.rebuild.planner;

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.NormalizedPatternSnapshot;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.storage.StorageSnapshot;

/**
 * Immutable, bounded record of only the storage keys and patterns actually read by a future planner operation.
 */
public final class DependencySet {
    private final GridRevision gridRevision;
    private final Map<KeyId, Long> storageKeyRevisions;
    private final Map<PatternId, PatternRevision> patternRevisions;

    private DependencySet(GridRevision gridRevision, Map<KeyId, Long> storageKeyRevisions,
            Map<PatternId, PatternRevision> patternRevisions) {
        this.gridRevision = Objects.requireNonNull(gridRevision, "gridRevision");
        this.storageKeyRevisions = immutableStorageDependencies(storageKeyRevisions);
        this.patternRevisions = immutablePatternDependencies(patternRevisions);
    }

    public GridRevision gridRevision() {
        return gridRevision;
    }

    /** KeyId-sorted recorded aggregate revisions. */
    public Map<KeyId, Long> storageKeyRevisions() {
        return storageKeyRevisions;
    }

    /** PatternId-sorted recorded pattern revisions. */
    public Map<PatternId, PatternRevision> patternRevisions() {
        return patternRevisions;
    }

    /** Validates identity first, then only the keys and patterns actually recorded as reads. */
    public DependencyValidationResult validate(StorageSnapshot storageSnapshot,
            NormalizedPatternSnapshot patternSnapshot) {
        Objects.requireNonNull(storageSnapshot, "storageSnapshot");
        Objects.requireNonNull(patternSnapshot, "patternSnapshot");

        if (patternSnapshot.serverGeneration() != gridRevision.serverGeneration()) {
            return new DependencyValidationResult.Invalid(
                    DependencyValidationResult.Reason.SERVER_GENERATION_MISMATCH);
        }
        if (storageSnapshot.keyRegistryGeneration() != gridRevision.keyRegistryGeneration()
                || patternSnapshot.keyRegistryGeneration() != gridRevision.keyRegistryGeneration()) {
            return new DependencyValidationResult.Invalid(
                    DependencyValidationResult.Reason.KEY_REGISTRY_GENERATION_MISMATCH);
        }
        if (!patternSnapshot.graph().generation().equals(gridRevision.graphGeneration())) {
            return new DependencyValidationResult.Invalid(
                    DependencyValidationResult.Reason.GRAPH_GENERATION_MISMATCH);
        }
        if (!patternSnapshot.recipeRevision().equals(gridRevision.recipeRevision())) {
            return new DependencyValidationResult.Invalid(
                    DependencyValidationResult.Reason.RECIPE_REVISION_MISMATCH);
        }
        if (storageSnapshot.revision().value() < gridRevision.storageRevision().value()) {
            return new DependencyValidationResult.Invalid(
                    DependencyValidationResult.Reason.STORAGE_REVISION_REGRESSION);
        }

        for (Map.Entry<KeyId, Long> dependency : storageKeyRevisions.entrySet()) {
            KeyId key = dependency.getKey();
            if (key.value() >= storageSnapshot.keyCount()) {
                return new DependencyValidationResult.Invalid(DependencyValidationResult.Reason.STORAGE_KEY_ABSENT);
            }
            if (storageSnapshot.keyRevision(key) != dependency.getValue()) {
                return new DependencyValidationResult.Invalid(
                        DependencyValidationResult.Reason.STORAGE_KEY_REVISION_MISMATCH);
            }
        }
        for (Map.Entry<PatternId, PatternRevision> dependency : patternRevisions.entrySet()) {
            CompiledPattern currentPattern = patternSnapshot.patternsById().get(dependency.getKey());
            if (currentPattern == null) {
                return new DependencyValidationResult.Invalid(DependencyValidationResult.Reason.PATTERN_ABSENT);
            }
            if (!currentPattern.revision().equals(dependency.getValue())) {
                return new DependencyValidationResult.Invalid(
                        DependencyValidationResult.Reason.PATTERN_REVISION_MISMATCH);
            }
        }
        return new DependencyValidationResult.Valid();
    }

    public static final class Builder {
        private final GridRevision gridRevision;
        private final Map<KeyId, Long> storageKeyRevisions = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        private final Map<PatternId, PatternRevision> patternRevisions = new TreeMap<>();

        public Builder(GridRevision gridRevision) {
            this.gridRevision = Objects.requireNonNull(gridRevision, "gridRevision");
        }

        /** Records one actual aggregate-key read. Repeating the same observed revision is idempotent. */
        public void recordStorageRead(KeyId key, long observedKeyRevision) {
            Objects.requireNonNull(key, "key");
            if (observedKeyRevision < 0) {
                throw new IllegalArgumentException("Observed storage key revision must be non-negative");
            }
            putDependency(storageKeyRevisions, key, observedKeyRevision, PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS,
                    "storage dependencies");
        }

        /** Records one actual compiled-pattern read. Repeating the same observed revision is idempotent. */
        public void recordPatternRead(PatternId patternId, PatternRevision observedPatternRevision) {
            putDependency(patternRevisions, Objects.requireNonNull(patternId, "patternId"),
                    Objects.requireNonNull(observedPatternRevision, "observedPatternRevision"),
                    PatternLimits.MAX_GRAPH_NODES, "pattern dependencies");
        }

        public DependencySet build() {
            return new DependencySet(gridRevision, storageKeyRevisions, patternRevisions);
        }

        private static <K, V> void putDependency(Map<K, V> dependencies, K key, V revision, int maximum,
                String name) {
            V previous = dependencies.get(key);
            if (previous != null) {
                if (!previous.equals(revision)) {
                    throw new IllegalArgumentException("Conflicting " + name + " revision");
                }
                return;
            }
            if (dependencies.size() >= maximum) {
                throw new IllegalArgumentException("Too many " + name);
            }
            dependencies.put(key, revision);
        }
    }

    private static Map<KeyId, Long> immutableStorageDependencies(Map<KeyId, Long> dependencies) {
        Objects.requireNonNull(dependencies, "storageKeyRevisions");
        if (dependencies.size() > PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS) {
            throw new IllegalArgumentException("Too many storage dependencies");
        }
        TreeMap<KeyId, Long> result = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (Map.Entry<KeyId, Long> entry : dependencies.entrySet()) {
            KeyId key = Objects.requireNonNull(entry.getKey(), "storage dependencies cannot contain null keys");
            Long revision = Objects.requireNonNull(entry.getValue(),
                    "storage dependencies cannot contain null revisions");
            if (revision < 0) {
                throw new IllegalArgumentException("Storage dependency revision must be non-negative");
            }
            result.put(key, revision);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<PatternId, PatternRevision> immutablePatternDependencies(
            Map<PatternId, PatternRevision> dependencies) {
        Objects.requireNonNull(dependencies, "patternRevisions");
        if (dependencies.size() > PatternLimits.MAX_GRAPH_NODES) {
            throw new IllegalArgumentException("Too many pattern dependencies");
        }
        TreeMap<PatternId, PatternRevision> result = new TreeMap<>();
        for (Map.Entry<PatternId, PatternRevision> entry : dependencies.entrySet()) {
            result.put(Objects.requireNonNull(entry.getKey(), "pattern dependencies cannot contain null ids"),
                    Objects.requireNonNull(entry.getValue(), "pattern dependencies cannot contain null revisions"));
        }
        return Collections.unmodifiableMap(result);
    }
}

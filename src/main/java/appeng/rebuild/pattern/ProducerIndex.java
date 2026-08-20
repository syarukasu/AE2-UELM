package appeng.rebuild.pattern;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import appeng.rebuild.key.KeyId;

/** Immutable, generation-scoped reverse index from primary output keys to deterministically sorted producer ids. */
public final class ProducerIndex {
    private final long keyRegistryGeneration;
    private final Map<KeyId, List<PatternId>> producersByKey;

    public ProducerIndex(long keyRegistryGeneration, Collection<CompiledPattern> patterns) {
        if (keyRegistryGeneration < 0) {
            throw new IllegalArgumentException(
                    "Key registry generation must be non-negative: " + keyRegistryGeneration);
        }
        Objects.requireNonNull(patterns, "patterns");
        this.keyRegistryGeneration = keyRegistryGeneration;

        Map<KeyId, TreeSet<PatternId>> mutableProducers = new HashMap<>();
        Set<PatternId> patternIds = new HashSet<>();
        for (CompiledPattern pattern : patterns) {
            Objects.requireNonNull(pattern, "patterns cannot contain null");
            if (pattern.keyRegistryGeneration() != keyRegistryGeneration) {
                throw new IllegalArgumentException("Patterns from mixed key-registry generations are not indexable");
            }
            if (!patternIds.add(pattern.id())) {
                throw new IllegalArgumentException("Duplicate pattern id: " + pattern.id());
            }
            for (CompiledOutputSpec output : pattern.outputs()) {
                if (output.primary()) {
                    mutableProducers.computeIfAbsent(output.key(), ignored -> new TreeSet<>()).add(pattern.id());
                }
            }
        }

        Map<KeyId, List<PatternId>> compiledProducers = new HashMap<>();
        mutableProducers.forEach((key, producers) -> compiledProducers.put(key, List.copyOf(producers)));
        this.producersByKey = Map.copyOf(compiledProducers);
    }

    public long keyRegistryGeneration() {
        return keyRegistryGeneration;
    }

    /** Returns immutable precomputed primary-output producers for {@code key}, with no all-pattern scan. */
    public List<PatternId> producers(KeyId key) {
        Objects.requireNonNull(key, "key");
        List<PatternId> producers = producersByKey.get(key);
        return producers == null ? List.of() : producers;
    }
}

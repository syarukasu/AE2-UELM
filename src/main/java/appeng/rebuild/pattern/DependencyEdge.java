package appeng.rebuild.pattern;

import java.util.Objects;

import appeng.rebuild.key.KeyId;

/** One labelled candidate branch from a consumer pattern to a primary-output producer pattern. */
public record DependencyEdge(PatternId consumer, int inputIndex, int candidateIndex, KeyId candidateKey,
        PatternId producer) {
    public DependencyEdge {
        Objects.requireNonNull(consumer, "consumer");
        if (inputIndex < 0 || candidateIndex < 0) {
            throw new IllegalArgumentException("Input and candidate indexes must be non-negative");
        }
        Objects.requireNonNull(candidateKey, "candidateKey");
        Objects.requireNonNull(producer, "producer");
    }
}

package appeng.rebuild.pattern;

import java.util.Objects;
import java.util.Optional;

/** Immutable read-only state of a grid's observational normalized pattern shadow. */
public record NormalizedPatternShadowState(Status status, long serverGeneration, RecipeRevision recipeRevision,
        Optional<NormalizedPatternSnapshot> snapshot, Optional<NormalizedPatternBuildResult.Failure> failure) {
    public enum Status {
        DISABLED,
        DIRTY,
        ACTIVE
    }

    public NormalizedPatternShadowState {
        Objects.requireNonNull(status, "status");
        if (serverGeneration < 0) {
            throw new IllegalArgumentException("Server generation must be non-negative: " + serverGeneration);
        }
        Objects.requireNonNull(recipeRevision, "recipeRevision");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        failure = Objects.requireNonNull(failure, "failure");
        switch (status) {
            case ACTIVE -> {
                if (snapshot.isEmpty() || failure.isPresent()) {
                    throw new IllegalArgumentException("An active shadow requires a snapshot and no failure");
                }
            }
            case DIRTY -> {
                if (snapshot.isPresent() || failure.isPresent()) {
                    throw new IllegalArgumentException("A dirty shadow cannot expose a snapshot or failure");
                }
            }
            case DISABLED -> {
                if (snapshot.isPresent()) {
                    throw new IllegalArgumentException("A disabled shadow cannot expose a snapshot");
                }
            }
        }
    }
}

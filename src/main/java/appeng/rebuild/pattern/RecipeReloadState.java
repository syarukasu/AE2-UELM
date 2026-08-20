package appeng.rebuild.pattern;

import java.util.Objects;
import java.util.Optional;

/** Immutable query view of one process-scoped recipe reload coordinator. */
public record RecipeReloadState(long serverGeneration, RecipeRevision currentRevision,
        Optional<RecipeRevision> pendingRevision, boolean active, boolean failClosed, int failedGridCount,
        Optional<String> lastFailedGridIdentity) {
    public RecipeReloadState {
        if (serverGeneration < 0) {
            throw new IllegalArgumentException("Server generation must be non-negative: " + serverGeneration);
        }
        Objects.requireNonNull(currentRevision, "currentRevision");
        pendingRevision = Objects.requireNonNull(pendingRevision, "pendingRevision");
        if (failedGridCount < 0) {
            throw new IllegalArgumentException("Failed grid count must be non-negative: " + failedGridCount);
        }
        lastFailedGridIdentity = Objects.requireNonNull(lastFailedGridIdentity, "lastFailedGridIdentity");
    }
}

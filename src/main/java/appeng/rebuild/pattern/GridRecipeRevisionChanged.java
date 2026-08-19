package appeng.rebuild.pattern;

import java.util.Objects;

import appeng.api.networking.events.GridEvent;

/** Internal server-thread event announcing one applied recipe-revision invalidation signal to a grid. */
public final class GridRecipeRevisionChanged extends GridEvent {
    private final RecipeRevision revision;
    private final long serverGeneration;

    public GridRecipeRevisionChanged(RecipeRevision revision, long serverGeneration) {
        this.revision = Objects.requireNonNull(revision, "revision");
        if (serverGeneration < 0) {
            throw new IllegalArgumentException("Server generation must be non-negative: " + serverGeneration);
        }
        this.serverGeneration = serverGeneration;
    }

    public RecipeRevision revision() {
        return revision;
    }

    public long serverGeneration() {
        return serverGeneration;
    }
}

package appeng.rebuild.planner;

import java.util.Objects;

import appeng.rebuild.pattern.GraphGeneration;
import appeng.rebuild.pattern.NormalizedPatternSnapshot;
import appeng.rebuild.pattern.RecipeRevision;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Immutable identity of the grid inputs observed by a future planner operation. */
public record GridRevision(long serverGeneration, long keyRegistryGeneration, GraphGeneration graphGeneration,
        RecipeRevision recipeRevision, StorageRevision storageRevision) {
    public GridRevision {
        if (serverGeneration < 0 || keyRegistryGeneration < 0) {
            throw new IllegalArgumentException("Grid generations must be non-negative");
        }
        Objects.requireNonNull(graphGeneration, "graphGeneration");
        Objects.requireNonNull(recipeRevision, "recipeRevision");
        Objects.requireNonNull(storageRevision, "storageRevision");
    }

    /** Captures a consistent planner identity from matching immutable storage and pattern snapshots. */
    public static GridRevision capture(StorageSnapshot storageSnapshot, NormalizedPatternSnapshot patternSnapshot) {
        Objects.requireNonNull(storageSnapshot, "storageSnapshot");
        Objects.requireNonNull(patternSnapshot, "patternSnapshot");
        if (storageSnapshot.keyRegistryGeneration() != patternSnapshot.keyRegistryGeneration()) {
            throw new IllegalArgumentException("Storage and pattern snapshots have different key-registry generations");
        }
        return new GridRevision(patternSnapshot.serverGeneration(), storageSnapshot.keyRegistryGeneration(),
                patternSnapshot.graph().generation(), patternSnapshot.recipeRevision(), storageSnapshot.revision());
    }
}

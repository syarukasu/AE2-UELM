package appeng.rebuild.pattern;

import appeng.me.Grid;

/**
 * Explicit C2B hand-off for failing a grid's Rebuild shadow after recipe-revision event delivery throws.
 *
 * <p>
 * The callback is synchronous and server-thread-only. It receives the original exception for immediate handling; the
 * coordinator itself stores only bounded identity/count diagnostics and never retries delivery.
 */
@FunctionalInterface
public interface GridRecipeRevisionFailureObserver {
    GridRecipeRevisionFailureObserver NO_OP = (grid, event, failure) -> {
    };

    void onDeliveryFailure(Grid grid, GridRecipeRevisionChanged event, RuntimeException failure);
}

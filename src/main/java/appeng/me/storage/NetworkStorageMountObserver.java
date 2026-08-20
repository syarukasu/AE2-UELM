package appeng.me.storage;

import appeng.api.storage.MEStorage;

/**
 * Receives effective lifecycle changes made by {@link NetworkStorage}.
 *
 * <p>
 * Implementations must not throw. A callback is made only after the corresponding mount list has actually changed;
 * callbacks may therefore be used as invalidation signals rather than as routing hooks.
 */
public interface NetworkStorageMountObserver {
    void mounted(int priority, MEStorage storage);

    void unmounted(int priority, MEStorage storage);
}

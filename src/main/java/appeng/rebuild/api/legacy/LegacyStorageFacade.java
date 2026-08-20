package appeng.rebuild.api.legacy;

import java.util.Objects;

import net.minecraft.network.chat.Component;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.me.storage.NetworkStorage;
import appeng.rebuild.storage.StorageServiceRebuild;

/**
 * Legacy {@link MEStorage} facade over routed network storage and its exact reconstructed view.
 *
 * <p>
 * This facade performs no projection and does not manufacture transfer results. Every positive MODULATE request
 * invalidates the exact view before delegation, even when the delegate returns zero or throws after a partial mutation;
 * reconciliation occurs at the next server end tick.
 */
public final class LegacyStorageFacade implements MEStorage {
    private final NetworkStorage storage;
    private final StorageServiceRebuild exactStorage;

    public LegacyStorageFacade(NetworkStorage storage, StorageServiceRebuild exactStorage) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.exactStorage = Objects.requireNonNull(exactStorage, "exactStorage");
    }

    @Override
    public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
        return storage.isPreferredStorageFor(what, source);
    }

    /**
     * Conservatively invalidates the exact view before every positive MODULATE request, including a zero-result or
     * throwing delegate call.
     */
    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        MEStorage.checkPreconditions(what, amount, mode, source);
        if (mode == Actionable.MODULATE && amount > 0) {
            exactStorage.markDirty();
        }
        return validateTransferredAmount(storage.insert(what, amount, mode, source), amount, "insert");
    }

    /**
     * Conservatively invalidates the exact view before every positive MODULATE request, including a zero-result or
     * throwing delegate call.
     */
    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        MEStorage.checkPreconditions(what, amount, mode, source);
        if (mode == Actionable.MODULATE && amount > 0) {
            exactStorage.markDirty();
        }
        return validateTransferredAmount(storage.extract(what, amount, mode, source), amount, "extract");
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        storage.getAvailableStacks(out);
    }

    @Override
    public Component getDescription() {
        return storage.getDescription();
    }

    private static long validateTransferredAmount(long transferred, long requested, String operation) {
        if (transferred < 0 || transferred > requested) {
            throw new IllegalStateException(
                    "Network storage " + operation + " returned " + transferred + " for request " + requested);
        }
        return transferred;
    }
}

package appeng.rebuild.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.me.storage.NetworkStorageMountObserver;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;

/**
 * Server-thread-owned exact-storage view reconstructed from mounted legacy {@link MEStorage} delegates.
 *
 * <p>
 * Mount lifecycle changes merely invalidate this view. {@link #reconcileEndTick()} is the sole operation that captures
 * legacy contents and publishes them to the exact ledger; callers cannot consume the previous exact state while a
 * capture is required or has failed.
 */
public final class StorageServiceRebuild implements NetworkStorageMountObserver {
    private final KeyRegistry keyRegistry;
    private final StorageLedger ledger;
    private final List<MountedLocation> mountedLocations = new ArrayList<>();
    private Set<StorageLocationId> committedLocations = Set.of();

    private long nextLocationId;
    private boolean valid;
    private boolean dirty = true;
    private boolean lifecycleInconsistent;
    @Nullable
    private Throwable lastFailure;

    public StorageServiceRebuild(KeyRegistry keyRegistry) {
        this.keyRegistry = Objects.requireNonNull(keyRegistry, "keyRegistry");
        this.ledger = new StorageLedger(keyRegistry);
    }

    /** Invalidates exact queries after an effective legacy mount. */
    @Override
    public void mounted(int priority, MEStorage storage) {
        Objects.requireNonNull(storage, "storage");
        long followingLocationId;
        try {
            followingLocationId = Math.incrementExact(nextLocationId);
        } catch (ArithmeticException failure) {
            markLifecycleInconsistent(failure);
            return;
        }
        mountedLocations.add(new MountedLocation(new StorageLocationId(nextLocationId), priority, storage));
        nextLocationId = followingLocationId;
        markDirty();
    }

    /** Invalidates exact queries after removing one identity- and priority-matched legacy mount. */
    @Override
    public void unmounted(int priority, MEStorage storage) {
        Objects.requireNonNull(storage, "storage");
        Iterator<MountedLocation> iterator = mountedLocations.iterator();
        while (iterator.hasNext()) {
            MountedLocation location = iterator.next();
            if (location.priority == priority && location.storage == storage) {
                iterator.remove();
                markDirty();
                return;
            }
        }
        markLifecycleInconsistent(new IllegalStateException("Unmounted storage did not have a matching mount record"));
    }

    /** Invalidates exact queries after an out-of-band legacy storage change. */
    public void markDirty() {
        dirty = true;
        valid = false;
    }

    /**
     * Captures every mounted storage exactly once and atomically reconciles the exact ledger.
     *
     * <p>
     * A failed capture leaves the previously committed ledger and committed location set untouched. The next explicit
     * invocation is the only retry; this method does not loop or fall back to a combined legacy network counter.
     */
    public boolean reconcileEndTick() {
        if (lifecycleInconsistent) {
            valid = false;
            dirty = true;
            return false;
        }
        try {
            List<CapturedLocation> capturedLocations = captureMountedLocations();
            Map<StorageLocationId, StorageLocationSnapshot> replacements = materializeSnapshots(capturedLocations);
            Set<StorageLocationId> removals = new HashSet<>(committedLocations);
            removals.removeAll(replacements.keySet());

            ledger.replaceAll(replacements, removals);
            committedLocations = Set.copyOf(replacements.keySet());
            valid = true;
            dirty = false;
            lastFailure = null;
            return true;
        } catch (RuntimeException failure) {
            valid = false;
            dirty = true;
            lastFailure = failure;
            return false;
        }
    }

    public boolean isValid() {
        return valid;
    }

    public boolean isDirty() {
        return dirty;
    }

    public int mountedLocationCount() {
        return mountedLocations.size();
    }

    /** Returns an immutable snapshot of the identifiers currently mounted for future reconciliation. */
    public Set<StorageLocationId> mountedLocations() {
        Set<StorageLocationId> result = new HashSet<>(mountedLocations.size());
        for (MountedLocation location : mountedLocations) {
            result.add(location.id);
        }
        return Set.copyOf(result);
    }

    public KeyRegistry keyRegistry() {
        return keyRegistry;
    }

    /**
     * Returns the exact ledger only after a successful reconciliation.
     *
     * @throws IllegalStateException if lifecycle changes or a failed capture make the exact view stale
     */
    public StorageLedger ledger() {
        if (!valid || dirty) {
            throw new IllegalStateException("Exact storage ledger is not reconciled");
        }
        return ledger;
    }

    /**
     * Returns one exact, read-only storage snapshot without reconciling or consulting legacy storage.
     *
     * <p>
     * Server thread only. A dirty or invalid exact view is unavailable; callers must wait for a later explicit end-tick
     * reconciliation rather than retrying or consuming stale data.
     */
    public StorageSnapshotCaptureResult captureSnapshot() {
        if (!valid || dirty) {
            return new StorageSnapshotCaptureResult.Failure(StorageSnapshotCaptureResult.FailureReason.UNAVAILABLE);
        }
        if (keyRegistry.size() > PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS) {
            return new StorageSnapshotCaptureResult.Failure(StorageSnapshotCaptureResult.FailureReason.KEY_LIMIT);
        }
        try {
            return new StorageSnapshotCaptureResult.Success(ledger.captureSnapshot());
        } catch (RuntimeException failure) {
            return new StorageSnapshotCaptureResult.Failure(
                    StorageSnapshotCaptureResult.FailureReason.LEGACY_EXCEPTION);
        }
    }

    /** Returns the latest reconciliation failure, or {@code null} after the next successful reconciliation. */
    @Nullable
    public Throwable lastFailure() {
        return lastFailure;
    }

    private List<CapturedLocation> captureMountedLocations() {
        List<CapturedLocation> capturedLocations = new ArrayList<>(mountedLocations.size());
        for (MountedLocation location : mountedLocations) {
            KeyCounter counter = new KeyCounter();
            location.storage.getAvailableStacks(counter);

            List<CapturedAmount> amounts = new ArrayList<>(counter.size());
            for (Object2LongMap.Entry<AEKey> entry : counter) {
                AEKey key = Objects.requireNonNull(entry.getKey(), "Legacy storage reported a null key");
                amounts.add(new CapturedAmount(key, AEAmount.of(entry.getLongValue())));
            }
            capturedLocations.add(new CapturedLocation(location.id, amounts));
        }
        return capturedLocations;
    }

    private Map<StorageLocationId, StorageLocationSnapshot> materializeSnapshots(
            List<CapturedLocation> capturedLocations) {
        for (CapturedLocation location : capturedLocations) {
            for (CapturedAmount amount : location.amounts) {
                keyRegistry.intern(amount.key);
            }
        }

        Map<StorageLocationId, StorageLocationSnapshot> snapshots = new HashMap<>(capturedLocations.size());
        for (CapturedLocation location : capturedLocations) {
            AmountVector amounts = new AmountVector(keyRegistry.size());
            for (CapturedAmount amount : location.amounts) {
                KeyId key = keyRegistry.intern(amount.key);
                amounts.add(key.value(), amount.amount);
            }
            snapshots.put(location.id, new StorageLocationSnapshot(location.id, amounts));
        }
        return snapshots;
    }

    private void markLifecycleInconsistent(RuntimeException failure) {
        lifecycleInconsistent = true;
        dirty = true;
        valid = false;
        lastFailure = failure;
    }

    private record MountedLocation(StorageLocationId id, int priority, MEStorage storage) {
    }

    private record CapturedLocation(StorageLocationId id, List<CapturedAmount> amounts) {
    }

    private record CapturedAmount(AEKey key, AEAmount amount) {
    }
}

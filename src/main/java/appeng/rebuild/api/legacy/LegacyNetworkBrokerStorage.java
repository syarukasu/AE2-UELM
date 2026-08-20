package appeng.rebuild.api.legacy;

import java.util.Objects;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.rebuild.execution.ServerThreadGate;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.storage.BrokerExactStorage;
import appeng.rebuild.storage.ExactStorageVisitor;
import appeng.rebuild.storage.StorageServiceRebuild;
import appeng.rebuild.storage.StorageSnapshot;
import appeng.rebuild.storage.StorageSnapshotCaptureResult;

/**
 * Strict bounded bridge from AE2's native long storage API to the revisioned exact storage view.
 *
 * <p>
 * The reconstructed ledger is authoritative for every observation. A physical request is admitted only when its exact
 * amount fits in a positive legacy {@code long}; it is reconciled before and after delegation, and the returned long
 * must equal the exact ledger delta. Any legacy exception, changed simulation, reconciliation failure, or divergent
 * delta permanently closes this bridge rather than manufacturing a transfer result.
 */
public final class LegacyNetworkBrokerStorage implements BrokerExactStorage {
    private final MEStorage legacy;
    private final StorageServiceRebuild exact;
    private final KeyRegistry keys;
    private final ServerThreadGate serverThread;
    private boolean entered;
    private boolean failed;

    public LegacyNetworkBrokerStorage(MEStorage legacy, StorageServiceRebuild exact, ServerThreadGate serverThread) {
        this.legacy = Objects.requireNonNull(legacy, "legacy");
        this.exact = Objects.requireNonNull(exact, "exact");
        this.keys = exact.keyRegistry();
        this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
    }

    @Override
    public AEAmount insert(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
        return transfer(key, amount, mode, source, true);
    }

    @Override
    public AEAmount extract(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
        return transfer(key, amount, mode, source, false);
    }

    @Override
    public AEAmount amount(KeyId key) {
        enter();
        try {
            return snapshot().amount(requireKey(key));
        } finally {
            exit();
        }
    }

    @Override
    public void enumerate(ExactStorageVisitor visitor) {
        enter();
        try {
            Objects.requireNonNull(visitor, "visitor");
            StorageSnapshot snapshot = snapshot();
            for (KeyId key : snapshot.nonZeroKeys()) {
                visitor.accept(key, snapshot.amount(key));
            }
        } finally {
            exit();
        }
    }

    @Override
    public StorageSnapshot captureSnapshot() {
        enter();
        try {
            return snapshot();
        } finally {
            exit();
        }
    }

    private AEAmount transfer(KeyId requestedKey, AEAmount requestedAmount, Actionable mode, IActionSource source,
            boolean inserting) {
        enter();
        try {
            KeyId key = requireKey(requestedKey);
            AEAmount amount = Objects.requireNonNull(requestedAmount, "requestedAmount");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(source, "source");
            AEKey legacyKey = keys.resolve(key);
            if (mode == Actionable.SIMULATE) {
                return simulate(key, amount, source, legacyKey, inserting);
            }
            return mutate(key, amount, source, legacyKey, inserting);
        } finally {
            exit();
        }
    }

    /** Extraction availability is already authoritative in the exact ledger and needs no legacy simulation call. */
    private AEAmount simulate(KeyId key, AEAmount amount, IActionSource source, AEKey legacyKey, boolean inserting) {
        StorageSnapshot before = snapshot();
        if (!inserting) {
            return amount.min(before.amount(key));
        }
        long window = boundedWindow(amount);
        long reported = legacy.insert(legacyKey, window, Actionable.SIMULATE, source);
        if (reported < 0 || reported > window) {
            failClosed("Legacy storage returned an out-of-window simulation", null);
        }
        StorageSnapshot after = snapshot();
        if (!before.revision().equals(after.revision())) {
            failClosed("Legacy storage simulation mutated the authoritative ledger", null);
        }
        return AEAmount.of(reported);
    }

    /** Performs a finite sequence of physical signed-long windows and returns only reconciled observed movement. */
    private AEAmount mutate(KeyId key, AEAmount amount, IActionSource source, AEKey legacyKey, boolean inserting) {
        AEAmount moved = AEAmount.ZERO;
        int maxAttempts = Math.addExact(exact.mountedLocationCount(), 1);
        for (int attempt = 0; attempt < maxAttempts && moved.compareTo(amount) < 0; attempt++) {
            StorageSnapshot before = snapshot();
            AEAmount remaining = amount.subtractExact(moved);
            AEAmount permitted = inserting ? remaining : remaining.min(before.amount(key));
            if (permitted.equals(AEAmount.ZERO)) {
                break;
            }
            long window = boundedWindow(permitted);
            long reported;
            try {
                reported = inserting
                        ? legacy.insert(legacyKey, window, Actionable.MODULATE, source)
                        : legacy.extract(legacyKey, window, Actionable.MODULATE, source);
            } catch (RuntimeException failure) {
                AEAmount observed = observedAfterException(key, before, window, inserting, failure);
                return moved.add(observed);
            }
            if (reported < 0 || reported > window) {
                failClosed("Legacy storage returned an out-of-window transfer", null);
            }
            AEAmount observed = observedDelta(key, before, snapshot(), window, inserting);
            if (!observed.equals(AEAmount.of(reported))) {
                failClosed("Legacy transfer result differs from reconciled exact ledger delta", null);
            }
            moved = moved.add(observed);
            if (reported < window) {
                break;
            }
        }
        return moved;
    }

    private AEAmount observedAfterException(KeyId key, StorageSnapshot before, long window, boolean inserting,
            RuntimeException original) {
        StorageSnapshot after;
        try {
            after = snapshot();
        } catch (RuntimeException reconcileFailure) {
            failed = true;
            throw new IllegalStateException("Legacy storage threw and its physical result could not be reconciled",
                    original);
        }
        AEAmount observed = observedDelta(key, before, after, window, inserting);
        if (observed.equals(AEAmount.ZERO)) {
            failed = true;
            throw new IllegalStateException("Legacy storage threw without a reconcilable physical transfer", original);
        }
        // The exact observed amount is returned so the broker records real escrow rather than inventing a zero.
        return observed;
    }

    private AEAmount observedDelta(KeyId key, StorageSnapshot before, StorageSnapshot after, long window,
            boolean inserting) {
        AEAmount observed;
        try {
            observed = inserting
                    ? after.amount(key).subtractExact(before.amount(key))
                    : before.amount(key).subtractExact(after.amount(key));
        } catch (ArithmeticException invalidDirection) {
            failClosed("Legacy mutation changed the authoritative key in the wrong direction", invalidDirection);
            throw new AssertionError("unreachable");
        }
        if (observed.compareTo(AEAmount.of(window)) > 0) {
            failClosed("Legacy mutation exceeded its physical window", null);
        }
        return observed;
    }

    private static long boundedWindow(AEAmount amount) {
        return amount.min(AEAmount.of(Long.MAX_VALUE)).longValueExact();
    }

    private StorageSnapshot snapshot() {
        if (!exact.reconcileEndTick()) {
            failClosed("Native storage reconciliation is unavailable", exact.lastFailure());
        }
        StorageSnapshotCaptureResult result = exact.captureSnapshot();
        if (result instanceof StorageSnapshotCaptureResult.Success success) {
            return success.snapshot();
        }
        failClosed("Native storage snapshot is unavailable", null);
        throw new AssertionError("unreachable");
    }

    private KeyId requireKey(KeyId key) {
        key = Objects.requireNonNull(key, "key");
        keys.resolve(key);
        return key;
    }

    private void failClosed(String message, Throwable cause) {
        failed = true;
        throw cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private void enter() {
        if (failed) {
            throw new IllegalStateException("Legacy network broker storage is fail-closed");
        }
        if (!serverThread.isServerThread()) {
            throw new IllegalStateException("Legacy network broker storage requires the owning server thread");
        }
        if (entered) {
            throw new IllegalStateException("Legacy network broker storage is not reentrant");
        }
        entered = true;
    }

    private void exit() {
        entered = false;
    }
}

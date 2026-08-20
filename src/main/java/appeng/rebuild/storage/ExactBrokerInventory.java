package appeng.rebuild.storage;

import java.util.Arrays;
import java.util.Objects;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;

/**
 * Exception-atomic, revisioned in-memory implementation of {@link BrokerExactStorage}.
 *
 * <p>
 * This reference endpoint has no capacity, world, or legacy-storage access. Its first caller becomes its sole server
 * thread; a second thread or any callback reentry is rejected before an operation can observe or modify state.
 */
public final class ExactBrokerInventory implements BrokerExactStorage {
    private final KeyRegistry registry;
    private final AmountVector amounts = new AmountVector(0);

    private StorageRevision revision = StorageRevision.ZERO;
    private long[] keyRevisions = new long[0];
    private Thread serverThread;
    private boolean entered;

    public ExactBrokerInventory(KeyRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public AEAmount insert(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
        enter();
        try {
            int index = validateKey(key);
            amount = validateAmount(amount);
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(source, "source");
            AEAmount resultingAmount = validateAmount(currentAmount(index).add(amount));
            if (mode == Actionable.MODULATE && !amount.equals(AEAmount.ZERO)) {
                RevisionAdvance advance = stagedAdvance(index);
                amounts.ensureCapacity(index + 1);
                amounts.set(index, resultingAmount);
                publishAdvance(advance);
            }
            return amount;
        } finally {
            exit();
        }
    }

    @Override
    public AEAmount extract(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
        enter();
        try {
            int index = validateKey(key);
            amount = validateAmount(amount);
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(source, "source");
            AEAmount extracted = currentAmount(index).min(amount);
            if (mode == Actionable.MODULATE && !extracted.equals(AEAmount.ZERO)) {
                RevisionAdvance advance = stagedAdvance(index);
                amounts.subtractExact(index, extracted);
                publishAdvance(advance);
            }
            return extracted;
        } finally {
            exit();
        }
    }

    @Override
    public AEAmount amount(KeyId key) {
        enter();
        try {
            return currentAmount(validateKey(key));
        } finally {
            exit();
        }
    }

    @Override
    public void enumerate(ExactStorageVisitor visitor) {
        enter();
        try {
            Objects.requireNonNull(visitor, "visitor");
            int limit = Math.min(registry.size(), amounts.size());
            for (int index = 0; index < limit; index++) {
                AEAmount amount = amounts.get(index);
                if (!amount.equals(AEAmount.ZERO)) {
                    visitor.accept(new KeyId(index), amount);
                }
            }
        } finally {
            exit();
        }
    }

    @Override
    public StorageSnapshot captureSnapshot() {
        enter();
        try {
            int keyCount = boundedRegistrySize();
            AmountVector copy = new AmountVector(keyCount);
            for (int index = 0; index < keyCount; index++) {
                AEAmount amount = currentAmount(index);
                if (!amount.equals(AEAmount.ZERO)) {
                    copy.set(index, amount);
                }
            }
            return new StorageSnapshot(registry.generation(), revision, keyCount, copy,
                    Arrays.copyOf(keyRevisions, keyCount));
        } finally {
            exit();
        }
    }

    private int validateKey(KeyId key) {
        Objects.requireNonNull(key, "key");
        int index = key.value();
        int keyCount = boundedRegistrySize();
        if (index >= keyCount) {
            throw new IndexOutOfBoundsException("Key id " + index + " outside registry size " + keyCount);
        }
        return index;
    }

    private int boundedRegistrySize() {
        int size = registry.size();
        if (size > PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS) {
            throw new IllegalStateException("Exact broker storage exceeds the bounded key limit");
        }
        return size;
    }

    private static AEAmount validateAmount(AEAmount amount) {
        amount = Objects.requireNonNull(amount, "amount");
        if (amount.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
            throw new IllegalArgumentException("Exact broker amount exceeds the bounded quantity limit");
        }
        return amount;
    }

    private AEAmount currentAmount(int index) {
        return index < amounts.size() ? amounts.get(index) : AEAmount.ZERO;
    }

    private RevisionAdvance stagedAdvance(int index) {
        StorageRevision nextRevision = revision.next();
        long currentKeyRevision = index < keyRevisions.length ? keyRevisions[index] : 0L;
        long nextKeyRevision = Math.incrementExact(currentKeyRevision);
        return new RevisionAdvance(nextRevision, index, nextKeyRevision);
    }

    private void publishAdvance(RevisionAdvance advance) {
        if (keyRevisions.length <= advance.index) {
            keyRevisions = Arrays.copyOf(keyRevisions, advance.index + 1);
        }
        keyRevisions[advance.index] = advance.keyRevision;
        revision = advance.revision;
    }

    private synchronized void enter() {
        Thread current = Thread.currentThread();
        if (serverThread == null) {
            serverThread = current;
        } else if (serverThread != current) {
            throw new IllegalStateException("Exact broker storage accessed from a non-server thread");
        }
        if (entered) {
            throw new IllegalStateException("Exact broker storage is not reentrant");
        }
        entered = true;
    }

    private synchronized void exit() {
        entered = false;
    }

    private record RevisionAdvance(StorageRevision revision, int index, long keyRevision) {
    }
}

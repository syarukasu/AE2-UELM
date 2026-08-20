package appeng.rebuild.storage;

import java.util.Objects;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;

/**
 * Server-thread-owned, in-memory reference implementation of {@link ExactStorage}.
 *
 * <p>
 * This inventory accepts every insert request. It has no capacity, physical-storage routing, persistence, or world
 * access. Quantities remain authoritative in {@link AmountVector} and are never narrowed to a primitive transfer
 * window.
 */
public final class ExactKeyInventory implements ExactStorage {
    private final KeyRegistry registry;
    private final AmountVector amounts = new AmountVector(0);

    public ExactKeyInventory(KeyRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public AEAmount insert(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
        int index = validateKey(key);
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(source, "source");
        if (mode == Actionable.MODULATE && !amount.equals(AEAmount.ZERO)) {
            amounts.ensureCapacity(index + 1);
            amounts.add(index, amount);
        }
        return amount;
    }

    @Override
    public AEAmount extract(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
        int index = validateKey(key);
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(source, "source");
        AEAmount extracted = currentAmount(index).min(amount);
        if (mode == Actionable.MODULATE && !extracted.equals(AEAmount.ZERO)) {
            amounts.subtractExact(index, extracted);
        }
        return extracted;
    }

    @Override
    public AEAmount amount(KeyId key) {
        return currentAmount(validateKey(key));
    }

    @Override
    public void enumerate(ExactStorageVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        for (int index = 0, limit = Math.min(registry.size(), amounts.size()); index < limit; index++) {
            AEAmount amount = amounts.get(index);
            if (!amount.equals(AEAmount.ZERO)) {
                visitor.accept(new KeyId(index), amount);
            }
        }
    }

    private int validateKey(KeyId key) {
        Objects.requireNonNull(key, "key");
        int index = key.value();
        if (index >= registry.size()) {
            throw new IndexOutOfBoundsException("Key id " + index + " outside registry size " + registry.size());
        }
        return index;
    }

    private AEAmount currentAmount(int index) {
        return index < amounts.size() ? amounts.get(index) : AEAmount.ZERO;
    }
}

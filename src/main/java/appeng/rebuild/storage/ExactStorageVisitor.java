package appeng.rebuild.storage;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Receives each non-zero exact amount held by an {@link ExactStorage}. */
@FunctionalInterface
public interface ExactStorageVisitor {
    void accept(KeyId key, AEAmount amount);
}

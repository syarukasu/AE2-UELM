package appeng.rebuild.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** An exact-quantity storage endpoint. */
public interface ExactStorage {
    AEAmount insert(KeyId key, AEAmount amount, Actionable mode, IActionSource source);

    AEAmount extract(KeyId key, AEAmount amount, Actionable mode, IActionSource source);

    AEAmount amount(KeyId key);

    void enumerate(ExactStorageVisitor visitor);
}

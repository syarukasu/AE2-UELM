package appeng.rebuild.storage;

import java.util.function.BiConsumer;

import appeng.api.stacks.AEKey;
import appeng.rebuild.cell.ExactCellId;
import appeng.rebuild.quantity.AEAmount;

/** Exact, persistent identity and contents optionally exposed by a mounted legacy storage endpoint. */
public interface ExactMountedStorage {
    ExactCellId exactCellId();

    long exactCellRevision();

    void enumerateExact(BiConsumer<AEKey, AEAmount> visitor);

    boolean claimExactMount(Object owner);

    void releaseExactMount(Object owner);
}

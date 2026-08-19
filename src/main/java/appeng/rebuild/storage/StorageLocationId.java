package appeng.rebuild.storage;

/** A stable, non-negative identifier for one physical storage location. */
public record StorageLocationId(long value) {
    public StorageLocationId {
        if (value < 0) {
            throw new IllegalArgumentException("Storage location id must be non-negative: " + value);
        }
    }
}

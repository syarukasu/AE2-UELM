package appeng.rebuild.storage;

/** A monotonically increasing, non-negative storage revision. */
public record StorageRevision(long value) {
    public static final StorageRevision ZERO = new StorageRevision(0L);

    public StorageRevision {
        if (value < 0) {
            throw new IllegalArgumentException("Storage revision must be non-negative: " + value);
        }
    }

    /**
     * Returns the next revision, rejecting overflow rather than wrapping to an invalid revision.
     */
    public StorageRevision next() {
        return new StorageRevision(Math.incrementExact(value));
    }
}

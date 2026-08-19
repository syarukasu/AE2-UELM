package appeng.rebuild.key;

/**
 * A dense, registry-scoped identifier for an {@link appeng.api.stacks.AEKey}.
 *
 * <p>
 * The id deliberately contains no registry generation. Its owning registry is responsible for scoping it at API
 * boundaries.
 */
public record KeyId(int value) {
    public KeyId {
        if (value < 0) {
            throw new IllegalArgumentException("Key id must be non-negative: " + value);
        }
    }
}

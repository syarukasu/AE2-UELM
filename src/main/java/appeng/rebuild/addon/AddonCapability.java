package appeng.rebuild.addon;

import java.util.Objects;

/**
 * Typed result of evaluating one addon surface.
 *
 * <p>
 * Supported standard-contract adapters retain exact quantities in the rebuild and only project a bounded physical
 * command at the addon boundary. Unsupported surfaces cannot silently route through legacy behavior.
 */
public sealed interface AddonCapability permits AddonCapability.Supported, AddonCapability.Unsupported {
    AddonId addon();

    AddonSurface surface();

    AddonRuntimeMetadata metadata();

    default ExactRebuildSeam seam() {
        return surface().seam();
    }

    default boolean enabled() {
        return this instanceof Supported;
    }

    /** A verified adapter implemented entirely through AE2's stable standard crafting contracts. */
    record Supported(AddonId addon, AddonSurface surface, AddonRuntimeMetadata metadata, AdapterMode mode,
            String verifiedAgainst) implements AddonCapability {
        public Supported {
            Objects.requireNonNull(addon, "addon");
            Objects.requireNonNull(surface, "surface");
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(verifiedAgainst, "verifiedAgainst");
            if (verifiedAgainst.isBlank()) {
                throw new IllegalArgumentException("Verified addon version must not be blank");
            }
        }
    }

    /** A fail-closed capability result that cannot be mistaken for a successful legacy fallback. */
    record Unsupported(AddonId addon, AddonSurface surface, AddonRuntimeMetadata metadata, Reason reason)
            implements
                AddonCapability {
        public Unsupported {
            Objects.requireNonNull(addon, "addon");
            Objects.requireNonNull(surface, "surface");
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum Reason {
        COMPILED_API_UNAVAILABLE
    }

    enum AdapterMode {
        STANDARD_PATTERN_SUBTYPE,
        SERIALIZED_EXACT_MACHINE
    }
}

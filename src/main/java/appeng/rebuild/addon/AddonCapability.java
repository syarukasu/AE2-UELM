package appeng.rebuild.addon;

import java.util.Objects;

/**
 * Typed result of evaluating one addon surface.
 *
 * <p>
 * An enabled capability will only be introduced with a compiled, version-pinned addon API adapter. Until then, every
 * surface is explicitly unavailable and cannot silently route through legacy behavior.
 */
public sealed interface AddonCapability permits AddonCapability.Unsupported {
    AddonId addon();

    AddonSurface surface();

    AddonRuntimeMetadata metadata();

    default ExactRebuildSeam seam() {
        return surface().seam();
    }

    default boolean enabled() {
        return false;
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
}

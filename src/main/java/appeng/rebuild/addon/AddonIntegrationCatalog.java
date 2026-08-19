package appeng.rebuild.addon;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fail-closed catalog for optional addon integration.
 *
 * <p>
 * The production dependency graph has no ExtendedAE or AdvancedAE API. Consequently, this catalog deliberately does not
 * inspect addon classes, reflect into their internals, or claim that a legacy path succeeded. A future adapter must be
 * compiled against a verified API and replace the corresponding typed unsupported result.
 */
public final class AddonIntegrationCatalog {
    private static final Map<AddonId, List<AddonCapability>> CAPABILITIES = buildCapabilities();

    private AddonIntegrationCatalog() {
    }

    /**
     * Returns all surface results for {@code addon}. Runtime metadata is {@code UNKNOWN/UNKNOWN} until a supported,
     * version-pinned adapter supplies verified metadata.
     */
    public static List<AddonCapability> capabilities(AddonId addon) {
        return CAPABILITIES.get(Objects.requireNonNull(addon, "addon"));
    }

    /** Returns the exact, fail-closed result for one addon surface. */
    public static AddonCapability capability(AddonId addon, AddonSurface surface) {
        Objects.requireNonNull(surface, "surface");
        return capabilities(addon).get(surface.ordinal());
    }

    private static Map<AddonId, List<AddonCapability>> buildCapabilities() {
        EnumMap<AddonId, List<AddonCapability>> result = new EnumMap<>(AddonId.class);
        for (AddonId addon : AddonId.values()) {
            List<AddonCapability> capabilities = new ArrayList<>(AddonSurface.values().length);
            for (AddonSurface surface : AddonSurface.values()) {
                capabilities.add(new AddonCapability.Unsupported(addon, surface, AddonRuntimeMetadata.UNKNOWN,
                        AddonCapability.Reason.COMPILED_API_UNAVAILABLE));
            }
            result.put(addon, List.copyOf(capabilities));
        }
        return Map.copyOf(result);
    }
}

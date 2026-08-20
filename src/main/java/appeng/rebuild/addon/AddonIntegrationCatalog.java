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
 * Pattern and machine support uses AE2's standard contracts, verified against real addon artifacts, and therefore does
 * not load, reflect into, or bundle addon classes. The exact core remains authoritative while the physical machine is
 * driven by a single bounded command whose returned resources are checked before custody changes.
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
                capabilities.add(createCapability(addon, surface));
            }
            result.put(addon, List.copyOf(capabilities));
        }
        return Map.copyOf(result);
    }

    private static AddonCapability createCapability(AddonId addon, AddonSurface surface) {
        String verifiedVersion = switch (addon) {
            case EXTENDED_AE -> "1.4.15";
            case ADVANCED_AE -> "1.3.5";
        };
        if (surface == AddonSurface.PATTERN_PROVIDER) {
            return new AddonCapability.Supported(addon, surface, AddonRuntimeMetadata.UNKNOWN,
                    AddonCapability.AdapterMode.STANDARD_PATTERN_SUBTYPE, verifiedVersion);
        }
        if (surface == AddonSurface.CRAFTING_MACHINE) {
            return new AddonCapability.Supported(addon, surface, AddonRuntimeMetadata.UNKNOWN,
                    AddonCapability.AdapterMode.SERIALIZED_EXACT_MACHINE, verifiedVersion);
        }
        return new AddonCapability.Unsupported(addon, surface, AddonRuntimeMetadata.UNKNOWN,
                AddonCapability.Reason.COMPILED_API_UNAVAILABLE);
    }
}

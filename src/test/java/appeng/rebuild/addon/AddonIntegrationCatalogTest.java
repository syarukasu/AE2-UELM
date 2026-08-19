package appeng.rebuild.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Contract tests for the fail-closed optional-addon capability surface. */
class AddonIntegrationCatalogTest {

    @Test
    void everyKnownAddonExposesEachImmutableUnavailableSurface() {
        for (AddonId addon : AddonId.values()) {
            List<AddonCapability> capabilities = AddonIntegrationCatalog.capabilities(addon);

            assertEquals(List.of(AddonSurface.values()),
                    capabilities.stream().map(AddonCapability::surface).toList());
            for (AddonSurface surface : AddonSurface.values()) {
                AddonCapability.Unsupported capability = assertInstanceOf(AddonCapability.Unsupported.class,
                        AddonIntegrationCatalog.capability(addon, surface));
                assertEquals(addon, capability.addon());
                assertEquals(surface, capability.surface());
                assertEquals(AddonRuntimeMetadata.UNKNOWN, capability.metadata());
                assertEquals(AddonCapability.Reason.COMPILED_API_UNAVAILABLE, capability.reason());
                assertEquals(surface.seam(), capability.seam());
                assertFalse(capability.enabled());
            }

            assertThrows(UnsupportedOperationException.class,
                    () -> capabilities.add(capabilities.get(0)));
        }
    }

    @Test
    void catalogRejectsNullArgumentsAndKeepsStableAddonIds() {
        assertThrows(NullPointerException.class, () -> AddonIntegrationCatalog.capabilities(null));
        assertThrows(NullPointerException.class,
                () -> AddonIntegrationCatalog.capability(AddonId.EXTENDED_AE, null));
        assertEquals("extendedae", AddonId.EXTENDED_AE.modId());
        assertEquals("advanced_ae", AddonId.ADVANCED_AE.modId());
    }

    @Test
    void runtimeMetadataIsExplicitAndNullSafe() {
        assertEquals("UNKNOWN", AddonRuntimeMetadata.UNKNOWN.modVersion());
        assertEquals("UNKNOWN", AddonRuntimeMetadata.UNKNOWN.apiVersion());
        assertThrows(NullPointerException.class, () -> new AddonRuntimeMetadata(null, "api"));
        assertThrows(NullPointerException.class, () -> new AddonRuntimeMetadata("mod", null));
    }
}

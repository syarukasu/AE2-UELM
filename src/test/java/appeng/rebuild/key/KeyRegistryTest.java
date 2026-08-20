package appeng.rebuild.key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEKey;

/** Contract tests for the grid-local AEKey to KeyId registry. */
class KeyRegistryTest {

    @Test
    void keyIdRejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> new KeyId(-1));
        assertEquals(0, new KeyId(0).value());
        assertEquals(Integer.MAX_VALUE, new KeyId(Integer.MAX_VALUE).value());
    }

    @Test
    void registryRejectsNegativeGenerations() {
        assertThrows(IllegalArgumentException.class, () -> new KeyRegistry(-1));
        assertEquals(42L, new KeyRegistry(42L).generation());
    }

    @Test
    void internIsIdempotentAndKeepsTheFirstSequentialId() {
        KeyRegistry registry = new KeyRegistry(1L);
        AEKey stone = key();

        KeyId first = registry.intern(stone);
        KeyId second = registry.intern(stone);

        assertEquals(first, second);
        assertEquals(0, first.value());
        assertEquals(1, registry.size());
    }

    @Test
    void distinctKeysReceiveDistinctSequentialIds() {
        KeyRegistry registry = new KeyRegistry(2L);

        KeyId stone = registry.intern(key());
        KeyId dirt = registry.intern(key());
        KeyId diamond = registry.intern(key());

        assertEquals(0, stone.value());
        assertEquals(1, dirt.value());
        assertEquals(2, diamond.value());
        assertNotEquals(stone, dirt);
        assertNotEquals(dirt, diamond);
        assertEquals(3, registry.size());
    }

    @Test
    void lookupDoesNotInternAnUnknownKey() {
        KeyRegistry registry = new KeyRegistry(3L);
        AEKey stone = key();

        assertNull(registry.lookup(stone));
        assertEquals(0, registry.size());

        KeyId id = registry.intern(stone);
        assertEquals(id, registry.lookup(stone));
        assertEquals(1, registry.size());
    }

    @Test
    void resolveRoundTripsInternedKeys() {
        KeyRegistry registry = new KeyRegistry(4L);
        AEKey stone = key();
        AEKey dirt = key();

        KeyId stoneId = registry.intern(stone);
        KeyId dirtId = registry.intern(dirt);

        assertEquals(stone, registry.resolve(stoneId));
        assertEquals(dirt, registry.resolve(dirtId));
        assertEquals(stone, registry.resolve(registry.lookup(stone)));
    }

    @Test
    void resolveRejectsIdsOutsideThisRegistryRange() {
        KeyRegistry registry = new KeyRegistry(5L);
        registry.intern(key());

        assertThrows(IndexOutOfBoundsException.class, () -> registry.resolve(new KeyId(1)));
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> registry.resolve(new KeyId(Integer.MAX_VALUE)));
    }

    @Test
    void sizeAndGenerationAreStableObservations() {
        KeyRegistry registry = new KeyRegistry(Long.MAX_VALUE);

        assertEquals(0, registry.size());
        assertEquals(Long.MAX_VALUE, registry.generation());
        registry.intern(key());
        assertEquals(1, registry.size());
        assertEquals(Long.MAX_VALUE, registry.generation());
    }

    private static AEKey key() {
        return mock(AEKey.class);
    }
}

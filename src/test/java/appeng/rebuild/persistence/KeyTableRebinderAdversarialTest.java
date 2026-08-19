package appeng.rebuild.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;

import appeng.api.stacks.AEKey;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;

/** Adversarial tests for sparse-key preflight, commit atomicity, and remap identity. */
class KeyTableRebinderAdversarialTest {

    @Test
    void prospectiveIsReadOnlyAndCommitPublishesExactlyItsIds() {
        AEKey first = key("rebind:first");
        AEKey second = key("rebind:second");
        PersistedKeyTable table = new PersistedKeyTable(17L, List.of(
                new PersistedKeyTable.Entry(new KeyId(4), first),
                new PersistedKeyTable.Entry(new KeyId(9), second)));

        KeyRegistry current = new KeyRegistry(23L);
        AEKey existing = key("rebind:existing");
        KeyId existingId = current.intern(existing);
        ExactKeyRemap prospective = KeyTableRebinder.prospective(table, current);

        assertEquals(new KeyId(1), prospective.require(new KeyId(4)));
        assertEquals(new KeyId(2), prospective.require(new KeyId(9)));
        assertEquals(1, current.size(), "prospective computation must not intern identities");
        assertSame(existing, current.resolve(existingId));

        KeyTableRebinder.commit(table, current, prospective);

        assertEquals(3, current.size());
        assertSame(existing, current.resolve(existingId));
        assertEquals(new KeyId(1), current.lookup(first));
        assertEquals(new KeyId(2), current.lookup(second));
    }

    @Test
    void laterIdentityFailureLeavesTheCurrentRegistryExactlyUnchanged() {
        AEKey first = key("rebind:good");
        AEKey later = mock(AEKey.class);
        when(later.toTagGeneric()).thenThrow(new IllegalStateException("synthetic identity failure"));
        PersistedKeyTable table = new PersistedKeyTable(31L, List.of(
                new PersistedKeyTable.Entry(new KeyId(0), first),
                new PersistedKeyTable.Entry(new KeyId(1), later)));
        KeyRegistry current = new KeyRegistry(37L);
        AEKey existing = key("rebind:existing");
        KeyId existingId = current.intern(existing);

        assertThrows(IllegalStateException.class, () -> KeyTableRebinder.rebind(table, current));

        assertEquals(1, current.size());
        assertSame(existing, current.resolve(existingId));
        assertNull(current.lookup(first));
    }

    @Test
    void ConstructibleCollisionRemapIsRejectedWithoutPublishingEitherIdentity() {
        AEKey first = key("rebind:collision:first");
        AEKey second = key("rebind:collision:second");
        PersistedKeyTable table = new PersistedKeyTable(41L, List.of(
                new PersistedKeyTable.Entry(new KeyId(0), first),
                new PersistedKeyTable.Entry(new KeyId(1), second)));
        KeyRegistry current = new KeyRegistry(43L);
        AEKey existing = key("rebind:collision:existing");
        KeyId existingId = current.intern(existing);

        Map<KeyId, KeyId> colliding = new LinkedHashMap<>();
        colliding.put(new KeyId(0), new KeyId(1));
        colliding.put(new KeyId(1), new KeyId(1));
        ExactKeyRemap forged = new ExactKeyRemap(table.generation(), current.generation(), colliding, 2);

        assertThrows(IllegalArgumentException.class, () -> KeyTableRebinder.commit(table, current, forged));

        assertEquals(1, current.size());
        assertSame(existing, current.resolve(existingId));
        assertNull(current.lookup(first));
        assertNull(current.lookup(second));
    }

    private static AEKey key(String channel) {
        AEKey key = mock(AEKey.class);
        CompoundTag tag = new CompoundTag();
        tag.putString("#c", channel);
        when(key.toTagGeneric()).thenReturn(tag);
        return key;
    }
}

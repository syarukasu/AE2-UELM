package appeng.rebuild.key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;

/** Adversarial tests for the all-or-nothing key-registry recovery batch. */
class KeyRegistryAtomicRecoveryTest {

    @Test
    void runtimeFailureWhileBuildingProspectiveCopiesLeavesRegistryUntouched() {
        KeyRegistry registry = new KeyRegistry(41L);
        AEKey existing = mock(AEKey.class);
        KeyId existingId = registry.intern(existing);
        ExplodingHashKey later = new ExplodingHashKey();

        assertThrows(IllegalStateException.class,
                () -> registry.internAllAtomically(List.of(existing, later),
                        List.of(existingId, new KeyId(1))));

        assertEquals(1, registry.size(), "a local-copy failure must not publish a partial batch");
        assertSame(existing, registry.resolve(existingId));
        assertEquals(41L, registry.generation());
    }

    @Test
    void expectedIdMismatchAfterAProspectiveInsertionLeavesRegistryUntouched() {
        KeyRegistry registry = new KeyRegistry(43L);
        AEKey existing = mock(AEKey.class);
        AEKey newKey = mock(AEKey.class);
        KeyId existingId = registry.intern(existing);

        assertThrows(IllegalArgumentException.class,
                () -> registry.internAllAtomically(List.of(existing, newKey),
                        List.of(existingId, new KeyId(99))));

        assertEquals(1, registry.size());
        assertSame(existing, registry.resolve(existingId));
    }

    /** A key whose identity operation fails only inside the prospective local batch. */
    private static final class ExplodingHashKey extends AEKey {
        @Override
        public int hashCode() {
            throw new IllegalStateException("synthetic recovery callback failure");
        }

        @Override
        public AEKeyType getType() {
            return null;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag() {
            return new CompoundTag();
        }

        @Override
        public Object getPrimaryKey() {
            return this;
        }

        @Override
        public ResourceLocation getId() {
            return new ResourceLocation("test", "exploding");
        }

        @Override
        public void writeToPacket(FriendlyByteBuf data) {
            // The synthetic key is never serialized in this test.
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal("exploding");
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
            // The synthetic key exists only to make hashCode fail during recovery preflight.
        }
    }
}

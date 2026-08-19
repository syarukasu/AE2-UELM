package appeng.rebuild.api.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import net.minecraft.network.chat.Component;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.me.storage.NetworkStorage;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.storage.StorageServiceRebuild;

/** Contract tests for the legacy MEStorage facade over exact reconstructed storage. */
class LegacyStorageFacadeTest {

    private static final IActionSource SOURCE = IActionSource.empty();

    @Test
    void insertAndExtractReturnTheSingleDelegateResult() {
        Fixture fixture = fixture();
        AEKey key = key();
        when(fixture.network.insert(key, 7L, Actionable.SIMULATE, SOURCE)).thenReturn(3L);
        when(fixture.network.extract(key, 7L, Actionable.SIMULATE, SOURCE)).thenReturn(2L);

        assertEquals(3L, fixture.facade.insert(key, 7L, Actionable.SIMULATE, SOURCE));
        assertEquals(2L, fixture.facade.extract(key, 7L, Actionable.SIMULATE, SOURCE));
        verify(fixture.network).insert(key, 7L, Actionable.SIMULATE, SOURCE);
        verify(fixture.network).extract(key, 7L, Actionable.SIMULATE, SOURCE);
    }

    @Test
    void preconditionsRejectInvalidRequestsBeforeDelegation() {
        Fixture fixture = fixture();
        AEKey key = key();

        assertThrows(
                NullPointerException.class,
                () -> fixture.facade.insert(null, 1L, Actionable.SIMULATE, SOURCE));
        assertThrows(
                NullPointerException.class,
                () -> fixture.facade.extract(key, 1L, null, SOURCE));
        assertThrows(
                NullPointerException.class,
                () -> fixture.facade.insert(key, 1L, Actionable.SIMULATE, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.facade.extract(key, -1L, Actionable.SIMULATE, SOURCE));

        verifyNoInteractions(fixture.network);
    }

    @Test
    void simulateAndZeroModulateNeverDirtyTheExactView() {
        Fixture fixture = fixture();
        AEKey key = key();
        when(fixture.network.insert(key, 4L, Actionable.SIMULATE, SOURCE)).thenReturn(0L);
        when(fixture.network.extract(key, 0L, Actionable.MODULATE, SOURCE)).thenReturn(0L);

        fixture.facade.insert(key, 4L, Actionable.SIMULATE, SOURCE);
        fixture.facade.extract(key, 0L, Actionable.MODULATE, SOURCE);

        assertFalse(fixture.exact.isDirty());
        assertTrue(fixture.exact.isValid());
    }

    @Test
    void positiveModulateInvalidatesBeforeZeroResultAndThrownDelegate() {
        Fixture fixture = fixture();
        AEKey key = key();
        doAnswer(invocation -> {
            assertTrue(fixture.exact.isDirty());
            return 0L;
        }).when(fixture.network).insert(key, 4L, Actionable.MODULATE, SOURCE);
        doAnswer(invocation -> {
            assertTrue(fixture.exact.isDirty());
            throw new IllegalStateException("delegate failure");
        }).when(fixture.network).extract(key, 4L, Actionable.MODULATE, SOURCE);

        assertEquals(0L, fixture.facade.insert(key, 4L, Actionable.MODULATE, SOURCE));
        assertTrue(fixture.exact.isDirty());
        assertThrows(
                IllegalStateException.class,
                () -> fixture.facade.extract(key, 4L, Actionable.MODULATE, SOURCE));
        assertTrue(fixture.exact.isDirty());
    }

    @Test
    void invalidDelegateResultsAreRejectedWithoutClamping() {
        Fixture fixture = fixture();
        AEKey key = key();
        when(fixture.network.insert(key, 4L, Actionable.SIMULATE, SOURCE)).thenReturn(-1L);
        when(fixture.network.extract(key, 4L, Actionable.SIMULATE, SOURCE)).thenReturn(5L);

        assertThrows(
                IllegalStateException.class,
                () -> fixture.facade.insert(key, 4L, Actionable.SIMULATE, SOURCE));
        assertThrows(
                IllegalStateException.class,
                () -> fixture.facade.extract(key, 4L, Actionable.SIMULATE, SOURCE));
        assertFalse(fixture.exact.isDirty());
    }

    @Test
    void passThroughMethodsPreserveDelegateArgumentsAndResults() {
        Fixture fixture = fixture();
        AEKey key = key();
        KeyCounter counter = new KeyCounter();
        Component description = mock(Component.class);
        when(fixture.network.getDescription()).thenReturn(description);
        when(fixture.network.isPreferredStorageFor(key, SOURCE)).thenReturn(true);

        fixture.facade.getAvailableStacks(counter);

        assertSame(description, fixture.facade.getDescription());
        assertTrue(fixture.facade.isPreferredStorageFor(key, SOURCE));
        verify(fixture.network).getAvailableStacks(counter);
        verify(fixture.network).getDescription();
        verify(fixture.network).isPreferredStorageFor(key, SOURCE);
    }

    private static Fixture fixture() {
        StorageServiceRebuild exact = new StorageServiceRebuild(new KeyRegistry(1L));
        assertTrue(exact.reconcileEndTick());
        NetworkStorage network = mock(NetworkStorage.class);
        return new Fixture(network, exact, new LegacyStorageFacade(network, exact));
    }

    private static AEKey key() {
        AEKey key = mock(AEKey.class);
        when(key.getPrimaryKey()).thenReturn(new Object());
        return key;
    }

    private record Fixture(NetworkStorage network, StorageServiceRebuild exact, MEStorage facade) {
    }
}

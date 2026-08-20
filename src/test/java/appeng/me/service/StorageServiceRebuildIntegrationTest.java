package appeng.me.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IStackWatcher;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageWatcherNode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Integration tests for the public StorageService compatibility cache and exact view. */
class StorageServiceRebuildIntegrationTest {

    private static final IActionSource SOURCE = IActionSource.empty();

    @Test
    void distinctServicesUseDistinctRegistryGenerationsAndRetainOneFacade() {
        StorageService first = new StorageService();
        StorageService second = new StorageService();

        assertNotEquals(
                first.getExactStorage().keyRegistry().generation(),
                second.getExactStorage().keyRegistry().generation());
        assertSame(first.getInventory(), first.getInventory());
        assertSame(second.getInventory(), second.getInventory());
    }

    @Test
    void initialCachedInventoryQueryDoesNotScanADelegate() {
        StorageService service = new StorageService();
        AEKey key = key();

        assertEquals(0L, service.getCachedInventory().get(key));
        assertTrue(service.getCachedInventory().isEmpty());
    }

    @Test
    void globalProviderMountBootstrapsOnceAndProjectsItsCache() {
        StorageService service = new StorageService();
        AEKey key = key();
        MutableStorage storage = storage(key, 12L);
        IStorageProvider provider = provider(storage);

        service.addGlobalStorageProvider(provider);

        assertEquals(1, storage.scanCount);
        assertEquals(12L, service.getCachedInventory().get(key));
        assertTrue(service.getExactStorage().isValid());
        assertEquals(AEAmount.of(12L), exactAmount(service, key));
    }

    @Test
    void twoLongMaxLocationsRemainExactWhileLegacyCacheSaturates() {
        StorageService service = new StorageService();
        AEKey key = key();
        MutableStorage first = storage(key, Long.MAX_VALUE);
        MutableStorage second = storage(key, Long.MAX_VALUE);

        service.addGlobalStorageProvider(provider(first, second));

        AEAmount expected = AEAmount.of(
                BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(2L)));
        assertEquals(expected, exactAmount(service, key));
        assertEquals(Long.MAX_VALUE, service.getCachedInventory().get(key));
        assertEquals(1, first.scanCount);
        assertEquals(1, second.scanCount);
    }

    @Test
    void outOfBandChangeAppearsAfterExactlyOneEndTickScanPerLocation() {
        StorageService service = new StorageService();
        AEKey key = key();
        MutableStorage storage = storage(key, 3L);
        service.addGlobalStorageProvider(provider(storage));
        assertEquals(1, storage.scanCount);

        storage.amount = 9L;
        service.onServerEndTick();

        assertEquals(2, storage.scanCount);
        assertEquals(9L, service.getCachedInventory().get(key));
        assertEquals(AEAmount.of(9L), exactAmount(service, key));
    }

    @Test
    void failedEndTickKeepsCompatibilityCacheAndSendsNoFakeWatcherUpdate() {
        StorageService service = new StorageService();
        AEKey key = key();
        MutableStorage storage = storage(key, 5L);
        IStorageWatcherNode watcherHost = mock(IStorageWatcherNode.class);
        AtomicReference<IStackWatcher> watcher = new AtomicReference<>();
        doAnswer(invocation -> {
            watcher.set(invocation.getArgument(0));
            return null;
        }).when(watcherHost).updateWatcher(any());
        service.addNode(node(provider(storage), watcherHost), null);
        watcher.get().add(key);
        clearInvocations(watcherHost);

        storage.failure = new IllegalStateException("scan failure");
        service.onServerEndTick();

        assertEquals(5L, service.getCachedInventory().get(key));
        assertFalse(service.getExactStorage().isValid());
        verify(watcherHost, never()).onStackChange(any(), anyLong());
    }

    @Test
    void removingGlobalProviderBootstrapsAnEmptyCache() {
        StorageService service = new StorageService();
        AEKey key = key();
        MutableStorage storage = storage(key, 5L);
        IStorageProvider provider = provider(storage);
        service.addGlobalStorageProvider(provider);
        assertEquals(5L, service.getCachedInventory().get(key));

        service.removeGlobalStorageProvider(provider);

        assertEquals(0L, service.getCachedInventory().get(key));
        assertTrue(service.getExactStorage().isValid());
        assertEquals(AEAmount.ZERO, exactAmount(service, key));
    }

    @Test
    void invalidateCacheMakesExactLedgerUnavailableWithoutScanningOnQueries() {
        StorageService service = new StorageService();
        AEKey key = key();
        MutableStorage storage = storage(key, 5L);
        service.addGlobalStorageProvider(provider(storage));
        assertEquals(1, storage.scanCount);

        service.invalidateCache();
        assertFalse(service.getExactStorage().isValid());
        assertThrows(IllegalStateException.class, () -> service.getExactStorage().ledger());
        assertEquals(5L, service.getCachedInventory().get(key));
        assertEquals(1, storage.scanCount);

        service.onServerEndTick();
        assertTrue(service.getExactStorage().isValid());
        assertEquals(2, storage.scanCount);
    }

    @Test
    void facadeModulateInvalidatesExactViewAndEndTickRecoversIt() {
        StorageService service = new StorageService();
        AEKey key = key();
        MutableStorage storage = storage(key, 5L);
        service.addGlobalStorageProvider(provider(storage));
        assertTrue(service.getExactStorage().isValid());

        assertEquals(
                2L,
                service.getInventory().extract(key, 2L, Actionable.MODULATE, SOURCE));
        assertFalse(service.getExactStorage().isValid());
        assertTrue(service.getExactStorage().isDirty());
        assertEquals(1, storage.scanCount);

        service.onServerEndTick();

        assertTrue(service.getExactStorage().isValid());
        assertEquals(3L, service.getCachedInventory().get(key));
        assertEquals(AEAmount.of(3L), exactAmount(service, key));
        assertEquals(2, storage.scanCount);
    }

    @Test
    void watcherReportsChangedAndRemovedKeysButNotUnchangedReconciles() {
        StorageService service = new StorageService();
        AEKey key = key();
        MutableStorage storage = storage(key, 4L);
        IStorageWatcherNode watcherHost = mock(IStorageWatcherNode.class);
        AtomicReference<IStackWatcher> watcher = new AtomicReference<>();
        doAnswer(invocation -> {
            watcher.set(invocation.getArgument(0));
            return null;
        }).when(watcherHost).updateWatcher(any());
        service.addNode(node(provider(storage), watcherHost), null);
        watcher.get().add(key);

        clearInvocations(watcherHost);
        service.onServerEndTick();
        verify(watcherHost, never()).onStackChange(any(), anyLong());

        storage.amount = 9L;
        service.onServerEndTick();
        verify(watcherHost).onStackChange(key, 9L);

        clearInvocations(watcherHost);
        storage.amount = 0L;
        service.onServerEndTick();
        verify(watcherHost).onStackChange(key, 0L);
    }

    @Test
    void watcherReceivesSaturatedLongForAnExactAmountAboveLong() {
        StorageService service = new StorageService();
        AEKey key = key();
        MutableStorage first = storage(key, 0L);
        MutableStorage second = storage(key, 0L);
        IStorageWatcherNode watcherHost = mock(IStorageWatcherNode.class);
        AtomicReference<IStackWatcher> watcher = new AtomicReference<>();
        doAnswer(invocation -> {
            watcher.set(invocation.getArgument(0));
            return null;
        }).when(watcherHost).updateWatcher(any());
        service.addNode(node(provider(first, second), watcherHost), null);
        watcher.get().add(key);
        clearInvocations(watcherHost);

        first.amount = Long.MAX_VALUE;
        second.amount = Long.MAX_VALUE;
        service.onServerEndTick();

        assertTrue(exactAmount(service, key).compareTo(AEAmount.of(Long.MAX_VALUE)) > 0);
        assertEquals(Long.MAX_VALUE, service.getCachedInventory().get(key));
        verify(watcherHost).onStackChange(key, Long.MAX_VALUE);
    }

    private static AEKey key() {
        AEKey key = mock(AEKey.class);
        when(key.getPrimaryKey()).thenReturn(new Object());
        return key;
    }

    private static AEAmount exactAmount(StorageService service, AEKey key) {
        KeyId keyId = service.getExactStorage().keyRegistry().lookup(key);
        return service.getExactStorage().ledger().amount(keyId);
    }

    private static IStorageProvider provider(MutableStorage... storages) {
        IStorageProvider provider = mock(IStorageProvider.class);
        doAnswer(invocation -> {
            IStorageMounts mounts = invocation.getArgument(0);
            for (int index = 0; index < storages.length; index++) {
                mounts.mount(storages[index], index);
            }
            return null;
        }).when(provider).mountInventories(any());
        return provider;
    }

    private static IGridNode node(IStorageProvider provider, IStorageWatcherNode watcher) {
        IGridNode node = mock(IGridNode.class);
        when(node.getService(IStorageProvider.class)).thenReturn(provider);
        when(node.getService(IStorageWatcherNode.class)).thenReturn(watcher);
        return node;
    }

    private static MutableStorage storage(AEKey key, long amount) {
        return new MutableStorage(key, amount);
    }

    private static final class MutableStorage implements MEStorage {
        private final AEKey key;
        private long amount;
        private int scanCount;
        private RuntimeException failure;

        private MutableStorage(AEKey key, long amount) {
            this.key = key;
            this.amount = amount;
        }

        @Override
        public long insert(AEKey what, long requested, Actionable mode, IActionSource source) {
            long inserted = Math.min(requested, Long.MAX_VALUE - amount);
            if (mode == Actionable.MODULATE) {
                amount += inserted;
            }
            return inserted;
        }

        @Override
        public long extract(AEKey what, long requested, Actionable mode, IActionSource source) {
            long extracted = Math.min(requested, amount);
            if (mode == Actionable.MODULATE) {
                amount -= extracted;
            }
            return extracted;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            scanCount++;
            if (failure != null) {
                throw failure;
            }
            out.add(key, amount);
        }

        @Override
        public net.minecraft.network.chat.Component getDescription() {
            return null;
        }
    }
}

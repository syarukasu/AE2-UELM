package appeng.rebuild.api.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.me.service.StorageService;
import appeng.rebuild.execution.ServerThreadGate;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.storage.BrokerExactStorage;
import appeng.rebuild.storage.StorageSnapshot;

/** Contract tests for the native AE2 network bridge used by exact transfer execution. */
class LegacyNetworkBrokerStorageTest {
    private static final IActionSource SOURCE = IActionSource.empty();
    private static final ServerThreadGate SERVER_THREAD = () -> true;

    @Test
    void snapshotRetainsAnAggregateAboveLongMaxAndUsesTheRegistryIdentity() {
        AEKey key = key();
        StorageService service = service(storage(key, Long.MAX_VALUE), storage(key, Long.MAX_VALUE));
        BrokerExactStorage broker = service.getBrokerExactStorage(SERVER_THREAD);
        KeyId id = id(service, key);

        AEAmount expected = AEAmount.of(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO));
        StorageSnapshot snapshot = broker.captureSnapshot();

        assertEquals(expected, snapshot.amount(id));
        assertEquals(expected, broker.amount(id));
        assertEquals(key, service.getExactStorage().keyRegistry().resolve(id));
        assertEquals(id, service.getExactStorage().keyRegistry().lookup(key));
    }

    @Test
    void boundedLongWindowReconcilesTheNativeExactDeltaAndPartialMove() {
        AEKey key = key();
        MutableStorage first = storage(key, Long.MAX_VALUE);
        MutableStorage second = storage(key, 5L);
        StorageService service = service(first, second);
        BrokerExactStorage broker = service.getBrokerExactStorage(SERVER_THREAD);
        KeyId id = id(service, key);
        StorageSnapshot before = broker.captureSnapshot();

        assertEquals(AEAmount.of(Long.MAX_VALUE), broker.extract(id, AEAmount.of(Long.MAX_VALUE),
                Actionable.MODULATE, SOURCE));
        assertEquals(AEAmount.of(5L), broker.amount(id));
        StorageSnapshot afterFirstWindow = broker.captureSnapshot();
        assertEquals(before.revision().next(), afterFirstWindow.revision());
        assertEquals(before.keyRevision(id) + 1L, afterFirstWindow.keyRevision(id));
        assertEquals(AEAmount.of(5L), broker.extract(id, AEAmount.of(Long.MAX_VALUE), Actionable.MODULATE, SOURCE));
        assertEquals(AEAmount.ZERO, broker.amount(id));
        assertEquals(key, first.lastRequestedKey);

        AEAmount aboveLong = AEAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        assertEquals(aboveLong, broker.insert(id, aboveLong, Actionable.MODULATE, SOURCE));
        assertEquals(aboveLong, broker.amount(id));
    }

    @Test
    void simulateIsPureForLegacyDelegateAndExactRevisions() {
        AEKey key = key();
        MutableStorage storage = storage(key, 8L);
        StorageService service = service(storage);
        BrokerExactStorage broker = service.getBrokerExactStorage(SERVER_THREAD);
        KeyId id = id(service, key);
        StorageSnapshot before = broker.captureSnapshot();

        assertEquals(AEAmount.of(3L), broker.extract(id, AEAmount.of(3L), Actionable.SIMULATE, SOURCE));
        assertEquals(AEAmount.of(4L), broker.insert(id, AEAmount.of(4L), Actionable.SIMULATE, SOURCE));

        StorageSnapshot after = broker.captureSnapshot();
        assertEquals(8L, storage.amount);
        assertEquals(before.revision(), after.revision());
        assertEquals(before.keyRevision(id), after.keyRevision(id));
        assertEquals(before.amount(id), after.amount(id));
    }

    @Test
    void divergentReportedMoveFailsClosedWithoutManufacturingSuccess() {
        AEKey key = key();
        MutableStorage storage = storage(key, 9L);
        storage.reportedExtractAdjustment = -1L;
        StorageService service = service(storage);
        BrokerExactStorage broker = service.getBrokerExactStorage(SERVER_THREAD);
        KeyId id = id(service, key);

        assertThrows(IllegalStateException.class,
                () -> broker.extract(id, AEAmount.of(4L), Actionable.MODULATE, SOURCE));
        assertEquals(5L, storage.amount, "the physical mutation is observed, never hidden as success");
        assertThrows(IllegalStateException.class, () -> broker.amount(id));
    }

    @Test
    void nativeExceptionAfterPhysicalMutationClosesTheBridge() {
        AEKey key = key();
        MutableStorage storage = storage(key, 9L);
        storage.throwAfterExtractMutation = new IllegalStateException("native failure");
        StorageService service = service(storage);
        BrokerExactStorage broker = service.getBrokerExactStorage(SERVER_THREAD);
        KeyId id = id(service, key);

        assertEquals(AEAmount.of(4L), broker.extract(id, AEAmount.of(4L), Actionable.MODULATE, SOURCE));
        assertEquals(5L, storage.amount);
        assertEquals(AEAmount.of(5L), broker.captureSnapshot().amount(id));
    }

    private static StorageService service(MutableStorage... storages) {
        StorageService service = new StorageService();
        IStorageProvider provider = mock(IStorageProvider.class);
        doAnswer(invocation -> {
            IStorageMounts mounts = invocation.getArgument(0);
            for (int index = 0; index < storages.length; index++) {
                mounts.mount(storages[index], index);
            }
            return null;
        }).when(provider).mountInventories(any());
        service.addGlobalStorageProvider(provider);
        assertTrue(service.getExactStorage().isValid());
        return service;
    }

    private static KeyId id(StorageService service, AEKey key) {
        KeyId id = service.getExactStorage().keyRegistry().lookup(key);
        if (id == null) {
            throw new AssertionError("mounted native key was not interned");
        }
        return id;
    }

    private static AEKey key() {
        AEKey key = mock(AEKey.class);
        when(key.getPrimaryKey()).thenReturn(new Object());
        return key;
    }

    private static MutableStorage storage(AEKey key, long amount) {
        return new MutableStorage(key, amount);
    }

    private static final class MutableStorage implements MEStorage {
        private final AEKey key;
        private long amount;
        private long reportedExtractAdjustment;
        private RuntimeException throwAfterExtractMutation;
        private AEKey lastRequestedKey;

        private MutableStorage(AEKey key, long amount) {
            this.key = key;
            this.amount = amount;
        }

        @Override
        public long insert(AEKey requestedKey, long requested, Actionable mode, IActionSource source) {
            lastRequestedKey = requestedKey;
            if (requestedKey != key) {
                return 0L;
            }
            long inserted = Math.min(requested, Long.MAX_VALUE - amount);
            if (mode == Actionable.MODULATE) {
                amount += inserted;
            }
            return inserted;
        }

        @Override
        public long extract(AEKey requestedKey, long requested, Actionable mode, IActionSource source) {
            lastRequestedKey = requestedKey;
            if (requestedKey != key) {
                return 0L;
            }
            long extracted = Math.min(requested, amount);
            if (mode == Actionable.MODULATE) {
                amount -= extracted;
                if (throwAfterExtractMutation != null) {
                    throw throwAfterExtractMutation;
                }
            }
            return extracted + reportedExtractAdjustment;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            if (amount != 0L) {
                out.add(key, amount);
            }
        }

        @Override
        public net.minecraft.network.chat.Component getDescription() {
            return null;
        }
    }
}

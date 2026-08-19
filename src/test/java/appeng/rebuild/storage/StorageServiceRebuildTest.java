package appeng.rebuild.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.quantity.AEAmount;

/** Reconciliation and lifecycle tests for the exact view over legacy storage delegates. */
class StorageServiceRebuildTest {

    @Test
    void startsInvalidAndDirtyThenTheFirstEmptyReconcileSucceeds() {
        StorageServiceRebuild service = service();

        assertFalse(service.isValid());
        assertTrue(service.isDirty());
        assertThrows(IllegalStateException.class, service::ledger);

        assertTrue(service.reconcileEndTick());

        assertTrue(service.isValid());
        assertFalse(service.isDirty());
        assertEquals(Map.of(), enumerate(service.ledger()));
    }

    @Test
    void independentlyMountedLocationsAggregateLongMaxExactlyAboveLong() {
        StorageServiceRebuild service = service();
        AEKey key = key();
        CountingStorage first = storage(key, Long.MAX_VALUE);
        CountingStorage second = storage(key, Long.MAX_VALUE);
        service.mounted(1, first);
        service.mounted(2, second);

        assertTrue(service.reconcileEndTick());

        KeyId keyId = service.keyRegistry().lookup(key);
        AEAmount expected = AEAmount.of(
                BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(2L)));
        assertEquals(expected, service.ledger().amount(keyId));
        assertEquals(2, service.mountedLocations().size());
        assertEquals(2, service.ledger().locationIndex().locations(keyId).size());
    }

    @Test
    void duplicateMountsOfOneDelegateReceiveDistinctLocationsAndCountTwice() {
        StorageServiceRebuild service = service();
        AEKey key = key();
        CountingStorage storage = storage(key, 7L);
        service.mounted(4, storage);
        service.mounted(9, storage);

        Set<StorageLocationId> mountedIds = service.mountedLocations();
        assertEquals(2, service.mountedLocationCount());
        assertEquals(2, mountedIds.size());
        assertTrue(service.reconcileEndTick());

        KeyId keyId = service.keyRegistry().lookup(key);
        assertEquals(AEAmount.of(14L), service.ledger().amount(keyId));
        assertEquals(2, service.ledger().locationIndex().locations(keyId).size());
        assertEquals(2, storage.scanCount);
    }

    @Test
    void markDirtyInvalidatesQueriesUntilReconcileAndUnchangedReconcilePreservesRevisions() {
        StorageServiceRebuild service = service();
        AEKey key = key();
        CountingStorage storage = storage(key, 5L);
        service.mounted(1, storage);
        assertTrue(service.reconcileEndTick());
        KeyId keyId = service.keyRegistry().lookup(key);
        StorageRevision revision = service.ledger().revision();
        long keyRevision = service.ledger().keyRevision(keyId);
        int scans = storage.scanCount;

        service.markDirty();
        assertFalse(service.isValid());
        assertTrue(service.isDirty());
        assertThrows(IllegalStateException.class, service::ledger);

        assertTrue(service.reconcileEndTick());
        assertEquals(revision, service.ledger().revision());
        assertEquals(keyRevision, service.ledger().keyRevision(keyId));
        assertEquals(scans + 1, storage.scanCount);
    }

    @Test
    void throwingLocationLeavesPriorLedgerButLedgerQueryRemainsUnavailable() {
        StorageServiceRebuild service = service();
        AEKey key = key();
        CountingStorage stable = storage(key, 11L);
        service.mounted(1, stable);
        assertTrue(service.reconcileEndTick());
        StorageLedger priorLedger = service.ledger();
        StorageRevision priorRevision = priorLedger.revision();
        AEAmount priorAmount = priorLedger.amount(service.keyRegistry().lookup(key));

        CountingStorage throwing = storage(key, 19L);
        throwing.failure = new IllegalStateException("legacy scan failed");
        service.mounted(2, throwing);
        assertThrows(IllegalStateException.class, service::ledger);

        assertFalse(service.reconcileEndTick());
        assertFalse(service.isValid());
        assertTrue(service.isDirty());
        assertNotNull(service.lastFailure());
        assertThrows(IllegalStateException.class, service::ledger);
        assertEquals(priorRevision, priorLedger.revision());
        assertEquals(priorAmount, priorLedger.amount(service.keyRegistry().lookup(key)));

        service.unmounted(2, throwing);
        assertTrue(service.reconcileEndTick());
        assertEquals(priorAmount, service.ledger().amount(service.keyRegistry().lookup(key)));
    }

    @Test
    void negativeLegacyAmountRejectsAndLaterCallScansEachLocationOnce() {
        StorageServiceRebuild service = service();
        AEKey key = key();
        CountingStorage stable = storage(key, 3L);
        CountingStorage negative = storage(key, 4L);
        negative.negativeAmount = true;
        service.mounted(1, stable);
        service.mounted(2, negative);

        assertFalse(service.reconcileEndTick());
        assertFalse(service.isValid());
        assertTrue(service.isDirty());
        assertTrue(service.lastFailure() instanceof IllegalArgumentException);
        assertEquals(1, stable.scanCount);
        assertEquals(1, negative.scanCount);

        negative.negativeAmount = false;
        assertTrue(service.reconcileEndTick());

        KeyId keyId = service.keyRegistry().lookup(key);
        assertEquals(AEAmount.of(7L), service.ledger().amount(keyId));
        assertEquals(2, stable.scanCount);
        assertEquals(2, negative.scanCount);
    }

    @Test
    void outOfBandMutationAppearsAtTheNextReconcileWithoutMarkDirty() {
        StorageServiceRebuild service = service();
        AEKey key = key();
        CountingStorage storage = storage(key, 5L);
        service.mounted(1, storage);
        assertTrue(service.reconcileEndTick());
        KeyId keyId = service.keyRegistry().lookup(key);
        StorageRevision revision = service.ledger().revision();

        storage.amount = 8L;
        assertTrue(service.reconcileEndTick());

        assertEquals(AEAmount.of(8L), service.ledger().amount(keyId));
        assertEquals(revision.next(), service.ledger().revision());
        assertEquals(2L, service.ledger().keyRevision(keyId));
    }

    @Test
    void unmountRemovesOnlyTheIdentityAndPriorityMatchedLocation() {
        StorageServiceRebuild service = service();
        AEKey key = key();
        CountingStorage first = storage(key, 3L);
        CountingStorage second = storage(key, 7L);
        service.mounted(1, first);
        service.mounted(2, second);
        assertTrue(service.reconcileEndTick());

        service.unmounted(1, first);
        assertFalse(service.isValid());
        assertTrue(service.isDirty());
        assertEquals(1, service.mountedLocationCount());
        assertTrue(service.reconcileEndTick());

        KeyId keyId = service.keyRegistry().lookup(key);
        assertEquals(AEAmount.of(7L), service.ledger().amount(keyId));
        assertEquals(1, service.ledger().locationIndex().locations(keyId).size());
    }

    @Test
    void mountedLocationsIsImmutableAndQueriesDoNotScanLegacyStorage() {
        StorageServiceRebuild service = service();
        AEKey key = key();
        CountingStorage storage = storage(key, 2L);
        service.mounted(1, storage);

        assertEquals(0, storage.scanCount);
        assertEquals(1, service.mountedLocationCount());
        Set<StorageLocationId> ids = service.mountedLocations();
        assertThrows(UnsupportedOperationException.class, ids::clear);
        assertEquals(1, service.mountedLocationCount());
        assertFalse(service.isValid());
        assertTrue(service.isDirty());
        assertNotNull(service.keyRegistry());
        assertNull(service.lastFailure());
        assertThrows(IllegalStateException.class, service::ledger);
        assertEquals(0, storage.scanCount);

        assertTrue(service.reconcileEndTick());
        int scans = storage.scanCount;
        KeyId keyId = service.keyRegistry().lookup(key);
        assertEquals(AEAmount.of(2L), service.ledger().amount(keyId));
        assertEquals(scans, storage.scanCount);
    }

    private static StorageServiceRebuild service() {
        return new StorageServiceRebuild(new KeyRegistry(1L));
    }

    private static AEKey key() {
        AEKey key = mock(AEKey.class);
        when(key.getPrimaryKey()).thenReturn(new Object());
        return key;
    }

    private static CountingStorage storage(AEKey key, long amount) {
        return new CountingStorage(key, amount);
    }

    private static Map<KeyId, AEAmount> enumerate(StorageLedger ledger) {
        Map<KeyId, AEAmount> values = new HashMap<>();
        ledger.enumerate(values::put);
        return values;
    }

    private static final class CountingStorage implements MEStorage {
        private final AEKey key;
        private long amount;
        private int scanCount;
        private boolean negativeAmount;
        private RuntimeException failure;

        private CountingStorage(AEKey key, long amount) {
            this.key = key;
            this.amount = amount;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            scanCount++;
            if (failure != null) {
                throw failure;
            }
            out.add(key, negativeAmount ? -1L : amount);
        }

        @Override
        public net.minecraft.network.chat.Component getDescription() {
            return null;
        }
    }
}

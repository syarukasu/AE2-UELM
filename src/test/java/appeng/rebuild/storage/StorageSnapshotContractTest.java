package appeng.rebuild.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;

/** Exact shape, copy, and capture-boundary tests for planner storage inputs. */
class StorageSnapshotContractTest {

    @Test
    void preservesNonZeroOrderAndAmountsBeyondLongWithoutAliasingInputs() {
        AmountVector source = new AmountVector(4);
        AEAmount huge = AEAmount.of(BigInteger.TEN.pow(1000));
        source.set(1, huge);
        source.set(3, AEAmount.of(BigInteger.ONE.shiftLeft(128)));
        long[] keyRevisions = { 0L, 4L, 0L, 9L };

        StorageSnapshot snapshot = new StorageSnapshot(7L, new StorageRevision(12L), 4, source, keyRevisions);

        source.set(1, AEAmount.ZERO);
        keyRevisions[1] = 99L;

        assertEquals(7L, snapshot.keyRegistryGeneration());
        assertEquals(new StorageRevision(12L), snapshot.revision());
        assertEquals(4, snapshot.keyCount());
        assertEquals(huge, snapshot.amount(new KeyId(1)));
        assertEquals(AEAmount.of(BigInteger.ONE.shiftLeft(128)), snapshot.amount(new KeyId(3)));
        assertEquals(List.of(new KeyId(1), new KeyId(3)), snapshot.nonZeroKeys());
        assertEquals(4L, snapshot.keyRevision(new KeyId(1)));
        assertEquals(9L, snapshot.keyRevision(new KeyId(3)));
    }

    @Test
    void amountCopyAndNonZeroListAreIndependentAndReadOnly() {
        AmountVector source = new AmountVector(2);
        source.set(0, AEAmount.of(5L));
        StorageSnapshot snapshot = new StorageSnapshot(1L, StorageRevision.ZERO, 2, source, new long[] { 1L, 0L });

        AmountVector copy = snapshot.copyAmounts();
        copy.set(0, AEAmount.of(99L));

        assertEquals(AEAmount.of(5L), snapshot.amount(new KeyId(0)));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.nonZeroKeys().clear());
    }

    @Test
    void exactAmountMatrixAndDeterministicBigIntegerConservationSurviveCopy() {
        int keyCount = 512;
        AmountVector source = new AmountVector(keyCount);
        long[] keyRevisions = new long[keyCount];
        Random random = new Random(0xAE2BEEFL);
        BigInteger expectedTotal = BigInteger.ZERO;
        int expectedNonZero = 0;
        for (int index = 0; index < keyCount; index++) {
            AEAmount amount = switch (index) {
                case 0 -> AEAmount.ZERO;
                case 1 -> AEAmount.ONE;
                case 2 -> AEAmount.of(Long.MAX_VALUE);
                case 3 -> AEAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
                case 4 -> AEAmount.of(BigInteger.ONE.shiftLeft(128));
                case 5 -> AEAmount.of(BigInteger.TEN.pow(1000));
                default -> index % 11 == 0 ? AEAmount.ZERO : AEAmount.of(new BigInteger(320, random));
            };
            source.set(index, amount);
            keyRevisions[index] = index;
            expectedTotal = expectedTotal.add(amount.toBigInteger());
            if (!amount.equals(AEAmount.ZERO)) {
                expectedNonZero++;
            }
        }

        StorageSnapshot snapshot = new StorageSnapshot(8L, new StorageRevision(13L), keyCount, source, keyRevisions);
        AmountVector copy = snapshot.copyAmounts();
        BigInteger copiedTotal = BigInteger.ZERO;
        for (KeyId key : snapshot.nonZeroKeys()) {
            copiedTotal = copiedTotal.add(snapshot.amount(key).toBigInteger());
        }

        copy.set(1, AEAmount.ZERO);
        assertEquals(expectedTotal, copiedTotal);
        assertEquals(expectedNonZero, snapshot.nonZeroKeys().size());
        assertEquals(AEAmount.ONE, snapshot.amount(new KeyId(1)));
        assertEquals(AEAmount.of(Long.MAX_VALUE), snapshot.amount(new KeyId(2)));
        assertEquals(AEAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
                snapshot.amount(new KeyId(3)));
        assertEquals(AEAmount.of(BigInteger.ONE.shiftLeft(128)), snapshot.amount(new KeyId(4)));
        assertEquals(AEAmount.of(BigInteger.TEN.pow(1000)), snapshot.amount(new KeyId(5)));
    }

    @Test
    void rejectsMismatchedShapesNegativeRevisionsAndOutOfRangeKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> new StorageSnapshot(1L, StorageRevision.ZERO, 2, new AmountVector(1), new long[] { 0L, 0L }));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageSnapshot(1L, StorageRevision.ZERO, 1, new AmountVector(1), new long[] { -1L }));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageSnapshot(1L, StorageRevision.ZERO, 2, new AmountVector(2), new long[] { 0L }));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageSnapshot(1L, StorageRevision.ZERO, 1, new AmountVector(1), new long[] { 0L, 0L }));

        StorageSnapshot empty = new StorageSnapshot(1L, StorageRevision.ZERO, 0, new AmountVector(0), new long[0]);
        assertThrows(IndexOutOfBoundsException.class, () -> empty.amount(new KeyId(0)));
        assertThrows(IndexOutOfBoundsException.class, () -> empty.keyRevision(new KeyId(0)));
    }

    @Test
    void captureIsUnavailableWhileDirtyAndDoesNotImplicitlyScanOrReconcile() {
        KeyRegistry registry = new KeyRegistry(3L);
        StorageServiceRebuild service = new StorageServiceRebuild(registry);
        CountingStorage storage = storage(key(), 17L);
        service.mounted(2, storage);

        StorageSnapshotCaptureResult result = service.captureSnapshot();

        assertEquals(new StorageSnapshotCaptureResult.Failure(
                StorageSnapshotCaptureResult.FailureReason.UNAVAILABLE), result);
        assertFalse(service.isValid());
        assertTrue(service.isDirty());
        assertEquals(0, storage.scans);
    }

    @Test
    void successfulCaptureReadsCommittedLedgerOnlyAndRemainsStableAfterLegacyMutation() {
        KeyRegistry registry = new KeyRegistry(4L);
        StorageServiceRebuild service = new StorageServiceRebuild(registry);
        AEKey key = key();
        CountingStorage storage = storage(key, 17L);
        service.mounted(2, storage);

        assertTrue(service.reconcileEndTick());
        int scansAfterReconcile = storage.scans;
        StorageSnapshotCaptureResult.Success result = assertInstanceOf(StorageSnapshotCaptureResult.Success.class,
                service.captureSnapshot());
        StorageSnapshot snapshot = result.snapshot();
        StorageRevision revision = snapshot.revision();

        storage.amount = 99L;
        StorageSnapshotCaptureResult.Success second = assertInstanceOf(StorageSnapshotCaptureResult.Success.class,
                service.captureSnapshot());

        assertEquals(scansAfterReconcile, storage.scans);
        assertEquals(revision, second.snapshot().revision());
        assertEquals(AEAmount.of(17L), snapshot.amount(new KeyId(0)));
        assertEquals(AEAmount.of(17L), second.snapshot().amount(new KeyId(0)));
        assertEquals(List.of(new KeyId(0)), snapshot.nonZeroKeys());
    }

    @Test
    void captureCarriesLedgerKeyRevisionsAndBigExactTotals() {
        KeyRegistry registry = new KeyRegistry(5L);
        AEKey key = key();
        registry.intern(key);
        StorageLedger ledger = new StorageLedger(registry);
        StorageLocationId location = new StorageLocationId(11L);
        AmountVector values = new AmountVector(1);
        values.set(0, AEAmount.of(BigInteger.ONE.shiftLeft(128)));
        ledger.replaceAll(java.util.Map.of(location, new StorageLocationSnapshot(location, values)),
                java.util.Set.of());

        StorageSnapshot snapshot = ledger.captureSnapshot();

        assertEquals(new StorageRevision(1L), snapshot.revision());
        assertEquals(1L, snapshot.keyRevision(new KeyId(0)));
        assertEquals(AEAmount.of(BigInteger.ONE.shiftLeft(128)), snapshot.amount(new KeyId(0)));
    }

    @Test
    void captureReturnsTypedKeyLimitWithoutCallingLegacyStorage() {
        KeyRegistry registry = mock(KeyRegistry.class);
        AtomicBoolean overLimit = new AtomicBoolean(false);
        AEKey key = key();
        when(registry.generation()).thenReturn(6L);
        when(registry.size())
                .thenAnswer(invocation -> overLimit.get() ? PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS + 1 : 1);
        when(registry.intern(key)).thenReturn(new KeyId(0));
        StorageServiceRebuild service = new StorageServiceRebuild(registry);
        CountingStorage storage = storage(key, 3L);
        service.mounted(1, storage);
        assertTrue(service.reconcileEndTick());
        int scansAfterReconcile = storage.scans;

        overLimit.set(true);
        StorageSnapshotCaptureResult result = service.captureSnapshot();

        assertEquals(new StorageSnapshotCaptureResult.Failure(StorageSnapshotCaptureResult.FailureReason.KEY_LIMIT),
                result);
        assertEquals(scansAfterReconcile, storage.scans);
    }

    @Test
    void snapshotKeyLimitIsFiniteAndPubliclyDeclared() {
        assertTrue(PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS > 0);
        assertEquals(1_048_576, PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS);
        assertThrows(IllegalArgumentException.class, () -> new StorageSnapshot(
                1L, StorageRevision.ZERO, PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS + 1,
                new AmountVector(0), new long[0]));
    }

    @Test
    void acceptsExactMaximumKeyCountAndTheLastKeyBoundary() {
        int keyCount = PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS;
        AmountVector amounts = new AmountVector(keyCount);
        long[] keyRevisions = new long[keyCount];
        amounts.set(keyCount - 1, AEAmount.ONE);
        keyRevisions[keyCount - 1] = 1L;

        StorageSnapshot snapshot = new StorageSnapshot(1L, StorageRevision.ZERO, keyCount, amounts, keyRevisions);

        assertEquals(keyCount, snapshot.keyCount());
        assertEquals(AEAmount.ONE, snapshot.amount(new KeyId(keyCount - 1)));
        assertEquals(1L, snapshot.keyRevision(new KeyId(keyCount - 1)));
        assertEquals(List.of(new KeyId(keyCount - 1)), snapshot.nonZeroKeys());
    }

    private static AEKey key() {
        AEKey key = mock(AEKey.class);
        when(key.getPrimaryKey()).thenReturn(new Object());
        return key;
    }

    private static CountingStorage storage(AEKey key, long amount) {
        return new CountingStorage(key, amount);
    }

    private static final class CountingStorage implements MEStorage {
        private final AEKey key;
        private long amount;
        private int scans;

        private CountingStorage(AEKey key, long amount) {
            this.key = key;
            this.amount = amount;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            scans++;
            out.add(key, amount);
        }

        @Override
        public net.minecraft.network.chat.Component getDescription() {
            return null;
        }
    }
}

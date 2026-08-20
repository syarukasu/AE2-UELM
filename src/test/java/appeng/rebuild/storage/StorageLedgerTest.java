package appeng.rebuild.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEKey;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;

/** Atomicity, exactness, and revision tests for the aggregate storage ledger. */
class StorageLedgerTest {

    @Test
    void aggregatesLongMaxFromTwoLocationsWithoutOverflow() {
        Fixture fixture = fixture(1);
        StorageLocationId first = new StorageLocationId(1L);
        StorageLocationId second = new StorageLocationId(2L);
        AEAmount longMax = AEAmount.of(Long.MAX_VALUE);

        fixture.ledger.replaceAll(
                Map.of(first, snapshot(first, 1, Map.of(0, longMax)), second, snapshot(second, 1, Map.of(0, longMax))),
                Set.of());

        AEAmount expected = AEAmount.of(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(2L)));
        assertEquals(expected, fixture.ledger.amount(fixture.ids.get(0)));
        assertEquals(expected, enumerate(fixture.ledger).get(fixture.ids.get(0)));
    }

    @Test
    void preservesVeryLargeExactValues() {
        Fixture fixture = fixture(2);
        StorageLocationId location = new StorageLocationId(3L);
        AEAmount twoTo128 = AEAmount.of(BigInteger.ONE.shiftLeft(128));
        AEAmount tenTo1000 = AEAmount.of(BigInteger.TEN.pow(1000));

        fixture.ledger.replaceAll(
                Map.of(location, snapshot(location, 2, Map.of(0, twoTo128, 1, tenTo1000))),
                Set.of());

        assertEquals(twoTo128, fixture.ledger.amount(fixture.ids.get(0)));
        assertEquals(tenTo1000, fixture.ledger.amount(fixture.ids.get(1)));
        assertEquals(Map.of(fixture.ids.get(0), twoTo128, fixture.ids.get(1), tenTo1000), enumerate(fixture.ledger));
    }

    @Test
    void replacementSubtractsOldContentsAndAddsNewContentsExactly() {
        Fixture fixture = fixture(2);
        StorageLocationId location = new StorageLocationId(4L);
        StorageLocationSnapshot initial = snapshot(location, 2, Map.of(
                0, AEAmount.of(10L),
                1, AEAmount.of(20L)));
        StorageLocationSnapshot replacement = snapshot(location, 2, Map.of(0, AEAmount.of(100L)));

        fixture.ledger.replaceAll(Map.of(location, initial), Set.of());
        fixture.ledger.replaceAll(Map.of(location, replacement), Set.of());

        assertEquals(AEAmount.of(100L), fixture.ledger.amount(fixture.ids.get(0)));
        assertEquals(AEAmount.ZERO, fixture.ledger.amount(fixture.ids.get(1)));
        assertEquals(Set.of(location), fixture.ledger.locationIndex().locations(fixture.ids.get(0)));
        assertEquals(Set.of(), fixture.ledger.locationIndex().locations(fixture.ids.get(1)));
    }

    @Test
    void unmountRemovesOnlyTheSelectedLocation() {
        Fixture fixture = fixture(2);
        StorageLocationId first = new StorageLocationId(10L);
        StorageLocationId second = new StorageLocationId(11L);
        StorageLocationSnapshot secondSnapshot = snapshot(second, 2, Map.of(
                0, AEAmount.of(22L),
                1, AEAmount.of(7L)));

        fixture.ledger.replaceAll(
                Map.of(
                        first, snapshot(first, 2, Map.of(0, AEAmount.of(11L))),
                        second, secondSnapshot),
                Set.of());
        fixture.ledger.replaceAll(Map.of(), Set.of(first));

        assertNull(fixture.ledger.snapshot(first));
        assertEquals(secondSnapshot, fixture.ledger.snapshot(second));
        assertEquals(AEAmount.of(22L), fixture.ledger.amount(fixture.ids.get(0)));
        assertEquals(AEAmount.of(7L), fixture.ledger.amount(fixture.ids.get(1)));
        assertEquals(Set.of(second), fixture.ledger.locationIndex().locations(fixture.ids.get(0)));
    }

    @Test
    void replacingWithZeroClearsTheReverseIndex() {
        Fixture fixture = fixture(1);
        StorageLocationId location = new StorageLocationId(12L);
        StorageLocationSnapshot populated = snapshot(location, 1, Map.of(0, AEAmount.of(7L)));
        StorageLocationSnapshot zero = snapshot(location, 1, Map.of());

        fixture.ledger.replaceAll(Map.of(location, populated), Set.of());
        fixture.ledger.replaceAll(Map.of(location, zero), Set.of());

        assertEquals(AEAmount.ZERO, fixture.ledger.amount(fixture.ids.get(0)));
        assertEquals(Set.of(), fixture.ledger.locationIndex().locations(fixture.ids.get(0)));
        assertEquals(0, fixture.ledger.locationIndex().indexedKeyCount());
        assertEquals(Map.of(), enumerate(fixture.ledger));
    }

    @Test
    void ledgerIndexReadsAreIndependentCopies() {
        Fixture fixture = fixture(1);
        StorageLocationId location = new StorageLocationId(13L);
        StorageLocationSnapshot contents = snapshot(location, 1, Map.of(0, AEAmount.of(7L)));
        fixture.ledger.replaceAll(Map.of(location, contents), Set.of());

        StorageLocationIndex copy = fixture.ledger.locationIndex();
        copy.remove(location, contents);

        assertEquals(Set.of(location), fixture.ledger.locationIndex().locations(fixture.ids.get(0)));
    }

    @Test
    void invalidBatchesLeaveAmountsIndexRevisionsAndSnapshotsUnchanged() {
        Fixture fixture = fixture(2);
        StorageLocationId first = new StorageLocationId(20L);
        StorageLocationId second = new StorageLocationId(21L);
        StorageLocationSnapshot firstSnapshot = snapshot(first, 2, Map.of(0, AEAmount.of(5L)));
        StorageLocationSnapshot secondSnapshot = snapshot(second, 2, Map.of(1, AEAmount.of(7L)));
        fixture.ledger.replaceAll(Map.of(first, firstSnapshot, second, secondSnapshot), Set.of());

        Map<StorageLocationId, StorageLocationSnapshot> overlap = new HashMap<>();
        overlap.put(first, firstSnapshot);
        assertRejectedUnchanged(fixture, overlap, Set.of(first), IllegalArgumentException.class);

        Map<StorageLocationId, StorageLocationSnapshot> mismatch = new HashMap<>();
        mismatch.put(first, secondSnapshot);
        assertRejectedUnchanged(fixture, mismatch, Set.of(), IllegalArgumentException.class);

        int outOfRegistry = fixture.registry.size();
        StorageLocationId invalidLocation = new StorageLocationId(22L);
        Map<StorageLocationId, StorageLocationSnapshot> invalidKey = new HashMap<>();
        invalidKey.put(invalidLocation, snapshot(invalidLocation, outOfRegistry + 1,
                Map.of(outOfRegistry, AEAmount.ONE)));
        assertRejectedUnchanged(fixture, invalidKey, Set.of(), IndexOutOfBoundsException.class);

        Map<StorageLocationId, StorageLocationSnapshot> nullKey = new HashMap<>();
        nullKey.put(null, firstSnapshot);
        assertRejectedUnchanged(fixture, nullKey, Set.of(), NullPointerException.class);

        Map<StorageLocationId, StorageLocationSnapshot> nullValue = new HashMap<>();
        nullValue.put(invalidLocation, null);
        assertRejectedUnchanged(fixture, nullValue, Set.of(), NullPointerException.class);

        Set<StorageLocationId> nullRemoval = new HashSet<>();
        nullRemoval.add(null);
        assertRejectedUnchanged(fixture, Map.of(), nullRemoval, NullPointerException.class);
    }

    @Test
    void sameBatchChangesIncrementGlobalAndEachChangedKeyRevisionOnce() {
        Fixture fixture = fixture(2);
        StorageLocationId first = new StorageLocationId(30L);
        StorageLocationId second = new StorageLocationId(31L);

        fixture.ledger.replaceAll(
                Map.of(
                        first, snapshot(first, 2, Map.of(0, AEAmount.of(1L))),
                        second, snapshot(second, 2, Map.of(
                                0, AEAmount.of(2L),
                                1, AEAmount.of(3L)))),
                Set.of());

        assertEquals(new StorageRevision(1L), fixture.ledger.revision());
        assertEquals(1L, fixture.ledger.keyRevision(fixture.ids.get(0)));
        assertEquals(1L, fixture.ledger.keyRevision(fixture.ids.get(1)));
    }

    @Test
    void unrelatedKeyRevisionRemainsStable() {
        Fixture fixture = fixture(3);
        StorageLocationId location = new StorageLocationId(32L);
        StorageLocationSnapshot initial = snapshot(location, 3, Map.of(
                0, AEAmount.of(4L),
                1, AEAmount.of(8L)));
        StorageLocationSnapshot replacement = snapshot(location, 3, Map.of(
                0, AEAmount.of(9L),
                1, AEAmount.of(8L)));

        fixture.ledger.replaceAll(Map.of(location, initial), Set.of());
        long unrelatedBefore = fixture.ledger.keyRevision(fixture.ids.get(1));
        fixture.ledger.replaceAll(Map.of(location, replacement), Set.of());

        assertEquals(2L, fixture.ledger.keyRevision(fixture.ids.get(0)));
        assertEquals(unrelatedBefore, fixture.ledger.keyRevision(fixture.ids.get(1)));
        assertEquals(0L, fixture.ledger.keyRevision(fixture.ids.get(2)));
    }

    @Test
    void exactLocationRelocationChangesGlobalRevisionButNotKeyRevision() {
        Fixture fixture = fixture(1);
        StorageLocationId oldLocation = new StorageLocationId(40L);
        StorageLocationId newLocation = new StorageLocationId(41L);
        StorageLocationSnapshot oldSnapshot = snapshot(oldLocation, 1, Map.of(0, AEAmount.of(6L)));
        StorageLocationSnapshot newSnapshot = snapshot(newLocation, 1, Map.of(0, AEAmount.of(6L)));

        fixture.ledger.replaceAll(Map.of(oldLocation, oldSnapshot), Set.of());
        long keyRevisionBefore = fixture.ledger.keyRevision(fixture.ids.get(0));
        fixture.ledger.replaceAll(Map.of(newLocation, newSnapshot), Set.of(oldLocation));

        assertEquals(new StorageRevision(2L), fixture.ledger.revision());
        assertEquals(keyRevisionBefore, fixture.ledger.keyRevision(fixture.ids.get(0)));
        assertEquals(AEAmount.of(6L), fixture.ledger.amount(fixture.ids.get(0)));
        assertNull(fixture.ledger.snapshot(oldLocation));
        assertEquals(Set.of(newLocation), fixture.ledger.locationIndex().locations(fixture.ids.get(0)));
    }

    @Test
    void equalContentBatchDoesNotAdvanceAnyRevision() {
        Fixture fixture = fixture(1);
        StorageLocationId location = new StorageLocationId(50L);
        StorageLocationSnapshot initial = snapshot(location, 1, Map.of(0, AEAmount.of(6L)));
        StorageLocationSnapshot equalWithTrailingCapacity = snapshot(location, 5, Map.of(0, AEAmount.of(6L)));

        fixture.ledger.replaceAll(Map.of(location, initial), Set.of());
        StorageRevision revisionBefore = fixture.ledger.revision();
        long keyRevisionBefore = fixture.ledger.keyRevision(fixture.ids.get(0));
        fixture.ledger.replaceAll(Map.of(location, equalWithTrailingCapacity), Set.of());

        assertEquals(revisionBefore, fixture.ledger.revision());
        assertEquals(keyRevisionBefore, fixture.ledger.keyRevision(fixture.ids.get(0)));
        assertEquals(initial, fixture.ledger.snapshot(location));
    }

    @Test
    void registryGrowthAfterLedgerConstructionIsSupported() {
        KeyRegistry registry = new KeyRegistry(60L);
        StorageLedger ledger = new StorageLedger(registry);
        KeyId firstKey = registry.intern(mock(AEKey.class));
        StorageLocationId firstLocation = new StorageLocationId(60L);

        ledger.replaceAll(Map.of(firstLocation, snapshot(firstLocation, 1, Map.of(0, AEAmount.of(2L)))), Set.of());
        KeyId secondKey = registry.intern(mock(AEKey.class));
        StorageLocationId secondLocation = new StorageLocationId(61L);
        ledger.replaceAll(Map.of(secondLocation, snapshot(secondLocation, 2, Map.of(1, AEAmount.of(3L)))), Set.of());

        assertEquals(AEAmount.of(2L), ledger.amount(firstKey));
        assertEquals(AEAmount.of(3L), ledger.amount(secondKey));
        assertEquals(1L, ledger.keyRevision(firstKey));
        assertEquals(1L, ledger.keyRevision(secondKey));
    }

    private static void assertRejectedUnchanged(
            Fixture fixture,
            Map<StorageLocationId, StorageLocationSnapshot> replacements,
            Set<StorageLocationId> removals,
            Class<? extends Throwable> expectedException) {
        Map<KeyId, AEAmount> amountsBefore = enumerate(fixture.ledger);
        StorageLocationIndex indexBefore = fixture.ledger.locationIndex();
        StorageRevision revisionBefore = fixture.ledger.revision();
        long[] keyRevisionsBefore = new long[fixture.ids.size()];
        for (int index = 0; index < fixture.ids.size(); index++) {
            keyRevisionsBefore[index] = fixture.ledger.keyRevision(fixture.ids.get(index));
        }
        Map<StorageLocationId, StorageLocationSnapshot> snapshotsBefore = new HashMap<>();
        for (StorageLocationId location : List.of(new StorageLocationId(20L), new StorageLocationId(21L))) {
            snapshotsBefore.put(location, fixture.ledger.snapshot(location));
        }

        assertThrows(expectedException, () -> fixture.ledger.replaceAll(replacements, removals));

        assertEquals(amountsBefore, enumerate(fixture.ledger));
        assertEquals(revisionBefore, fixture.ledger.revision());
        for (int index = 0; index < fixture.ids.size(); index++) {
            assertEquals(keyRevisionsBefore[index], fixture.ledger.keyRevision(fixture.ids.get(index)));
            assertEquals(indexBefore.locations(fixture.ids.get(index)),
                    fixture.ledger.locationIndex().locations(fixture.ids.get(index)));
        }
        for (Map.Entry<StorageLocationId, StorageLocationSnapshot> entry : snapshotsBefore.entrySet()) {
            assertEquals(entry.getValue(), fixture.ledger.snapshot(entry.getKey()));
        }
    }

    private static Map<KeyId, AEAmount> enumerate(StorageLedger ledger) {
        Map<KeyId, AEAmount> entries = new HashMap<>();
        ledger.enumerate(entries::put);
        return entries;
    }

    private static StorageLocationSnapshot snapshot(
            StorageLocationId location,
            int capacity,
            Map<Integer, AEAmount> values) {
        AmountVector vector = new AmountVector(capacity);
        values.forEach(vector::set);
        return new StorageLocationSnapshot(location, vector);
    }

    private static Fixture fixture(int keyCount) {
        KeyRegistry registry = new KeyRegistry(1L);
        List<KeyId> ids = new ArrayList<>(keyCount);
        for (int index = 0; index < keyCount; index++) {
            ids.add(registry.intern(mock(AEKey.class)));
        }
        return new Fixture(registry, new StorageLedger(registry), ids);
    }

    private record Fixture(KeyRegistry registry, StorageLedger ledger, List<KeyId> ids) {
    }
}

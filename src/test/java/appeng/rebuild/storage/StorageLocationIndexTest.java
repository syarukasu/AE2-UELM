package appeng.rebuild.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;

/** Contract tests for the reverse index of non-zero storage contents. */
class StorageLocationIndexTest {

    @Test
    void indexesMultipleLocationsPerKeyAndMultipleKeysPerLocation() {
        StorageLocationId first = new StorageLocationId(1L);
        StorageLocationId second = new StorageLocationId(2L);
        StorageLocationSnapshot firstSnapshot = snapshot(first, Map.of(
                0, AEAmount.of(3L),
                1, AEAmount.of(5L)));
        StorageLocationSnapshot secondSnapshot = snapshot(second, Map.of(
                0, AEAmount.of(7L),
                2, AEAmount.of(11L)));
        StorageLocationIndex index = new StorageLocationIndex();

        index.replace(first, null, firstSnapshot);
        index.replace(second, null, secondSnapshot);

        assertEquals(Set.of(first, second), index.locations(new KeyId(0)));
        assertEquals(Set.of(first), index.locations(new KeyId(1)));
        assertEquals(Set.of(second), index.locations(new KeyId(2)));
        assertEquals(3, index.indexedKeyCount());
    }

    @Test
    void replacementRemovesStaleMembershipsBeforeAddingNewContents() {
        StorageLocationId location = new StorageLocationId(5L);
        StorageLocationSnapshot previous = snapshot(location, Map.of(
                0, AEAmount.of(3L),
                1, AEAmount.of(5L)));
        StorageLocationSnapshot replacement = snapshot(location, Map.of(
                2, AEAmount.of(7L)));
        StorageLocationIndex index = new StorageLocationIndex();

        index.replace(location, null, previous);
        index.replace(location, previous, replacement);

        assertEquals(Set.of(), index.locations(new KeyId(0)));
        assertEquals(Set.of(), index.locations(new KeyId(1)));
        assertEquals(Set.of(location), index.locations(new KeyId(2)));
        assertEquals(1, index.indexedKeyCount());
    }

    @Test
    void returnedSetsAndCopiesCannotMutateTheSourceIndex() {
        StorageLocationId location = new StorageLocationId(8L);
        StorageLocationSnapshot contents = snapshot(location, Map.of(0, AEAmount.of(13L)));
        StorageLocationIndex index = new StorageLocationIndex();
        index.replace(location, null, contents);

        Set<StorageLocationId> locations = index.locations(new KeyId(0));
        assertThrows(UnsupportedOperationException.class, locations::clear);

        StorageLocationIndex copy = index.copy();
        copy.remove(location, contents);
        assertEquals(Set.of(location), index.locations(new KeyId(0)));
        assertEquals(1, index.indexedKeyCount());
    }

    private static StorageLocationSnapshot snapshot(StorageLocationId location, Map<Integer, AEAmount> values) {
        AmountVector vector = new AmountVector(3);
        values.forEach(vector::set);
        return new StorageLocationSnapshot(location, vector);
    }
}

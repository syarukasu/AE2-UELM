package appeng.rebuild.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;

/** Contract tests for immutable exact contents of one storage location. */
class StorageLocationSnapshotTest {

    @Test
    void constructorAndCopyAmountsDefensivelyCopyVectors() {
        StorageLocationId location = new StorageLocationId(7L);
        AmountVector source = vector(2, Map.of(0, AEAmount.of(4L)));
        StorageLocationSnapshot snapshot = new StorageLocationSnapshot(location, source);

        source.set(0, AEAmount.of(99L));
        assertEquals(AEAmount.of(4L), snapshot.amount(new KeyId(0)));

        AmountVector copy = snapshot.copyAmounts();
        copy.set(0, AEAmount.of(123L));
        assertEquals(AEAmount.of(4L), snapshot.amount(new KeyId(0)));
        assertEquals(location, snapshot.location());
    }

    @Test
    void constructorRejectsNullLocationAndAmounts() {
        AmountVector amounts = vector(1, Map.of());
        assertThrows(NullPointerException.class, () -> new StorageLocationSnapshot(null, amounts));
        assertThrows(
                NullPointerException.class,
                () -> new StorageLocationSnapshot(new StorageLocationId(1L), null));
    }

    @Test
    void equalityAndHashCodeIgnoreTrailingZeroCapacity() {
        StorageLocationId location = new StorageLocationId(3L);
        StorageLocationSnapshot compact = new StorageLocationSnapshot(
                location,
                vector(2, Map.of(1, AEAmount.of(8L))));
        StorageLocationSnapshot overAllocated = new StorageLocationSnapshot(
                location,
                vector(8, Map.of(1, AEAmount.of(8L))));

        assertEquals(compact, overAllocated);
        assertEquals(compact.hashCode(), overAllocated.hashCode());
        assertNotEquals(compact, new StorageLocationSnapshot(
                new StorageLocationId(4L),
                vector(2, Map.of(1, AEAmount.of(8L)))));
    }

    @Test
    void enumerateVisitsOnlyNonZeroAmountsInAscendingKeyOrder() {
        StorageLocationSnapshot snapshot = new StorageLocationSnapshot(
                new StorageLocationId(2L),
                vector(6, Map.of(
                        4, AEAmount.of(BigInteger.ONE.shiftLeft(128)),
                        1, AEAmount.of(7L))));
        List<KeyId> keys = new ArrayList<>();
        List<AEAmount> amounts = new ArrayList<>();

        snapshot.enumerate((key, amount) -> {
            keys.add(key);
            amounts.add(amount);
        });

        assertEquals(List.of(new KeyId(1), new KeyId(4)), keys);
        assertEquals(
                List.of(AEAmount.of(7L), AEAmount.of(BigInteger.ONE.shiftLeft(128))),
                amounts);
        assertEquals(AEAmount.ZERO, snapshot.amount(new KeyId(99)));
    }

    private static AmountVector vector(int size, Map<Integer, AEAmount> values) {
        AmountVector vector = new AmountVector(size);
        values.forEach(vector::set);
        return vector;
    }
}

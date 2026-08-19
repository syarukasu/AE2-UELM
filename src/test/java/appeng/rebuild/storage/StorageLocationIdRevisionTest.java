package appeng.rebuild.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Boundary tests for physical location ids and storage revisions. */
class StorageLocationIdRevisionTest {

    @Test
    void locationIdsRejectNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> new StorageLocationId(-1L));
        assertEquals(0L, new StorageLocationId(0L).value());
        assertEquals(Long.MAX_VALUE, new StorageLocationId(Long.MAX_VALUE).value());
    }

    @Test
    void revisionsStartAtZeroAndRejectNegativeValues() {
        assertEquals(0L, StorageRevision.ZERO.value());
        assertEquals(StorageRevision.ZERO, new StorageRevision(0L));
        assertEquals(new StorageRevision(1L), StorageRevision.ZERO.next());
        assertThrows(IllegalArgumentException.class, () -> new StorageRevision(-1L));
    }

    @Test
    void maximumRevisionDoesNotWrap() {
        assertThrows(ArithmeticException.class, () -> new StorageRevision(Long.MAX_VALUE).next());
    }
}

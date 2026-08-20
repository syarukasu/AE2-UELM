package appeng.rebuild.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/** Exactness, atomicity, revision, and server-thread contract tests for the broker reference endpoint. */
class ExactBrokerInventoryTest {

    private static final IActionSource SOURCE = IActionSource.empty();

    @Test
    void preservesAllExactBoundariesThroughRevisionedSnapshots() {
        Fixture fixture = fixture(6);
        AEAmount maximumBitLength = amountAtMaximumBitLength();
        List<AEAmount> values = List.of(
                AEAmount.ZERO,
                AEAmount.ONE,
                AEAmount.of(Long.MAX_VALUE),
                AEAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
                AEAmount.of(BigInteger.ONE.shiftLeft(128)),
                maximumBitLength);

        for (int index = 0; index < values.size(); index++) {
            assertEquals(values.get(index), fixture.inventory.insert(fixture.ids.get(index), values.get(index),
                    Actionable.MODULATE, SOURCE));
        }

        StorageSnapshot snapshot = fixture.inventory.captureSnapshot();
        assertEquals(new StorageRevision(5L), snapshot.revision());
        assertEquals(List.of(fixture.ids.get(1), fixture.ids.get(2), fixture.ids.get(3), fixture.ids.get(4),
                fixture.ids.get(5)), snapshot.nonZeroKeys());
        for (int index = 0; index < values.size(); index++) {
            assertEquals(values.get(index), snapshot.amount(fixture.ids.get(index)), "boundary " + index);
            assertEquals(index == 0 ? 0L : 1L, snapshot.keyRevision(fixture.ids.get(index)),
                    "key revision " + index);
        }
    }

    @Test
    void simulateAndZeroModulateArePureIncludingRevisions() {
        Fixture fixture = fixture(1);
        KeyId key = fixture.ids.get(0);
        AEAmount initial = AEAmount.of(BigInteger.ONE.shiftLeft(128));
        fixture.inventory.insert(key, initial, Actionable.MODULATE, SOURCE);
        StorageSnapshot before = fixture.inventory.captureSnapshot();

        assertEquals(amountAtMaximumBitLength(), fixture.inventory.insert(key, amountAtMaximumBitLength(),
                Actionable.SIMULATE, SOURCE));
        assertEquals(initial, fixture.inventory.extract(key, amountAtMaximumBitLength(), Actionable.SIMULATE,
                SOURCE));
        assertEquals(AEAmount.ZERO,
                fixture.inventory.insert(key, AEAmount.ZERO, Actionable.MODULATE, SOURCE));
        assertEquals(AEAmount.ZERO,
                fixture.inventory.extract(key, AEAmount.ZERO, Actionable.MODULATE, SOURCE));

        assertSnapshotEquals(before, fixture.inventory.captureSnapshot());
    }

    @Test
    void modulatedExtractionReturnsTheExactPartialAmount() {
        Fixture fixture = fixture(1);
        KeyId key = fixture.ids.get(0);
        AEAmount wide = AEAmount.of(BigInteger.ONE.shiftLeft(128));
        AEAmount initial = wide.add(AEAmount.of(5L));
        fixture.inventory.insert(key, initial, Actionable.MODULATE, SOURCE);

        assertEquals(wide, fixture.inventory.extract(key, wide, Actionable.MODULATE, SOURCE));
        assertEquals(AEAmount.of(5L), fixture.inventory.amount(key));
        assertEquals(AEAmount.of(5L),
                fixture.inventory.extract(key, AEAmount.of(Long.MAX_VALUE), Actionable.MODULATE, SOURCE));
        assertEquals(AEAmount.ZERO, fixture.inventory.amount(key));

        StorageSnapshot snapshot = fixture.inventory.captureSnapshot();
        assertEquals(new StorageRevision(3L), snapshot.revision());
        assertEquals(3L, snapshot.keyRevision(key));
        assertEquals(List.of(), snapshot.nonZeroKeys());
    }

    @Test
    void onlyEffectiveModulationsAdvanceGlobalAndAffectedKeyRevisions() {
        Fixture fixture = fixture(2);
        KeyId first = fixture.ids.get(0);
        KeyId second = fixture.ids.get(1);

        fixture.inventory.insert(first, AEAmount.of(4L), Actionable.MODULATE, SOURCE);
        StorageSnapshot one = fixture.inventory.captureSnapshot();
        assertEquals(new StorageRevision(1L), one.revision());
        assertEquals(1L, one.keyRevision(first));
        assertEquals(0L, one.keyRevision(second));

        assertEquals(AEAmount.ZERO,
                fixture.inventory.extract(second, AEAmount.ONE, Actionable.MODULATE, SOURCE));
        fixture.inventory.insert(second, AEAmount.of(7L), Actionable.MODULATE, SOURCE);
        fixture.inventory.extract(first, AEAmount.ONE, Actionable.MODULATE, SOURCE);
        fixture.inventory.amount(first);
        fixture.inventory.enumerate((key, amount) -> {
        });

        StorageSnapshot three = fixture.inventory.captureSnapshot();
        assertEquals(new StorageRevision(3L), three.revision());
        assertEquals(2L, three.keyRevision(first));
        assertEquals(1L, three.keyRevision(second));
        assertEquals(AEAmount.of(3L), three.amount(first));
        assertEquals(AEAmount.of(7L), three.amount(second));
    }

    @Test
    void capturedStateIsImmutableAndIndependentOfLaterInventoryChanges() {
        Fixture fixture = fixture(2);
        KeyId first = fixture.ids.get(0);
        KeyId second = fixture.ids.get(1);
        fixture.inventory.insert(first, AEAmount.of(9L), Actionable.MODULATE, SOURCE);
        StorageSnapshot captured = fixture.inventory.captureSnapshot();

        fixture.inventory.extract(first, AEAmount.of(4L), Actionable.MODULATE, SOURCE);
        fixture.inventory.insert(second, AEAmount.of(BigInteger.ONE.shiftLeft(128)), Actionable.MODULATE, SOURCE);
        var copiedAmounts = captured.copyAmounts();
        copiedAmounts.set(first.value(), AEAmount.of(99L));

        assertEquals(AEAmount.of(9L), captured.amount(first));
        assertEquals(AEAmount.ZERO, captured.amount(second));
        assertEquals(new StorageRevision(1L), captured.revision());
        assertEquals(1L, captured.keyRevision(first));
        assertEquals(0L, captured.keyRevision(second));
        assertEquals(List.of(first), captured.nonZeroKeys());
        assertThrows(UnsupportedOperationException.class, () -> captured.nonZeroKeys().clear());
    }

    @Test
    void callbackReentryIsRejectedBeforeMutationAndEndpointRecovers() {
        Fixture fixture = fixture(1);
        KeyId key = fixture.ids.get(0);
        fixture.inventory.insert(key, AEAmount.of(8L), Actionable.MODULATE, SOURCE);
        StorageSnapshot before = fixture.inventory.captureSnapshot();

        assertThrows(IllegalStateException.class, () -> fixture.inventory.enumerate((visitedKey,
                amount) -> fixture.inventory.extract(visitedKey, AEAmount.ONE, Actionable.MODULATE, SOURCE)));

        assertSnapshotEquals(before, fixture.inventory.captureSnapshot());
        assertEquals(AEAmount.ONE,
                fixture.inventory.extract(key, AEAmount.ONE, Actionable.MODULATE, SOURCE));
        assertEquals(AEAmount.of(7L), fixture.inventory.amount(key));
    }

    @Test
    void foreignThreadIsRejectedBeforeObservationOrMutation() throws InterruptedException {
        Fixture fixture = fixture(1);
        KeyId key = fixture.ids.get(0);
        fixture.inventory.insert(key, AEAmount.of(11L), Actionable.MODULATE, SOURCE);
        StorageSnapshot before = fixture.inventory.captureSnapshot();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread foreign = new Thread(() -> {
            try {
                fixture.inventory.insert(key, AEAmount.of(100L), Actionable.MODULATE, SOURCE);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "exact-broker-foreign-thread");
        foreign.start();
        foreign.join();

        assertInstanceOf(IllegalStateException.class, failure.get());
        assertSnapshotEquals(before, fixture.inventory.captureSnapshot());
    }

    @Test
    void quantityBoundIsPreflightedForRequestsAndResultingTotals() {
        Fixture fixture = fixture(1);
        KeyId key = fixture.ids.get(0);
        AEAmount maximum = amountAtMaximumBitLength();
        AEAmount overMaximum = AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS));

        assertThrows(IllegalArgumentException.class,
                () -> fixture.inventory.insert(key, overMaximum, Actionable.MODULATE, SOURCE));
        assertEquals(AEAmount.ZERO, fixture.inventory.amount(key));
        assertEquals(StorageRevision.ZERO, fixture.inventory.captureSnapshot().revision());

        fixture.inventory.insert(key, maximum, Actionable.MODULATE, SOURCE);
        StorageSnapshot beforeResultOverflow = fixture.inventory.captureSnapshot();
        assertThrows(IllegalArgumentException.class,
                () -> fixture.inventory.insert(key, maximum, Actionable.MODULATE, SOURCE));
        assertSnapshotEquals(beforeResultOverflow, fixture.inventory.captureSnapshot());
    }

    @Test
    void globalRevisionOverflowIsPreflightedWithoutAmountOrKeyRevisionMutation() {
        Fixture fixture = fixture(1);
        KeyId key = fixture.ids.get(0);
        fixture.inventory.insert(key, AEAmount.of(3L), Actionable.MODULATE, SOURCE);
        setField(fixture.inventory, "revision", new StorageRevision(Long.MAX_VALUE));
        StorageSnapshot before = fixture.inventory.captureSnapshot();

        assertThrows(ArithmeticException.class,
                () -> fixture.inventory.insert(key, AEAmount.ONE, Actionable.MODULATE, SOURCE));

        assertSnapshotEquals(before, fixture.inventory.captureSnapshot());
    }

    @Test
    void keyRevisionOverflowIsPreflightedWithoutAmountOrGlobalRevisionMutation() {
        Fixture fixture = fixture(1);
        KeyId key = fixture.ids.get(0);
        fixture.inventory.insert(key, AEAmount.of(3L), Actionable.MODULATE, SOURCE);
        setField(fixture.inventory, "keyRevisions", new long[] { Long.MAX_VALUE });
        StorageSnapshot before = fixture.inventory.captureSnapshot();

        assertThrows(ArithmeticException.class,
                () -> fixture.inventory.extract(key, AEAmount.ONE, Actionable.MODULATE, SOURCE));

        assertSnapshotEquals(before, fixture.inventory.captureSnapshot());
    }

    @Test
    void invalidArgumentsKeysAndRegistryBoundsAreRejectedWithoutMutation() {
        assertThrows(NullPointerException.class, () -> new ExactBrokerInventory(null));
        Fixture fixture = fixture(1);
        KeyId key = fixture.ids.get(0);
        KeyId outside = new KeyId(1);
        StorageSnapshot before = fixture.inventory.captureSnapshot();

        assertThrows(NullPointerException.class, () -> fixture.inventory.amount(null));
        assertThrows(NullPointerException.class,
                () -> fixture.inventory.insert(key, null, Actionable.MODULATE, SOURCE));
        assertThrows(NullPointerException.class,
                () -> fixture.inventory.insert(key, AEAmount.ONE, null, SOURCE));
        assertThrows(NullPointerException.class,
                () -> fixture.inventory.insert(key, AEAmount.ONE, Actionable.MODULATE, null));
        assertThrows(NullPointerException.class, () -> fixture.inventory.enumerate(null));
        assertThrows(IndexOutOfBoundsException.class, () -> fixture.inventory.amount(outside));
        assertThrows(IndexOutOfBoundsException.class,
                () -> fixture.inventory.extract(outside, AEAmount.ONE, Actionable.MODULATE, SOURCE));
        assertSnapshotEquals(before, fixture.inventory.captureSnapshot());

        KeyRegistry oversizedRegistry = mock(KeyRegistry.class);
        when(oversizedRegistry.size()).thenReturn(PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS + 1);
        ExactBrokerInventory oversized = new ExactBrokerInventory(oversizedRegistry);
        assertThrows(IllegalStateException.class, oversized::captureSnapshot);
    }

    private static AEAmount amountAtMaximumBitLength() {
        return AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS - 1));
    }

    private static void assertSnapshotEquals(StorageSnapshot expected, StorageSnapshot actual) {
        assertEquals(expected.keyRegistryGeneration(), actual.keyRegistryGeneration());
        assertEquals(expected.revision(), actual.revision());
        assertEquals(expected.keyCount(), actual.keyCount());
        assertEquals(expected.nonZeroKeys(), actual.nonZeroKeys());
        for (int index = 0; index < expected.keyCount(); index++) {
            KeyId key = new KeyId(index);
            assertEquals(expected.amount(key), actual.amount(key), "amount " + index);
            assertEquals(expected.keyRevision(key), actual.keyRevision(key), "key revision " + index);
        }
    }

    private static void setField(ExactBrokerInventory inventory, String fieldName, Object value) {
        try {
            Field field = ExactBrokerInventory.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(inventory, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("test requires existing field " + fieldName, exception);
        }
    }

    private static Fixture fixture(int keyCount) {
        KeyRegistry registry = new KeyRegistry(17L);
        List<KeyId> ids = new ArrayList<>(keyCount);
        for (int index = 0; index < keyCount; index++) {
            ids.add(registry.intern(mock(AEKey.class)));
        }
        return new Fixture(new ExactBrokerInventory(registry), ids);
    }

    private record Fixture(ExactBrokerInventory inventory, List<KeyId> ids) {
    }
}

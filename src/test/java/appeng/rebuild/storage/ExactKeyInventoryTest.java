package appeng.rebuild.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.quantity.AEAmount;

/** Exact-quantity and simulate/modulate contract tests for the in-memory inventory. */
class ExactKeyInventoryTest {

    private static final IActionSource SOURCE = IActionSource.empty();

    @Test
    void storesAllRequiredExactValueBoundaries() {
        Fixture fixture = fixture(8);
        BigInteger longMaxMinusOne = BigInteger.valueOf(Long.MAX_VALUE).subtract(BigInteger.ONE);
        BigInteger longMaxPlusOne = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        AEAmount[] values = {
                AEAmount.ZERO,
                AEAmount.ONE,
                AEAmount.of(longMaxMinusOne),
                AEAmount.of(Long.MAX_VALUE),
                AEAmount.of(longMaxPlusOne),
                AEAmount.of(BigInteger.ONE.shiftLeft(64)),
                AEAmount.of(BigInteger.ONE.shiftLeft(128)),
                AEAmount.of(BigInteger.TEN.pow(1000))
        };

        for (int index = 0; index < values.length; index++) {
            assertEquals(
                    values[index],
                    fixture.inventory.insert(
                            fixture.ids.get(index), values[index], Actionable.MODULATE, SOURCE),
                    "insert result at " + index);
        }

        for (int index = 0; index < values.length; index++) {
            assertEquals(values[index], fixture.inventory.amount(fixture.ids.get(index)));
        }
    }

    @Test
    void simulateInsertAndExtractDoNotMutateInventory() {
        Fixture fixture = fixture(1);
        KeyId key = fixture.ids.get(0);
        AEAmount initial = AEAmount.of(BigInteger.ONE.shiftLeft(128));
        AEAmount insertRequest = AEAmount.of(BigInteger.TEN.pow(1000));
        AEAmount extractRequest = AEAmount.of(BigInteger.ONE.shiftLeft(64));

        fixture.inventory.insert(key, initial, Actionable.MODULATE, SOURCE);
        assertEquals(
                insertRequest,
                fixture.inventory.insert(key, insertRequest, Actionable.SIMULATE, SOURCE));
        assertEquals(
                extractRequest,
                fixture.inventory.extract(key, extractRequest, Actionable.SIMULATE, SOURCE));
        assertEquals(initial, fixture.inventory.amount(key));
    }

    @Test
    void modulateInsertAcceptsFullRequestAndExtractIsPartialWhenNeeded() {
        Fixture fixture = fixture(1);
        KeyId key = fixture.ids.get(0);

        assertEquals(
                AEAmount.of(10L),
                fixture.inventory.insert(key, AEAmount.of(10L), Actionable.MODULATE, SOURCE));
        assertEquals(
                AEAmount.of(3L),
                fixture.inventory.extract(key, AEAmount.of(3L), Actionable.MODULATE, SOURCE));
        assertEquals(AEAmount.of(7L), fixture.inventory.amount(key));

        assertEquals(
                AEAmount.of(7L),
                fixture.inventory.extract(key, AEAmount.of(100L), Actionable.MODULATE, SOURCE));
        assertEquals(AEAmount.ZERO, fixture.inventory.amount(key));
    }

    @Test
    void zeroOperationsAreNoOps() {
        Fixture fixture = fixture(1);
        KeyId key = fixture.ids.get(0);
        Map<KeyId, AEAmount> before = enumerate(fixture.inventory);

        assertEquals(
                AEAmount.ZERO,
                fixture.inventory.insert(key, AEAmount.ZERO, Actionable.MODULATE, SOURCE));
        assertEquals(
                AEAmount.ZERO,
                fixture.inventory.extract(key, AEAmount.ZERO, Actionable.MODULATE, SOURCE));
        assertEquals(AEAmount.ZERO, fixture.inventory.amount(key));
        assertEquals(before, enumerate(fixture.inventory));
    }

    @Test
    void invalidAndOutOfRangeKeyIdsAreRejected() {
        Fixture fixture = fixture(1);
        KeyId outOfRange = new KeyId(fixture.registry.size());

        assertThrows(IndexOutOfBoundsException.class, () -> fixture.inventory.amount(outOfRange));
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> fixture.inventory.insert(outOfRange, AEAmount.ONE, Actionable.MODULATE, SOURCE));
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> fixture.inventory.extract(outOfRange, AEAmount.ONE, Actionable.MODULATE, SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new KeyId(-1));
    }

    @Test
    void enumerationReportsOnlyNonZeroEntriesAndReadsDoNotMutate() {
        Fixture fixture = fixture(4);
        fixture.inventory.insert(fixture.ids.get(0), AEAmount.ZERO, Actionable.MODULATE, SOURCE);
        fixture.inventory.insert(fixture.ids.get(1), AEAmount.of(7L), Actionable.MODULATE, SOURCE);
        fixture.inventory.insert(
                fixture.ids.get(2), AEAmount.of(BigInteger.ONE.shiftLeft(128)), Actionable.MODULATE, SOURCE);

        Map<KeyId, AEAmount> expected = new HashMap<>();
        expected.put(fixture.ids.get(1), AEAmount.of(7L));
        expected.put(fixture.ids.get(2), AEAmount.of(BigInteger.ONE.shiftLeft(128)));

        assertEquals(expected, enumerate(fixture.inventory));
        assertEquals(AEAmount.ZERO, fixture.inventory.amount(fixture.ids.get(3)));
        assertEquals(expected, enumerate(fixture.inventory));
        assertEquals(expected, enumerate(fixture.inventory));
    }

    @Test
    void randomizedConservationMatchesBigIntegerReference() {
        Fixture fixture = fixture(5);
        Random random = new Random(0xE2BEE11DL);
        BigInteger[] expected = new BigInteger[fixture.ids.size()];
        java.util.Arrays.fill(expected, BigInteger.ZERO);

        for (int iteration = 0; iteration < 512; iteration++) {
            int index = random.nextInt(fixture.ids.size());
            KeyId key = fixture.ids.get(index);
            AEAmount request = randomAmount(random);
            if (random.nextBoolean()) {
                assertEquals(
                        request,
                        fixture.inventory.insert(key, request, Actionable.SIMULATE, SOURCE),
                        "simulated insert at " + iteration);
                assertEquals(expected[index], fixture.inventory.amount(key).toBigInteger());
                fixture.inventory.insert(key, request, Actionable.MODULATE, SOURCE);
                expected[index] = expected[index].add(request.toBigInteger());
            } else {
                BigInteger expectedExtracted = expected[index].min(request.toBigInteger());
                assertEquals(
                        expectedExtracted,
                        fixture.inventory
                                .extract(key, request, Actionable.SIMULATE, SOURCE)
                                .toBigInteger(),
                        "simulated extract at " + iteration);
                assertEquals(expected[index], fixture.inventory.amount(key).toBigInteger());
                assertEquals(
                        expectedExtracted,
                        fixture.inventory
                                .extract(key, request, Actionable.MODULATE, SOURCE)
                                .toBigInteger(),
                        "modulated extract at " + iteration);
                expected[index] = expected[index].subtract(expectedExtracted);
            }

            for (int slot = 0; slot < expected.length; slot++) {
                assertEquals(
                        expected[slot],
                        fixture.inventory.amount(fixture.ids.get(slot)).toBigInteger(),
                        "slot " + slot + " at iteration " + iteration);
            }
            assertEquals(expectedEntries(fixture, expected), enumerate(fixture.inventory));
        }
    }

    private static Map<KeyId, AEAmount> enumerate(ExactStorage storage) {
        Map<KeyId, AEAmount> entries = new HashMap<>();
        storage.enumerate(entries::put);
        return entries;
    }

    private static Map<KeyId, AEAmount> expectedEntries(Fixture fixture, BigInteger[] expected) {
        Map<KeyId, AEAmount> entries = new HashMap<>();
        for (int index = 0; index < expected.length; index++) {
            if (expected[index].signum() != 0) {
                entries.put(fixture.ids.get(index), AEAmount.of(expected[index]));
            }
        }
        return entries;
    }

    private static AEAmount randomAmount(Random random) {
        int bitLength = switch (random.nextInt(8)) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 63;
            case 3 -> 64;
            case 4 -> 128;
            case 5 -> 1000;
            default -> random.nextInt(1001);
        };
        return bitLength == 0 ? AEAmount.ZERO : AEAmount.of(new BigInteger(bitLength, random));
    }

    private static Fixture fixture(int keyCount) {
        KeyRegistry registry = new KeyRegistry(1L);
        List<AEKey> keys = List.of(
                key(), key(), key(), key(), key(), key(), key(), key());
        List<KeyId> ids = new ArrayList<>(keyCount);
        for (int index = 0; index < keyCount; index++) {
            ids.add(registry.intern(keys.get(index)));
        }
        return new Fixture(registry, new ExactKeyInventory(registry), ids);
    }

    private static AEKey key() {
        return mock(AEKey.class);
    }

    private record Fixture(KeyRegistry registry, ExactKeyInventory inventory, List<KeyId> ids) {
    }
}

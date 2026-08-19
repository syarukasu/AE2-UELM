package appeng.me.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import net.minecraft.network.chat.Component;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

/** Contract tests for effective mount lifecycle notifications in {@link NetworkStorage}. */
class NetworkStorageMountObserverTest {

    private static final IActionSource SOURCE = IActionSource.empty();

    @Test
    void mountAndUnmountNotifyImmediately() {
        RecordingObserver observer = new RecordingObserver();
        NetworkStorage network = new NetworkStorage(observer);
        TestStorage storage = new TestStorage("mounted");

        network.mount(7, storage);
        network.unmount(storage);

        assertEquals(List.of(
                new Event(EventType.MOUNTED, 7, storage),
                new Event(EventType.UNMOUNTED, 7, storage)), observer.events);
    }

    @Test
    void unmountingANonexistentStorageProducesNoEvent() {
        RecordingObserver observer = new RecordingObserver();
        NetworkStorage network = new NetworkStorage(observer);

        network.unmount(new TestStorage("not-mounted"));

        assertTrue(observer.events.isEmpty());
    }

    @Test
    void equalsEqualIdentityDistinctRemovalReportsTheActualMountedEntry() {
        RecordingObserver observer = new RecordingObserver();
        NetworkStorage network = new NetworkStorage(observer);
        EqualStorage mounted = new EqualStorage("same-entry");
        EqualStorage equalButDistinctRemovalArgument = new EqualStorage("same-entry");

        network.mount(4, mounted);
        observer.events.clear();
        network.unmount(equalButDistinctRemovalArgument);

        assertEquals(1, observer.events.size());
        assertSame(mounted, observer.events.get(0).storage);
        assertEquals(EventType.UNMOUNTED, observer.events.get(0).type);
    }

    @Test
    void sameDelegateMountedAtMultiplePrioritiesProducesSeparateUnmountEvents() {
        RecordingObserver observer = new RecordingObserver();
        NetworkStorage network = new NetworkStorage(observer);
        TestStorage storage = new TestStorage("same-delegate");

        network.mount(9, storage);
        network.mount(2, storage);
        observer.events.clear();
        network.unmount(storage);

        assertEquals(List.of(
                new Event(EventType.UNMOUNTED, 9, storage),
                new Event(EventType.UNMOUNTED, 2, storage)), observer.events);
    }

    @Test
    void queuedMountCallbackWaitsUntilTheOperationFlushes() {
        RecordingObserver observer = new RecordingObserver();
        NetworkStorage network = new NetworkStorage(observer);
        TestStorage queued = new TestStorage("queued-mount");
        TestStorage source = new TestStorage("source");
        source.onInsert = ignored -> {
            network.mount(3, queued);
            assertTrue(observer.events.isEmpty());
        };
        source.insertResult = 1L;
        network.mount(1, source);
        observer.events.clear();

        assertEquals(1L, network.insert(key(), 1L, Actionable.MODULATE, SOURCE));

        assertEquals(List.of(new Event(EventType.MOUNTED, 3, queued)), observer.events);
    }

    @Test
    void queuedUnmountCallbackWaitsUntilTheOperationFlushes() {
        RecordingObserver observer = new RecordingObserver();
        NetworkStorage network = new NetworkStorage(observer);
        TestStorage target = new TestStorage("queued-unmount-target");
        TestStorage source = new TestStorage("source");
        source.onInsert = ignored -> {
            network.unmount(target);
            assertTrue(observer.events.isEmpty());
        };
        source.insertResult = 1L;
        network.mount(0, target);
        network.mount(1, source);
        observer.events.clear();

        assertEquals(1L, network.insert(key(), 1L, Actionable.MODULATE, SOURCE));

        assertEquals(List.of(new Event(EventType.UNMOUNTED, 0, target)), observer.events);
    }

    @Test
    void operationThrowingBeforeFlushDoesNotNotifyQueuedLifecycleChanges() {
        RecordingObserver observer = new RecordingObserver();
        NetworkStorage network = new NetworkStorage(observer);
        TestStorage queued = new TestStorage("queued-after-failure");
        TestStorage source = new TestStorage("throwing-source");
        source.onInsert = ignored -> {
            network.mount(5, queued);
            assertTrue(observer.events.isEmpty());
            throw new IllegalStateException("delegate failure");
        };
        network.mount(1, source);
        observer.events.clear();

        assertThrows(
                IllegalStateException.class,
                () -> network.insert(key(), 1L, Actionable.MODULATE, SOURCE));

        assertTrue(observer.events.isEmpty());
    }

    @Test
    void insertionUsesHigherPriorityBeforeLowerPriorityAndExtractionUsesLowerFirst() {
        NetworkStorage network = new NetworkStorage();
        TestStorage high = new TestStorage("high");
        TestStorage low = new TestStorage("low");
        high.insertResult = 5L;
        low.insertResult = 5L;
        low.extractResult = 5L;
        network.mount(10, high);
        network.mount(1, low);

        assertEquals(5L, network.insert(key(), 5L, Actionable.MODULATE, SOURCE));
        assertEquals(List.of("high"), high.insertCalls);
        assertTrue(low.insertCalls.isEmpty());

        assertEquals(5L, network.extract(key(), 5L, Actionable.MODULATE, SOURCE));
        assertTrue(high.extractCalls.isEmpty());
        assertEquals(List.of("low"), low.extractCalls);
    }

    private static AEKey key() {
        AEKey key = mock(AEKey.class);
        when(key.getPrimaryKey()).thenReturn(new Object());
        return key;
    }

    private enum EventType {
        MOUNTED,
        UNMOUNTED
    }

    private record Event(EventType type, int priority, MEStorage storage) {
    }

    private static final class RecordingObserver implements NetworkStorageMountObserver {
        private final List<Event> events = new ArrayList<>();

        @Override
        public void mounted(int priority, MEStorage storage) {
            events.add(new Event(EventType.MOUNTED, priority, storage));
        }

        @Override
        public void unmounted(int priority, MEStorage storage) {
            events.add(new Event(EventType.UNMOUNTED, priority, storage));
        }
    }

    private static class TestStorage implements MEStorage {
        private final String name;
        private final List<String> insertCalls = new ArrayList<>();
        private final List<String> extractCalls = new ArrayList<>();
        private Consumer<TestStorage> onInsert;
        private long insertResult;
        private long extractResult;

        private TestStorage(String name) {
            this.name = name;
        }

        @Override
        public long insert(AEKey what, long amount, Actionable type, IActionSource src) {
            insertCalls.add(name);
            if (onInsert != null) {
                onInsert.accept(this);
            }
            return Math.min(amount, insertResult);
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            extractCalls.add(name);
            return Math.min(amount, extractResult);
        }

        @Override
        public Component getDescription() {
            return null;
        }
    }

    private static final class EqualStorage extends TestStorage {
        private final String equalityKey;

        private EqualStorage(String equalityKey) {
            super(equalityKey);
            this.equalityKey = equalityKey;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualStorage storage && equalityKey.equals(storage.equalityKey);
        }

        @Override
        public int hashCode() {
            return equalityKey.hashCode();
        }
    }
}

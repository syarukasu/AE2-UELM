package appeng.rebuild.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import appeng.hooks.ticking.TickHandler;
import appeng.me.Grid;

/** Bounded, server-thread-free lifecycle tests for recipe reload delivery. */
class RecipeReloadCoordinatorTest {
    @Test
    void startResetsRevisionAndChecksServerGeneration() {
        MinecraftServer server = server();
        MinecraftServer nextServer = server();
        RecipeReloadCoordinator coordinator = new RecipeReloadCoordinator();
        withCurrentServer(server, () -> {
            coordinator.onServerAboutToStart(server);
            coordinator.onReloadApply();
            assertEquals(new RecipeRevision(1), coordinator.currentState().currentRevision());

            coordinator.onServerStopping(server);
            coordinator.onServerStopped(server);
            coordinator.onServerAboutToStart(nextServer);
        });

        RecipeReloadState state = coordinator.currentState();
        assertEquals(2, state.serverGeneration());
        assertEquals(RecipeRevision.ZERO, state.currentRevision());
        assertTrue(state.active());
        assertTrue(state.pendingRevision().isEmpty());
    }

    @Test
    void addReloadRegistersOneListenerAndPrepareHasNoSideEffects() throws Exception {
        RecipeReloadCoordinator coordinator = new RecipeReloadCoordinator();
        AddReloadListenerEvent event = new AddReloadListenerEvent(mock(ReloadableServerResources.class), null);

        coordinator.onAddReloadListener(event);

        assertEquals(1, event.getListeners().size());
        PreparableReloadListener listener = event.getListeners().get(0);
        Field wrapped = listener.getClass().getDeclaredField("wrapped");
        wrapped.setAccessible(true);
        Object delegate = wrapped.get(listener);
        Method prepare = delegate.getClass().getDeclaredMethod("prepare", ResourceManager.class,
                ProfilerFiller.class);
        prepare.setAccessible(true);
        prepare.invoke(delegate, mock(ResourceManager.class), mock(ProfilerFiller.class));
        assertEquals(RecipeRevision.ZERO, coordinator.currentState().currentRevision());
        assertTrue(coordinator.currentState().pendingRevision().isEmpty());

        MinecraftServer server = server();
        Method apply = delegate.getClass().getDeclaredMethod("apply", Void.class, ResourceManager.class,
                ProfilerFiller.class);
        apply.setAccessible(true);
        withCurrentServer(server, () -> {
            coordinator.onServerAboutToStart(server);
            try {
                apply.invoke(delegate, null, mock(ResourceManager.class), mock(ProfilerFiller.class));
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        });
        assertEquals(new RecipeRevision(1), coordinator.currentState().currentRevision());
        assertEquals(new RecipeRevision(1), coordinator.currentState().pendingRevision().orElseThrow());
    }

    @Test
    void applyOnlyCoalescesPendingRevisionAndStartPublishesNewestOnce() {
        MinecraftServer server = server();
        Grid grid = mock(Grid.class);
        RecipeReloadCoordinator coordinator = new RecipeReloadCoordinator();
        TickHandler tickHandler = mock(TickHandler.class);
        doAnswer(invocation -> invocation.getArgument(0)).when(grid).postEvent(any(GridRecipeRevisionChanged.class));
        when(tickHandler.getGridList()).thenReturn(List.of(grid));

        withCurrentServer(server, () -> {
            coordinator.onServerAboutToStart(server);
            try (MockedStatic<TickHandler> ignored = mockTickHandler(tickHandler)) {
                coordinator.onReloadApply();
                coordinator.onReloadApply();
                assertEquals(new RecipeRevision(2), coordinator.currentState().currentRevision());
                assertEquals(new RecipeRevision(2), coordinator.currentState().pendingRevision().orElseThrow());
                verifyNoInteractions(tickHandler, grid);

                coordinator.onServerTick(new ServerTickEvent(Phase.START, () -> true, server));
                coordinator.onServerTick(new ServerTickEvent(Phase.START, () -> true, server));
            }
        });

        var event = capturedEvent(grid);
        assertEquals(new RecipeRevision(2), event.revision());
        assertEquals(1, event.serverGeneration());
        verify(grid).postEvent(any(GridRecipeRevisionChanged.class));
    }

    @Test
    void endPhaseDoesNotConsumePendingRevision() {
        MinecraftServer server = server();
        Grid grid = mock(Grid.class);
        RecipeReloadCoordinator coordinator = new RecipeReloadCoordinator();
        TickHandler tickHandler = mock(TickHandler.class);
        when(tickHandler.getGridList()).thenReturn(List.of(grid));
        doAnswer(invocation -> invocation.getArgument(0)).when(grid).postEvent(any(GridRecipeRevisionChanged.class));

        withCurrentServer(server, () -> {
            coordinator.onServerAboutToStart(server);
            try (MockedStatic<TickHandler> ignored = mockTickHandler(tickHandler)) {
                coordinator.onReloadApply();
                coordinator.onServerTick(new ServerTickEvent(Phase.END, () -> true, server));
                assertTrue(coordinator.currentState().pendingRevision().isPresent());
                verifyNoInteractions(tickHandler, grid);
            }
        });
    }

    @Test
    void gridIterableIsSnapshottedBeforeHandlersRun() {
        MinecraftServer server = server();
        Grid first = mock(Grid.class);
        Grid second = mock(Grid.class);
        Grid added = mock(Grid.class);
        List<Grid> grids = new ArrayList<>(List.of(first, second));
        TickHandler tickHandler = mock(TickHandler.class);
        when(tickHandler.getGridList()).thenReturn(grids);
        doAnswer(invocation -> {
            grids.add(added);
            return invocation.getArgument(0);
        }).when(first).postEvent(any(GridRecipeRevisionChanged.class));
        doAnswer(invocation -> invocation.getArgument(0)).when(second).postEvent(any(GridRecipeRevisionChanged.class));

        RecipeReloadCoordinator coordinator = new RecipeReloadCoordinator();
        withCurrentServer(server, () -> {
            coordinator.onServerAboutToStart(server);
            try (MockedStatic<TickHandler> ignored = mockTickHandler(tickHandler)) {
                coordinator.onReloadApply();
                coordinator.onServerTick(new ServerTickEvent(Phase.START, () -> true, server));
            }
        });

        verify(first).postEvent(any(GridRecipeRevisionChanged.class));
        verify(second).postEvent(any(GridRecipeRevisionChanged.class));
        verify(added, never()).postEvent(any(GridRecipeRevisionChanged.class));
    }

    @Test
    void gridFailureIsRecordedObservedAndNotRetried() {
        MinecraftServer server = server();
        Grid failed = mock(Grid.class);
        Grid healthy = mock(Grid.class);
        RuntimeException failure = new IllegalStateException("delivery");
        doThrow(failure).when(failed).postEvent(any(GridRecipeRevisionChanged.class));
        doAnswer(invocation -> invocation.getArgument(0)).when(healthy).postEvent(any(GridRecipeRevisionChanged.class));
        TickHandler tickHandler = mock(TickHandler.class);
        when(tickHandler.getGridList()).thenReturn(List.of(failed, healthy));
        AtomicReference<GridRecipeRevisionChanged> observedEvent = new AtomicReference<>();
        AtomicReference<RuntimeException> observedFailure = new AtomicReference<>();
        RecipeReloadCoordinator coordinator = new RecipeReloadCoordinator((grid, event, exception) -> {
            assertSame(failed, grid);
            observedEvent.set(event);
            observedFailure.set(exception);
        });

        withCurrentServer(server, () -> {
            coordinator.onServerAboutToStart(server);
            try (MockedStatic<TickHandler> ignored = mockTickHandler(tickHandler)) {
                coordinator.onReloadApply();
                coordinator.onServerTick(new ServerTickEvent(Phase.START, () -> true, server));
                coordinator.onServerTick(new ServerTickEvent(Phase.START, () -> true, server));
            }
        });

        assertSame(failure, observedFailure.get());
        assertEquals(new RecipeRevision(1), observedEvent.get().revision());
        assertEquals(1, coordinator.currentState().failedGridCount());
        verify(failed).postEvent(any(GridRecipeRevisionChanged.class));
        verify(healthy).postEvent(any(GridRecipeRevisionChanged.class));
    }

    @Test
    void observerRuntimeFailureIsContainedAndHealthyGridStillReceivesEvent() {
        MinecraftServer server = server();
        Grid failed = mock(Grid.class);
        Grid healthy = mock(Grid.class);
        doThrow(new IllegalStateException("delivery")).when(failed).postEvent(any(GridRecipeRevisionChanged.class));
        doAnswer(invocation -> invocation.getArgument(0)).when(healthy).postEvent(any(GridRecipeRevisionChanged.class));
        RecipeReloadCoordinator coordinator = new RecipeReloadCoordinator((grid, event, failure) -> {
            throw new IllegalArgumentException("observer");
        }, () -> List.of(failed, healthy));

        withCurrentServer(server, () -> {
            coordinator.onServerAboutToStart(server);
            coordinator.onReloadApply();
            coordinator.onServerTick(new ServerTickEvent(Phase.START, () -> true, server));
            coordinator.onServerTick(new ServerTickEvent(Phase.START, () -> true, server));
        });

        verify(failed).postEvent(any(GridRecipeRevisionChanged.class));
        verify(healthy).postEvent(any(GridRecipeRevisionChanged.class));
        assertEquals(1, coordinator.currentState().failedGridCount());
    }

    @Test
    void fatalGridErrorPropagates() {
        MinecraftServer server = server();
        Grid fatal = mock(Grid.class);
        doThrow(new AssertionError("fatal")).when(fatal).postEvent(any(GridRecipeRevisionChanged.class));
        RecipeReloadCoordinator coordinator = new RecipeReloadCoordinator(GridRecipeRevisionFailureObserver.NO_OP,
                () -> List.of(fatal));

        withCurrentServer(server, () -> {
            coordinator.onServerAboutToStart(server);
            coordinator.onReloadApply();
            assertThrows(AssertionError.class,
                    () -> coordinator.onServerTick(new ServerTickEvent(Phase.START, () -> true, server)));
        });
        assertTrue(coordinator.currentState().failClosed());
        assertFalse(coordinator.currentState().active());
    }

    @Test
    void gridSnapshotRuntimeFailureFailsClosedAndPropagates() {
        MinecraftServer server = server();
        RuntimeException failure = new IllegalStateException("snapshot");
        RecipeReloadCoordinator coordinator = new RecipeReloadCoordinator(GridRecipeRevisionFailureObserver.NO_OP,
                () -> {
                    throw failure;
                });

        withCurrentServer(server, () -> {
            coordinator.onServerAboutToStart(server);
            coordinator.onReloadApply();
            assertThrows(IllegalStateException.class,
                    () -> coordinator.onServerTick(new ServerTickEvent(Phase.START, () -> true, server)));
        });
        assertTrue(coordinator.currentState().failClosed());
        assertFalse(coordinator.currentState().active());
    }

    @Test
    void stoppingAndStoppedDiscardPendingAndResetState() {
        MinecraftServer server = server();
        RecipeReloadCoordinator coordinator = new RecipeReloadCoordinator();
        withCurrentServer(server, () -> {
            coordinator.onServerAboutToStart(server);
            coordinator.onReloadApply();
            coordinator.onServerStopping(server);
            assertFalse(coordinator.currentState().active());
            assertTrue(coordinator.currentState().pendingRevision().isEmpty());
            coordinator.onServerStopped(server);
        });

        RecipeReloadState state = coordinator.currentState();
        assertFalse(state.active());
        assertEquals(RecipeRevision.ZERO, state.currentRevision());
        assertTrue(state.pendingRevision().isEmpty());
        assertEquals(1, state.serverGeneration());
    }

    @Test
    void currentServerMismatchFailsClosedBeforeDelivery() {
        MinecraftServer activeServer = server();
        MinecraftServer otherServer = server();
        RecipeReloadCoordinator coordinator = new RecipeReloadCoordinator();
        withCurrentServer(activeServer, () -> {
            coordinator.onServerAboutToStart(activeServer);
            coordinator.onReloadApply();
        });
        try (MockedStatic<ServerLifecycleHooks> ignored = mockStatic(ServerLifecycleHooks.class)) {
            ignored.when(ServerLifecycleHooks::getCurrentServer).thenReturn(otherServer);
            coordinator.onReloadApply();
        }

        assertFalse(coordinator.currentState().active());
        assertTrue(coordinator.currentState().failClosed());
        assertTrue(coordinator.currentState().pendingRevision().isEmpty());
    }

    @Test
    void tickCurrentServerMismatchFailsClosedWithoutDelivery() {
        MinecraftServer activeServer = server();
        MinecraftServer eventServer = server();
        Grid grid = mock(Grid.class);
        RecipeReloadCoordinator coordinator = new RecipeReloadCoordinator(GridRecipeRevisionFailureObserver.NO_OP,
                () -> List.of(grid));
        withCurrentServer(activeServer, () -> {
            coordinator.onServerAboutToStart(activeServer);
            coordinator.onReloadApply();
            coordinator.onServerTick(new ServerTickEvent(Phase.START, () -> true, eventServer));
        });

        assertTrue(coordinator.currentState().failClosed());
        assertFalse(coordinator.currentState().active());
        verify(grid, never()).postEvent(any(GridRecipeRevisionChanged.class));
    }

    @Test
    void duplicateAboutToStartFailsClosed() {
        MinecraftServer server = server();
        RecipeReloadCoordinator coordinator = new RecipeReloadCoordinator();
        coordinator.onServerAboutToStart(server);
        coordinator.onServerAboutToStart(server);

        assertTrue(coordinator.currentState().failClosed());
        assertFalse(coordinator.currentState().active());
        assertTrue(coordinator.currentState().pendingRevision().isEmpty());
    }

    @Test
    void stoppingAndStoppedOffThreadFailClosed() {
        MinecraftServer server = server();
        RecipeReloadCoordinator coordinator = new RecipeReloadCoordinator();
        coordinator.onServerAboutToStart(server);
        when(server.isSameThread()).thenReturn(false);

        coordinator.onServerStopping(server);
        assertTrue(coordinator.currentState().failClosed());

        coordinator.onServerStopped(server);
        assertTrue(coordinator.currentState().failClosed());
    }

    @Test
    void generationAndRevisionOverflowFailClosedWithoutWrapping() throws Exception {
        MinecraftServer server = server();
        RecipeReloadCoordinator coordinator = new RecipeReloadCoordinator();
        setLong(coordinator, "serverGeneration", Long.MAX_VALUE);
        coordinator.onServerAboutToStart(server);
        assertTrue(coordinator.currentState().failClosed());
        assertEquals(Long.MAX_VALUE, coordinator.currentState().serverGeneration());

        RecipeReloadCoordinator revisionCoordinator = new RecipeReloadCoordinator();
        withCurrentServer(server, () -> revisionCoordinator.onServerAboutToStart(server));
        setField(revisionCoordinator, "currentRevision", new RecipeRevision(Long.MAX_VALUE));
        withCurrentServer(server, () -> revisionCoordinator.onReloadApply());
        assertTrue(revisionCoordinator.currentState().failClosed());
        assertEquals(new RecipeRevision(Long.MAX_VALUE), revisionCoordinator.currentState().currentRevision());
        assertTrue(revisionCoordinator.currentState().pendingRevision().isEmpty());
    }

    @Test
    void currentStateSupportsNewGridQueryAfterReload() {
        MinecraftServer server = server();
        Grid grid = mock(Grid.class);
        TickHandler tickHandler = mock(TickHandler.class);
        when(tickHandler.getGridList()).thenReturn(List.of(grid));
        doAnswer(invocation -> invocation.getArgument(0)).when(grid).postEvent(any(GridRecipeRevisionChanged.class));
        RecipeReloadCoordinator coordinator = new RecipeReloadCoordinator();

        withCurrentServer(server, () -> {
            coordinator.onServerAboutToStart(server);
            try (MockedStatic<TickHandler> ignored = mockTickHandler(tickHandler)) {
                coordinator.onReloadApply();
                coordinator.onServerTick(new ServerTickEvent(Phase.START, () -> true, server));
            }
        });

        assertEquals(new RecipeRevision(1), coordinator.currentState().currentRevision());
        assertTrue(coordinator.currentState().pendingRevision().isEmpty());
        assertEquals(1, capturedEvent(grid).serverGeneration());
    }

    private static MinecraftServer server() {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        return server;
    }

    private static void withCurrentServer(MinecraftServer server, Runnable action) {
        try (MockedStatic<ServerLifecycleHooks> ignored = mockStatic(ServerLifecycleHooks.class)) {
            ignored.when(ServerLifecycleHooks::getCurrentServer).thenReturn(server);
            action.run();
        }
    }

    private static MockedStatic<TickHandler> mockTickHandler(TickHandler handler) {
        MockedStatic<TickHandler> mocked = mockStatic(TickHandler.class);
        mocked.when(TickHandler::instance).thenReturn(handler);
        return mocked;
    }

    private static GridRecipeRevisionChanged capturedEvent(Grid grid) {
        var details = org.mockito.Mockito.mockingDetails(grid);
        return details.getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("postEvent"))
                .map(invocation -> (GridRecipeRevisionChanged) invocation.getArgument(0))
                .findFirst().orElseThrow();
    }

    private static void setLong(RecipeReloadCoordinator coordinator, String fieldName, long value) throws Exception {
        setField(coordinator, fieldName, value);
    }

    private static void setField(RecipeReloadCoordinator coordinator, String fieldName, Object value) throws Exception {
        Field field = RecipeReloadCoordinator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(coordinator, value);
    }
}

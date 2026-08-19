package appeng.rebuild.pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import appeng.hooks.ticking.TickHandler;
import appeng.me.Grid;

/**
 * Process-scoped, server-thread-owned coordinator for coalescing recipe reload signals into grid events.
 *
 * <p>
 * It intentionally does not inspect recipes, levels, providers, or normalized caches. Those consumers react only to
 * {@link GridRecipeRevisionChanged} and query {@link #currentState()} for new-grid state.
 */
public final class RecipeReloadCoordinator {
    private static final RecipeReloadCoordinator INSTANCE = new RecipeReloadCoordinator();

    private MinecraftServer activeServer;
    private long serverGeneration;
    private RecipeRevision currentRevision = RecipeRevision.ZERO;
    private RecipeRevision pendingRevision;
    private boolean active;
    private boolean failClosed;
    private int failedGridCount;
    private String lastFailedGridIdentity;
    private GridRecipeRevisionFailureObserver failureObserver;
    private final Supplier<? extends Iterable<Grid>> gridSupplier;

    public RecipeReloadCoordinator() {
        this(GridRecipeRevisionFailureObserver.NO_OP);
    }

    public RecipeReloadCoordinator(GridRecipeRevisionFailureObserver failureObserver) {
        this(failureObserver, () -> TickHandler.instance().getGridList());
    }

    /** Package-private bounded grid-enumeration seam for coordinator tests. */
    RecipeReloadCoordinator(GridRecipeRevisionFailureObserver failureObserver,
            Supplier<? extends Iterable<Grid>> gridSupplier) {
        this.failureObserver = Objects.requireNonNull(failureObserver, "failureObserver");
        this.gridSupplier = Objects.requireNonNull(gridSupplier, "gridSupplier");
    }

    public static RecipeReloadCoordinator instance() {
        return INSTANCE;
    }

    /** Installs the synchronous C2B grid-shadow failure hand-off. Server thread only while active. */
    public void setFailureObserver(GridRecipeRevisionFailureObserver failureObserver) {
        assertActiveServerThreadWhenActive();
        this.failureObserver = Objects.requireNonNull(failureObserver, "failureObserver");
    }

    /** Returns current state; server-thread-only while a server is active. */
    public RecipeReloadState currentState() {
        assertActiveServerThreadWhenActive();
        return new RecipeReloadState(serverGeneration, currentRevision, Optional.ofNullable(pendingRevision), active,
                failClosed, failedGridCount, Optional.ofNullable(lastFailedGridIdentity));
    }

    /** Registers one apply-only listener for the current server resource reload. */
    public void onAddReloadListener(AddReloadListenerEvent event) {
        Objects.requireNonNull(event, "event").addListener(new ReloadListener(this));
    }

    /** Activates this coordinator for the supplied server with revision zero and a checked next generation. */
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        onServerAboutToStart(Objects.requireNonNull(event, "event").getServer());
    }

    void onServerAboutToStart(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            failClosed();
            return;
        }
        if (activeServer != null || active) {
            failClosed();
            return;
        }
        try {
            serverGeneration = Math.incrementExact(serverGeneration);
        } catch (ArithmeticException exception) {
            failClosed();
            return;
        }
        activeServer = server;
        currentRevision = RecipeRevision.ZERO;
        pendingRevision = null;
        active = true;
        failClosed = false;
        clearDeliveryDiagnostics();
    }

    /** Deactivates and discards pending work for the active server. */
    public void onServerStopping(ServerStoppingEvent event) {
        onServerStopping(Objects.requireNonNull(event, "event").getServer());
    }

    void onServerStopping(MinecraftServer server) {
        if (!matchesActiveServer(server)) {
            return;
        }
        if (!server.isSameThread()) {
            failClosed();
            return;
        }
        active = false;
        pendingRevision = null;
    }

    /** Clears the stopped server's active state. The monotonic process generation remains for the next server. */
    public void onServerStopped(ServerStoppedEvent event) {
        onServerStopped(Objects.requireNonNull(event, "event").getServer());
    }

    void onServerStopped(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            failClosed();
            return;
        }
        if (activeServer != null && activeServer != server) {
            failClosed();
            return;
        }
        activeServer = null;
        active = false;
        pendingRevision = null;
        currentRevision = RecipeRevision.ZERO;
        failClosed = false;
        clearDeliveryDiagnostics();
    }

    /** Consumes at most one coalesced pending revision at the server START phase. */
    public void onServerTick(ServerTickEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.phase == Phase.START) {
            consumePending(event.getServer());
        }
    }

    /**
     * Signals a successful resource reload apply. This method performs no level, recipe-manager, grid, or provider
     * access; the next server START tick performs the bounded grid delivery.
     */
    void onReloadApply() {
        if (!active || failClosed) {
            return;
        }
        if (activeServer == null || ServerLifecycleHooks.getCurrentServer() != activeServer
                || !activeServer.isSameThread()) {
            failClosed();
            return;
        }
        try {
            currentRevision = currentRevision.next();
        } catch (ArithmeticException exception) {
            failClosed();
            return;
        }
        pendingRevision = currentRevision;
    }

    private void consumePending(MinecraftServer server) {
        if (!active || failClosed) {
            return;
        }
        if (!matchesActiveServer(server) || ServerLifecycleHooks.getCurrentServer() != activeServer
                || !server.isSameThread()) {
            failClosed();
            return;
        }
        RecipeRevision revision = pendingRevision;
        if (revision == null) {
            return;
        }

        List<Grid> gridSnapshot;
        try {
            gridSnapshot = new ArrayList<>();
            for (Grid grid : gridSupplier.get()) {
                gridSnapshot.add(grid);
            }
        } catch (RuntimeException exception) {
            failClosed();
            throw exception;
        } catch (Error fatal) {
            failClosed();
            throw fatal;
        }
        pendingRevision = null;
        clearDeliveryDiagnostics();
        for (Grid grid : gridSnapshot) {
            GridRecipeRevisionChanged event = new GridRecipeRevisionChanged(revision, serverGeneration);
            try {
                grid.postEvent(event);
            } catch (RuntimeException exception) {
                recordGridFailure(grid);
                notifyFailureObserver(grid, event, exception);
            } catch (Error fatal) {
                failClosed();
                throw fatal;
            }
        }
    }

    private boolean matchesActiveServer(MinecraftServer server) {
        if (activeServer == server) {
            return true;
        }
        if (activeServer != null) {
            failClosed();
        }
        return false;
    }

    private void notifyFailureObserver(Grid grid, GridRecipeRevisionChanged event, RuntimeException failure) {
        try {
            failureObserver.onDeliveryFailure(grid, event, failure);
        } catch (RuntimeException ignored) {
            // Delivery remains failed and is never retried; the bounded diagnostic is already recorded.
        } catch (Error fatal) {
            failClosed();
            throw fatal;
        }
    }

    private void recordGridFailure(Grid grid) {
        try {
            failedGridCount = Math.incrementExact(failedGridCount);
        } catch (ArithmeticException exception) {
            failClosed();
            return;
        }
        lastFailedGridIdentity = "grid@" + Integer.toUnsignedString(System.identityHashCode(grid), 16);
    }

    private void clearDeliveryDiagnostics() {
        failedGridCount = 0;
        lastFailedGridIdentity = null;
    }

    private void assertActiveServerThreadWhenActive() {
        if (active && (activeServer == null || !activeServer.isSameThread())) {
            throw new IllegalStateException("RecipeReloadCoordinator must run on the active server thread");
        }
    }

    private void failClosed() {
        active = false;
        pendingRevision = null;
        failClosed = true;
    }

    private static final class ReloadListener extends SimplePreparableReloadListener<Void> {
        private final RecipeReloadCoordinator coordinator;

        private ReloadListener(RecipeReloadCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        @Override
        protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
            return null;
        }

        @Override
        protected void apply(Void ignored, ResourceManager resourceManager, ProfilerFiller profiler) {
            coordinator.onReloadApply();
        }
    }
}

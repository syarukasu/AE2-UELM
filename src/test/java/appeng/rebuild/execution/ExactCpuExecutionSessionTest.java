package appeng.rebuild.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import appeng.api.networking.security.IActionSource;
import appeng.rebuild.storage.BrokerExactStorage;

/** Boundary tests for the CPU-owned exact session observation surface. */
class ExactCpuExecutionSessionTest {

    @Test
    void newSessionIsIdleAndMissingWorkOrderIsTypedWithoutDurablePublication() {
        AtomicReference<ExactRecoveryCheckpoint> published = new AtomicReference<>();
        ExactCpuExecutionSession session = ExactCpuExecutionSession.create(
                mock(BrokerExactStorage.class), () -> null, () -> true, IActionSource.empty(), published::set,
                () -> {
                    throw new AssertionError("idle session must not clear a checkpoint");
                });

        ExactCpuExecutionSession.ExactCpuSessionSnapshot snapshot = session.snapshot();
        assertEquals(ExactCpuLedgerState.IDLE, snapshot.ledger().state());
        assertEquals(ExactTransferBrokerState.IDLE, snapshot.broker().state());
        assertFalse(snapshot.workOrder().isPresent());
        assertNull(published.get());

        ExactCpuExecutionSession.Unavailable unavailable = assertInstanceOf(ExactCpuExecutionSession.Unavailable.class,
                session.issueNext(Long.MAX_VALUE));
        assertEquals(ExactCpuExecutionSession.UnavailableReason.NO_WORK_ORDER, unavailable.reason());
        assertEquals(snapshot, unavailable.snapshot());
        assertNull(published.get());
    }
}

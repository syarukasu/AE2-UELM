package appeng.rebuild.execution;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable capability for one prepared plan on one CPU ledger.
 *
 * <p>
 * The monotonically increasing revision prevents an old handle from becoming valid again after the CPU returns to
 * {@link ExactCpuLedgerState#IDLE}. It is lifecycle metadata, never a quantity.
 */
public record CpuPlanHandle(UUID ledgerIdentity, long revision) {
    public CpuPlanHandle {
        Objects.requireNonNull(ledgerIdentity, "ledgerIdentity");
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }
}

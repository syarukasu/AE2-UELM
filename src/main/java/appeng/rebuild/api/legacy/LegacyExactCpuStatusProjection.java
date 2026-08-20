package appeng.rebuild.api.legacy;

import java.util.Objects;
import java.util.Optional;

import appeng.rebuild.api.exact.ExactCpuStatus;
import appeng.rebuild.execution.ExactCpuLedgerState;
import appeng.rebuild.execution.ExactTransferBrokerState;
import appeng.rebuild.execution.ExactWorkOrderState;

/** Explicit long-only projection for existing CPU status menus and packets. */
public final class LegacyExactCpuStatusProjection {
    private LegacyExactCpuStatusProjection() {
    }

    /**
     * Projects status totals independently so an existing GUI can identify that at least one displayed number is
     * saturated without changing its current long-valued protocol.
     */
    public static ProjectedStatus project(ExactCpuStatus status) {
        Objects.requireNonNull(status, "status");
        return new ProjectedStatus(status.ledgerState(), status.brokerState(), status.workOrderState(),
                LegacyAmountProjection.project(status.reserved()), LegacyAmountProjection.project(status.escrowed()),
                LegacyAmountProjection.project(status.custody()));
    }

    public record ProjectedStatus(ExactCpuLedgerState ledgerState, ExactTransferBrokerState brokerState,
            Optional<ExactWorkOrderState> workOrderState, LegacyAmountProjection.ProjectedAmount reserved,
            LegacyAmountProjection.ProjectedAmount escrowed, LegacyAmountProjection.ProjectedAmount custody) {
        public ProjectedStatus {
            Objects.requireNonNull(ledgerState, "ledgerState");
            Objects.requireNonNull(brokerState, "brokerState");
            workOrderState = Objects.requireNonNull(workOrderState, "workOrderState");
            Objects.requireNonNull(reserved, "reserved");
            Objects.requireNonNull(escrowed, "escrowed");
            Objects.requireNonNull(custody, "custody");
        }

        /** True when any long-only consumer cannot display the exact total. */
        public boolean saturated() {
            return reserved.saturated() || escrowed.saturated() || custody.saturated();
        }
    }
}

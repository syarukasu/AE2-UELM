package appeng.rebuild.execution;

/** Lifecycle observation for one exact work order. */
public enum ExactWorkOrderState {
    READY,
    COMMAND_OUTSTANDING,
    IN_FLIGHT,
    /** A cancellation is waiting for its one accepted physical command to report matching completion evidence. */
    CANCEL_PENDING,
    /** All sealed normal and cycle work has completed and exact request-plus-surplus custody awaits settlement. */
    SETTLEMENT_PENDING,
    RELEASE_PENDING,
    COMPLETED,
    FAIL_CLOSED
}

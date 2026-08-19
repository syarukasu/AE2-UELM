package appeng.rebuild.execution;

/** Lifecycle observation for one exact work order. */
public enum ExactWorkOrderState {
    READY,
    COMMAND_OUTSTANDING,
    IN_FLIGHT,
    /** Reserved for the later bounded cancellation protocol. */
    CANCEL_PENDING,
    /** All sealed normal work has completed and exact request-plus-surplus custody awaits settlement. */
    SETTLEMENT_PENDING,
    RELEASE_PENDING,
    COMPLETED,
    FAIL_CLOSED
}

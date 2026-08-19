package appeng.rebuild.execution;

/** Lifecycle observation for one exact work order. */
public enum ExactWorkOrderState {
    READY,
    COMMAND_OUTSTANDING,
    IN_FLIGHT,
    CANCEL_PENDING,
    SETTLEMENT_PENDING,
    RELEASE_PENDING,
    COMPLETED,
    FAIL_CLOSED
}

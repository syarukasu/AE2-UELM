package appeng.rebuild.execution;

/** Observable state of one server-thread-owned exact transfer broker. */
public enum ExactTransferBrokerState {
    IDLE,
    PREFLIGHT,
    EXTRACTING,
    RESERVED,
    ROLLBACK_PENDING,
    RELEASE_PENDING,
    FAIL_CLOSED
}

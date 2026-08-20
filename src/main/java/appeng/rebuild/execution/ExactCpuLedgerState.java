package appeng.rebuild.execution;

/** Lifecycle state of one exact crafting CPU reservation ledger. */
public enum ExactCpuLedgerState {
    /** No plan or resource obligation is owned by this CPU. */
    IDLE,
    /** A validated plan has a handle but no storage resource has been reserved. */
    PREPARED,
    /** The receipt's exact storage debits are owned by this ledger. */
    RESERVED,
    /** A release obligation owns the debits until a broker acknowledges their release. */
    RELEASE_PENDING,
    /** A work-order lease owns the plan and debits until it acknowledges completion or abort. */
    HANDED_OFF,
    /** A lifecycle identity overflow made this ledger permanently unavailable. */
    FAIL_CLOSED
}

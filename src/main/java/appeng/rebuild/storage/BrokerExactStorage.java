package appeng.rebuild.storage;

/**
 * Revisioned exact endpoint accepted by the Phase 7 transfer broker.
 *
 * <p>
 * Implementations are confined to one server thread and must reject reentrant access. A simulation is pure. A modulated
 * insert or extract either returns its exact, observed mutation after publishing the corresponding storage and key
 * revisions, or throws without changing amounts or revisions. This stronger contract is deliberately separate from
 * {@link ExactStorage}: a broker cannot prove a reservation against an endpoint that permits ambiguous exceptions or
 * unrevisioned state changes.
 */
public interface BrokerExactStorage extends ExactStorage {
    /** Captures one immutable, revision-consistent exact state on the owning server thread. */
    StorageSnapshot captureSnapshot();
}

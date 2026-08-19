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
 *
 * <p>
 * For every bounded request, the returned moved amount is non-null, remains within the exact quantity bound, and is in
 * the closed interval from zero through the requested amount. Returning more than requested, returning an out-of-bound
 * value, or returning {@code null} is a protocol violation. A {@link RuntimeException} guarantees that neither amounts
 * nor revisions changed.
 */
public interface BrokerExactStorage extends ExactStorage {
    /** Captures one immutable, revision-consistent exact state on the owning server thread. */
    StorageSnapshot captureSnapshot();
}

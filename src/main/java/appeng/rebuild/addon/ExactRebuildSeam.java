package appeng.rebuild.addon;

/**
 * Closed set of core handoff seams available to a verified optional-addon adapter.
 *
 * <p>
 * These names describe existing exact snapshots and ledgers; they do not grant an addon access to a mutable core
 * implementation or create a compatibility fallback.
 */
public enum ExactRebuildSeam {
    NORMALIZED_PATTERN_SNAPSHOT,
    EXACT_STORAGE_SNAPSHOT,
    EXACT_CPU_LEDGER
}

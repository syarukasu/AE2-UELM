package appeng.rebuild.execution;

import appeng.rebuild.pattern.NormalizedPatternSnapshot;

/** Supplies the current immutable normalized-pattern view inside a broker preflight window. */
@FunctionalInterface
public interface CurrentPatternSnapshotSource {
    NormalizedPatternSnapshot captureCurrent();
}

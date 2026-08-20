package appeng.rebuild.addon;

/**
 * An addon-owned surface that could participate in the exact rebuild only through the named, immutable AE2 seam.
 */
public enum AddonSurface {
    PATTERN_PROVIDER(ExactRebuildSeam.NORMALIZED_PATTERN_SNAPSHOT),
    CRAFTING_MACHINE(ExactRebuildSeam.SEALED_EXACT_COMMAND),
    STORAGE(ExactRebuildSeam.EXACT_STORAGE_SNAPSHOT),
    CPU(ExactRebuildSeam.EXACT_CPU_LEDGER);

    private final ExactRebuildSeam seam;

    AddonSurface(ExactRebuildSeam seam) {
        this.seam = seam;
    }

    /** Returns the sole exact-rebuild seam this surface may use when a verified adapter exists. */
    public ExactRebuildSeam seam() {
        return seam;
    }
}

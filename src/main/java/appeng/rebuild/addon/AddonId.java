package appeng.rebuild.addon;

/** Identifies an optional AE2 addon without loading any of its classes. */
public enum AddonId {
    EXTENDED_AE("extendedae"),
    ADVANCED_AE("advanced_ae");

    private final String modId;

    AddonId(String modId) {
        this.modId = modId;
    }

    /** Returns the Forge mod id expected from this addon. */
    public String modId() {
        return modId;
    }
}

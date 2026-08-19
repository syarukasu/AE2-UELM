package appeng.rebuild.addon;

import java.util.Objects;

/** Runtime metadata reported for an optional addon without invoking addon code. */
public record AddonRuntimeMetadata(String modVersion, String apiVersion) {
    /** Metadata used when neither an approved addon API nor runtime metadata has been verified. */
    public static final AddonRuntimeMetadata UNKNOWN = new AddonRuntimeMetadata("UNKNOWN", "UNKNOWN");

    public AddonRuntimeMetadata {
        Objects.requireNonNull(modVersion, "modVersion");
        Objects.requireNonNull(apiVersion, "apiVersion");
    }
}

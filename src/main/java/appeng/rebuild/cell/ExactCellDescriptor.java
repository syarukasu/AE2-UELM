package appeng.rebuild.cell;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

/** Immutable physical cell type and capacity metadata retained for safe UUID recovery. */
public record ExactCellDescriptor(ResourceLocation itemId, ResourceLocation keyTypeId, int totalBytes, int bytesPerType,
        int totalTypes) {
    public ExactCellDescriptor {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(keyTypeId, "keyTypeId");
        if (totalBytes <= 0 || bytesPerType < 0 || totalTypes <= 0 || totalTypes > 65_536) {
            throw new IllegalArgumentException("invalid exact cell descriptor");
        }
    }
}

package appeng.rebuild.cell;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

import appeng.rebuild.quantity.AEAmount;

/** Immutable physical cell type and capacity metadata retained for safe UUID recovery. */
public record ExactCellDescriptor(ResourceLocation itemId, ResourceLocation keyTypeId, int totalBytes, int bytesPerType,
        int totalTypes, AEAmount exactTotalCapacity) {
    public ExactCellDescriptor {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(keyTypeId, "keyTypeId");
        Objects.requireNonNull(exactTotalCapacity, "exactTotalCapacity");
        if (totalBytes <= 0 || bytesPerType < 0 || totalTypes <= 0 || totalTypes > 65_536) {
            throw new IllegalArgumentException("invalid exact cell descriptor");
        }
        if (exactTotalCapacity.equals(AEAmount.ZERO)) {
            throw new IllegalArgumentException("exact cell capacity must be positive");
        }
    }
}

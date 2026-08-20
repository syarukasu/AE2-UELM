package appeng.rebuild.cell;

import java.math.BigInteger;
import java.util.Objects;

import net.minecraft.world.item.ItemStack;

import appeng.api.storage.cells.IBasicCellItem;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/** Supplies an exact physical capacity for UUID-backed basic storage cells. */
public interface ExactCellCapacityProvider {
    AEAmount getExactTotalCapacity(ItemStack cellItem);

    static AEAmount capacityOf(IBasicCellItem cell, ItemStack stack) {
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(stack, "stack");
        AEAmount capacity;
        if (cell instanceof ExactCellCapacityProvider provider) {
            capacity = Objects.requireNonNull(provider.getExactTotalCapacity(stack), "exact cell capacity");
        } else {
            capacity = AEAmount.of(BigInteger.valueOf(cell.getBytes(stack))
                    .multiply(BigInteger.valueOf(cell.getKeyType().getAmountPerByte())));
        }
        if (capacity.equals(AEAmount.ZERO)
                || capacity.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
            throw new IllegalArgumentException("exact cell capacity is outside supported bounds");
        }
        return capacity;
    }
}

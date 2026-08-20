package appeng.items.storage;

import java.math.BigInteger;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

import appeng.api.stacks.AEKeyType;
import appeng.rebuild.cell.ExactCellCapacityProvider;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/** A finite UUID-backed storage cell whose physical capacity is represented exactly. */
public final class BigIntStorageCell extends BasicStorageCell implements ExactCellCapacityProvider {
    private static final int LEGACY_KILOBYTES = 262_143;
    private static final AEAmount EXACT_CAPACITY = AEAmount.of(
            BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS - 1).subtract(BigInteger.ONE));

    public BigIntStorageCell(Item.Properties properties, ItemLike coreItem, ItemLike housingItem, int totalTypes,
            AEKeyType keyType) {
        super(properties, coreItem, housingItem, 4.0, LEGACY_KILOBYTES, 2048, totalTypes, keyType);
    }

    @Override
    public AEAmount getExactTotalCapacity(ItemStack cellItem) {
        return EXACT_CAPACITY;
    }
}

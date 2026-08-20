package appeng.items.storage;

import java.util.List;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

public record StorageCellTooltipComponent(List<ItemStack> upgrades,
        List<ExactTooltipStack> content,
        boolean hasMoreContent,
        boolean showAmounts) implements TooltipComponent {
}

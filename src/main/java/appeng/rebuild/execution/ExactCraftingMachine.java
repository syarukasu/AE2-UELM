package appeng.rebuild.execution;

import net.minecraft.core.Direction;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;

/** Native crafting-machine extension that persists and returns one exact work-command identity. */
public interface ExactCraftingMachine {
    boolean pushExactPattern(ExactWorkCommand command, IPatternDetails patternDetails, KeyCounter[] inputs,
            Direction ejectionDirection);
}

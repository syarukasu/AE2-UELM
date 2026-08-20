package appeng.rebuild.execution;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;

/** Optional native provider path which carries the durable work-command identity into a crafting machine. */
public interface ExactCraftingProvider {
    boolean pushExactPattern(ExactWorkCommand command, IPatternDetails patternDetails, KeyCounter[] inputs);
}

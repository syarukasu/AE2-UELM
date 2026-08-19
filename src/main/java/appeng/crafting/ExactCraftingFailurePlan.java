package appeng.crafting;

import java.util.Map;
import java.util.Objects;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

/** Legacy UI projection of a typed exact-planner failure. It can never be submitted for execution. */
public final class ExactCraftingFailurePlan implements ICraftingPlan {
    private final GenericStack request;
    private final KeyCounter missing = new KeyCounter();

    public ExactCraftingFailurePlan(AEKey output, long amount) {
        this.request = new GenericStack(Objects.requireNonNull(output, "output"), amount);
        this.missing.add(output, amount);
    }

    @Override
    public GenericStack finalOutput() {
        return request;
    }

    @Override
    public long bytes() {
        return 0;
    }

    @Override
    public boolean simulation() {
        return true;
    }

    @Override
    public boolean multiplePaths() {
        return false;
    }

    @Override
    public KeyCounter usedItems() {
        return new KeyCounter();
    }

    @Override
    public KeyCounter emittedItems() {
        return new KeyCounter();
    }

    @Override
    public KeyCounter missingItems() {
        return missing;
    }

    @Override
    public Map<IPatternDetails, Long> patternTimes() {
        return Map.of();
    }
}

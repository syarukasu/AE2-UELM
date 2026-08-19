package appeng.crafting;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.rebuild.api.legacy.LegacyAmountProjection;
import appeng.rebuild.execution.ExactCraftingPlan;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.pattern.PatternId;

/**
 * Compatibility view of an authoritative exact crafting plan.
 *
 * <p>
 * The wrapped plan remains the only execution authority. Values exposed through the legacy AE2 API are explicit
 * saturated projections and must never be used to reserve or execute the job.
 * </p>
 */
public final class ExactCraftingPlanAdapter implements ICraftingPlan {
    private final ExactCraftingPlan exactPlan;
    private final KeyRegistry keys;
    private final Map<IPatternDetails, Long> patternTimes;

    public ExactCraftingPlanAdapter(ExactCraftingPlan exactPlan, KeyRegistry keys,
            Function<PatternId, IPatternDetails> patternResolver) {
        this.exactPlan = Objects.requireNonNull(exactPlan, "exactPlan");
        this.keys = Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(patternResolver, "patternResolver");
        Map<IPatternDetails, Long> projected = new LinkedHashMap<>();
        exactPlan.patternExecutions().forEach((id, amount) -> {
            IPatternDetails details = Objects.requireNonNull(patternResolver.apply(id),
                    "Exact plan pattern is no longer physically bound: " + id);
            projected.merge(details, LegacyAmountProjection.saturatingLong(amount),
                    ExactCraftingPlanAdapter::saturatingAdd);
        });
        this.patternTimes = Collections.unmodifiableMap(projected);
    }

    public ExactCraftingPlan exactPlan() {
        return exactPlan;
    }

    @Override
    public GenericStack finalOutput() {
        return new GenericStack(keys.resolve(exactPlan.request().output()),
                LegacyAmountProjection.saturatingLong(exactPlan.request().amount()));
    }

    @Override
    public long bytes() {
        long total = 0;
        for (var amount : exactPlan.patternExecutions().values()) {
            total = saturatingAdd(total, LegacyAmountProjection.saturatingLong(amount));
        }
        return total;
    }

    @Override
    public boolean simulation() {
        return false;
    }

    @Override
    public boolean multiplePaths() {
        return false;
    }

    @Override
    public KeyCounter usedItems() {
        return project(exactPlan.initialStorageDebits());
    }

    @Override
    public KeyCounter emittedItems() {
        return new KeyCounter();
    }

    @Override
    public KeyCounter missingItems() {
        return new KeyCounter();
    }

    @Override
    public Map<IPatternDetails, Long> patternTimes() {
        return patternTimes;
    }

    private KeyCounter project(Map<appeng.rebuild.key.KeyId, appeng.rebuild.quantity.AEAmount> amounts) {
        KeyCounter result = new KeyCounter();
        amounts.forEach((key, amount) -> result.add(keys.resolve(key), LegacyAmountProjection.saturatingLong(amount)));
        return result;
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}

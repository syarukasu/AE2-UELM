package appeng.rebuild.addon;

import java.util.Objects;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import appeng.rebuild.pattern.RebuildPatternClassifier;

/**
 * Exact compatibility adapter for addon machines implemented through AE2's standard crafting contracts.
 *
 * <p>
 * The caller must first reserve the sealed command and claim the grid-wide untagged-result route. This adapter then
 * performs exactly one physical provider call. Returned items are not trusted as completion: the CPU's exact route
 * accepts only the sealed output and remainder maps, and retains any discrepancy as recovery authority.
 */
public final class StandardAddonMachineExactAdapter {
    private StandardAddonMachineExactAdapter() {
    }

    /** Dispatches one bounded command to a provider whose pattern has the validated standard AE2 shape. */
    public static boolean pushPattern(ICraftingProvider provider, IPatternDetails details, KeyCounter[] inputs) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(details, "details");
        Objects.requireNonNull(inputs, "inputs");
        if (!RebuildPatternClassifier.isEligible(details)) {
            throw new IllegalArgumentException("Unsupported addon pattern contract");
        }
        return provider.pushPattern(details, inputs);
    }
}

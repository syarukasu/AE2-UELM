package appeng.rebuild.pattern;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.crafting.pattern.AESmithingTablePattern;
import appeng.crafting.pattern.AEStonecuttingPattern;

/**
 * Classifies AE2 pattern implementations that expose the complete standard {@link IPatternDetails} contract.
 *
 * <p>
 * Subclasses are deliberately accepted. Addons such as ExtendedAE and AdvancedAE extend or forward AE2's standard
 * pattern types while using addon-owned providers and machines. Their callbacks are still fully validated by
 * {@link LegacyPatternNormalizer}; accepting the subtype does not grant it authority over exact quantities.
 */
public final class RebuildPatternClassifier {
    private RebuildPatternClassifier() {
    }

    /** Returns the normalized pattern kind, or {@code null} for a custom contract the rebuild cannot prove. */
    public static @Nullable PatternKind classify(IPatternDetails details) {
        if (details instanceof AECraftingPattern) {
            return PatternKind.CRAFTING;
        }
        if (details instanceof AEProcessingPattern) {
            return PatternKind.PROCESSING;
        }
        if (details instanceof AESmithingTablePattern) {
            return PatternKind.SMITHING;
        }
        if (details instanceof AEStonecuttingPattern) {
            return PatternKind.STONECUTTING;
        }
        return null;
    }

    /** Returns whether the pattern can be normalized through the bounded standard AE2 contract. */
    public static boolean isEligible(IPatternDetails details) {
        return classify(details) != null;
    }
}

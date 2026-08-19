package appeng.rebuild.pattern;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;

/**
 * Immutable, provider-owned legacy snapshot prepared for a single recipe revision.
 *
 * <p>
 * Its collections are immutable and intentionally contain no inventory stacks. Rebuild eligibility is ordered with
 * {@link #legacyPatterns()} so custom decoder results can remain in legacy routing without becoming Rebuild inputs.
 */
public final class PreparedPatternProviderRecipeReload implements PatternProviderRecipeReloadResult {
    private final Object ownerToken;
    private final RecipeRevision revision;
    private final long patternStateVersion;
    private final List<IPatternDetails> legacyPatterns;
    private final List<Boolean> rebuildEligible;
    private final Set<AEKey> patternInputs;

    public PreparedPatternProviderRecipeReload(Object ownerToken, RecipeRevision revision, long patternStateVersion,
            List<? extends IPatternDetails> legacyPatterns, List<Boolean> rebuildEligible,
            Set<? extends AEKey> patternInputs) {
        this.ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        this.revision = Objects.requireNonNull(revision, "revision");
        if (patternStateVersion < 0) {
            throw new IllegalArgumentException("Pattern state version must be non-negative: " + patternStateVersion);
        }
        this.patternStateVersion = patternStateVersion;
        Objects.requireNonNull(legacyPatterns, "legacyPatterns");
        Objects.requireNonNull(rebuildEligible, "rebuildEligible");
        Objects.requireNonNull(patternInputs, "patternInputs");
        if (legacyPatterns.size() > PatternLimits.MAX_PATTERN_PROVIDER_PATTERNS) {
            throw new IllegalArgumentException("Too many legacy provider patterns");
        }
        if (patternInputs.size() > PatternLimits.MAX_PATTERN_PROVIDER_TOTAL_INPUT_CANDIDATES) {
            throw new IllegalArgumentException("Too many provider input keys");
        }
        this.legacyPatterns = copyPatterns(legacyPatterns);
        this.rebuildEligible = copyEligibility(rebuildEligible);
        this.patternInputs = copyInputs(patternInputs);
        if (this.legacyPatterns.size() != this.rebuildEligible.size()) {
            throw new IllegalArgumentException("Every legacy pattern requires one Rebuild eligibility flag");
        }
    }

    public RecipeRevision revision() {
        return revision;
    }

    /** Provider-local state version captured before staging began. */
    public long patternStateVersion() {
        return patternStateVersion;
    }

    public List<IPatternDetails> legacyPatterns() {
        return legacyPatterns;
    }

    public Set<AEKey> patternInputs() {
        return patternInputs;
    }

    public boolean rebuildEligible(int patternIndex) {
        return rebuildEligible.get(patternIndex);
    }

    public List<Boolean> rebuildEligibility() {
        return rebuildEligible;
    }

    /** Internal ownership proof used by the provider's atomic commit gate. */
    public boolean belongsTo(Object token) {
        return ownerToken == token;
    }

    private static List<IPatternDetails> copyPatterns(List<? extends IPatternDetails> patterns) {
        for (IPatternDetails pattern : patterns) {
            Objects.requireNonNull(pattern, "legacyPatterns cannot contain null");
        }
        return List.copyOf(patterns);
    }

    private static List<Boolean> copyEligibility(List<Boolean> eligibility) {
        for (Boolean eligible : eligibility) {
            Objects.requireNonNull(eligible, "rebuildEligible cannot contain null");
        }
        return List.copyOf(eligibility);
    }

    private static Set<AEKey> copyInputs(Set<? extends AEKey> inputs) {
        for (AEKey input : inputs) {
            Objects.requireNonNull(input, "patternInputs cannot contain null");
        }
        return Set.copyOf(inputs);
    }
}

package appeng.rebuild.pattern;

/** Non-negative monotonic revision supplied by the recipe source. */
public record RecipeRevision(long value) {
    public static final RecipeRevision ZERO = new RecipeRevision(0L);

    public RecipeRevision {
        if (value < 0) {
            throw new IllegalArgumentException("Recipe revision must be non-negative: " + value);
        }
    }

    /** Returns the next revision or fails closed rather than wrapping. */
    public RecipeRevision next() {
        return new RecipeRevision(Math.incrementExact(value));
    }
}

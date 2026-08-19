package appeng.rebuild.planner;

/** Finite bounds for immutable planning inputs and dependency metadata. */
public final class PlannerLimits {
    /** Maximum registry-scoped keys copied into one exact storage snapshot. */
    public static final int MAX_STORAGE_SNAPSHOT_KEYS = 1_048_576;
    /** Maximum explicit dependency-expansion frames in one exact craft draft. */
    public static final int MAX_CRAFT_STACK_DEPTH = 4_096;
    /** Maximum producer or candidate branch decisions in one exact craft draft. */
    public static final int MAX_CRAFT_SEARCH_DECISIONS = 65_536;
    /** Maximum reversible map mutations in one exact craft draft. */
    public static final int MAX_CRAFT_MUTATIONS = 1_048_576;
    /** Maximum bit length of any exact quantity handled by the bounded planner. */
    public static final int MAX_CRAFT_QUANTITY_BITS = 65_536;
    /** Maximum binary chunks examined by one transactional producer or candidate fallback schedule. */
    public static final int MAX_CRAFT_CHUNK_BITS = 4_096;
    /** Maximum full producer passes attempted for one demand before later phases may revisit it. */
    public static final int MAX_CRAFT_PRODUCER_PASSES = 2;
    /** Maximum members in one compact productive-cycle step. */
    public static final int MAX_PRODUCTIVE_CYCLE_MEMBERS = 128;
    /** Maximum bounded productive-cycle discovery or link attempts. */
    public static final int MAX_PRODUCTIVE_CYCLE_ATTEMPTS = 4_096;

    private PlannerLimits() {
    }
}

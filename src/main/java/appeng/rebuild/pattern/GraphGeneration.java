package appeng.rebuild.pattern;

/** Non-negative generation of immutable compiled graph metadata. */
public record GraphGeneration(long value) {
    public GraphGeneration {
        if (value < 0) {
            throw new IllegalArgumentException("Graph generation must be non-negative: " + value);
        }
    }
}

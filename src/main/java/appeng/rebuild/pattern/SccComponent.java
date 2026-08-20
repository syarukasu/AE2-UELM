package appeng.rebuild.pattern;

import java.util.List;
import java.util.Objects;

/** Immutable deterministic membership and classification of one strongly connected component. */
public record SccComponent(List<PatternId> members, SccClassification classification) {
    public SccComponent {
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        if (members.isEmpty()) {
            throw new IllegalArgumentException("An SCC component must have at least one member");
        }
        Objects.requireNonNull(classification, "classification");
    }
}

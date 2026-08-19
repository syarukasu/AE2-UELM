package appeng.rebuild.planner;

import java.util.Objects;

/** Typed result of checking recorded planner reads against current immutable grid inputs. */
public sealed interface DependencyValidationResult
        permits DependencyValidationResult.Valid, DependencyValidationResult.Invalid {
    record Valid() implements DependencyValidationResult {
    }

    record Invalid(Reason reason) implements DependencyValidationResult {
        public Invalid {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum Reason {
        SERVER_GENERATION_MISMATCH,
        KEY_REGISTRY_GENERATION_MISMATCH,
        GRAPH_GENERATION_MISMATCH,
        RECIPE_REVISION_MISMATCH,
        STORAGE_REVISION_REGRESSION,
        STORAGE_KEY_ABSENT,
        STORAGE_KEY_REVISION_MISMATCH,
        PATTERN_ABSENT,
        PATTERN_REVISION_MISMATCH
    }
}

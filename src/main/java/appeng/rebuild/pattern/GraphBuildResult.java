package appeng.rebuild.pattern;

import java.util.Objects;

/** Typed, all-or-nothing compiled-graph construction result. */
public sealed interface GraphBuildResult permits GraphBuildResult.Success, GraphBuildResult.Failure {
    static Success success(CompiledPatternGraph graph) {
        return new Success(graph);
    }

    static Failure failure(FailureReason reason, String context) {
        return new Failure(reason, context);
    }

    boolean isSuccess();

    record Success(CompiledPatternGraph graph) implements GraphBuildResult {
        public Success {
            Objects.requireNonNull(graph, "graph");
        }

        @Override
        public boolean isSuccess() {
            return true;
        }
    }

    record Failure(FailureReason reason, String context) implements GraphBuildResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(context, "context");
        }

        @Override
        public boolean isSuccess() {
            return false;
        }
    }

    enum FailureReason {
        GRAPH_LIMIT,
        DUPLICATE_PATTERN_ID,
        MIXED_KEY_REGISTRY_GENERATION
    }
}

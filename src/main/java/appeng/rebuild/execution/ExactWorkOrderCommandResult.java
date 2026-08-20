package appeng.rebuild.execution;

import java.util.Objects;

/** Package-bound command-issue result. None of these values grants acknowledgement authority. */
public sealed interface ExactWorkOrderCommandResult permits ExactWorkOrderCommandResult.Issued,
        ExactWorkOrderCommandResult.CycleUnsupported, ExactWorkOrderCommandResult.Unavailable {
    record Issued(ExactWorkCommand command) implements ExactWorkOrderCommandResult {
        public Issued {
            Objects.requireNonNull(command, "command");
        }
    }

    /** Cycle execution remains deliberately unavailable until the separate B2 execution protocol is sealed. */
    record CycleUnsupported(ExactWorkOrderSnapshot snapshot) implements ExactWorkOrderCommandResult {
        public CycleUnsupported {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record Unavailable(Reason reason, ExactWorkOrderSnapshot snapshot) implements ExactWorkOrderCommandResult {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    enum Reason {
        WRONG_THREAD,
        REENTRANT,
        WRONG_STATE,
        INVALID_WINDOW,
        PHYSICAL_WINDOW_UNAVAILABLE,
        COMMAND_GENERATION_EXHAUSTED,
        INVARIANT_VIOLATION
    }
}

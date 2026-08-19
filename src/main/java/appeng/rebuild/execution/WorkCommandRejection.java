package appeng.rebuild.execution;

import java.util.Objects;

/** Same-package executor evidence that an unaccepted command was rejected without physical work. */
record WorkCommandRejection(ExactWorkCommand command) {
    WorkCommandRejection {
        Objects.requireNonNull(command, "command");
    }
}

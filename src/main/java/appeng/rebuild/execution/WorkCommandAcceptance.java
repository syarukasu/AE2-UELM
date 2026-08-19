package appeng.rebuild.execution;

import java.util.Objects;

/** Same-package executor evidence that it has accepted exactly one sealed command. */
record WorkCommandAcceptance(ExactWorkCommand command) {
    WorkCommandAcceptance {
        Objects.requireNonNull(command, "command");
    }
}

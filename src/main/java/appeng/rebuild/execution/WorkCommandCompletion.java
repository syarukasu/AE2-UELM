package appeng.rebuild.execution;

import java.util.Map;
import java.util.Objects;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Same-package executor evidence containing the actual goods returned by a sealed command. */
record WorkCommandCompletion(ExactWorkCommand command, Map<KeyId, AEAmount> actualOutputs,
        Map<KeyId, AEAmount> actualRemainders) {
    WorkCommandCompletion {
        Objects.requireNonNull(command, "command");
        actualOutputs = ExactWorkCommand.copyAmounts(actualOutputs, "actualOutputs");
        actualRemainders = ExactWorkCommand.copyAmounts(actualRemainders, "actualRemainders");
    }
}

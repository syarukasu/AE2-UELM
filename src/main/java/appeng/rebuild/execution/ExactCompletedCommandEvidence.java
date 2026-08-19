package appeng.rebuild.execution;

import java.util.Map;
import java.util.Objects;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Immutable recovery evidence for one physically completed command whose actual result exactly matched its seal. */
public record ExactCompletedCommandEvidence(ExactWorkCommand command, Map<KeyId, AEAmount> actualOutputs,
        Map<KeyId, AEAmount> actualRemainders) {
    public ExactCompletedCommandEvidence {
        Objects.requireNonNull(command, "command");
        actualOutputs = ExactWorkCommand.copyAmounts(actualOutputs, "actualOutputs");
        actualRemainders = ExactWorkCommand.copyAmounts(actualRemainders, "actualRemainders");
        if (!command.expectedOutputs().equals(actualOutputs)
                || !command.expectedRemainders().equals(actualRemainders)) {
            throw new IllegalArgumentException("Completed evidence must exactly match the sealed command result");
        }
    }
}

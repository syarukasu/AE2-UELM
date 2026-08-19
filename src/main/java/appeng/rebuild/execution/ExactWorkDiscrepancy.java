package appeng.rebuild.execution;

import java.util.Map;
import java.util.Objects;

import appeng.rebuild.key.KeyId;

/** Immutable evidence retained when a physical executor's completion differs from the sealed command. */
public record ExactWorkDiscrepancy(ExactWorkCommand command,
        Map<KeyId, appeng.rebuild.quantity.AEAmount> expectedOutputs,
        Map<KeyId, appeng.rebuild.quantity.AEAmount> actualOutputs,
        Map<KeyId, appeng.rebuild.quantity.AEAmount> expectedRemainders,
        Map<KeyId, appeng.rebuild.quantity.AEAmount> actualRemainders) {
    public ExactWorkDiscrepancy {
        Objects.requireNonNull(command, "command");
        expectedOutputs = ExactWorkCommand.copyPhysicalAmounts(expectedOutputs, "expectedOutputs");
        actualOutputs = ExactWorkCommand.copyAmounts(actualOutputs, "actualOutputs");
        expectedRemainders = ExactWorkCommand.copyPhysicalAmounts(expectedRemainders, "expectedRemainders");
        actualRemainders = ExactWorkCommand.copyAmounts(actualRemainders, "actualRemainders");
        if (!expectedOutputs.equals(command.expectedOutputs())
                || !expectedRemainders.equals(command.expectedRemainders())) {
            throw new IllegalArgumentException("Discrepancy expectations must equal the sealed command");
        }
        if (expectedOutputs.equals(actualOutputs) && expectedRemainders.equals(actualRemainders)) {
            throw new IllegalArgumentException("Discrepancy requires an actual result mismatch");
        }
    }
}

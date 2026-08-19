package appeng.rebuild.pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;

/** Pure compiler from normalized source patterns to registry-scoped KeyId patterns. */
public final class PatternCompiler {
    private final KeyRegistry keyRegistry;

    public PatternCompiler(KeyRegistry keyRegistry) {
        this.keyRegistry = Objects.requireNonNull(keyRegistry, "keyRegistry");
    }

    /**
     * Compiles without interning keys or mutating the source pattern. Unknown keys and rejected bounded shapes are
     * reported explicitly, without returning a partial compiled pattern.
     */
    public PatternCompileResult compile(PatternDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        PatternCompileResult.Failure shapeFailure = validateShape(definition);
        if (shapeFailure != null) {
            return shapeFailure;
        }

        List<CompiledInputSpec> inputs = new ArrayList<>(definition.inputs().size());
        for (int inputIndex = 0; inputIndex < definition.inputs().size(); inputIndex++) {
            InputSpec input = definition.inputs().get(inputIndex);
            List<CompiledCandidateSpec> candidates = new ArrayList<>(input.candidates().size());
            for (int candidateIndex = 0; candidateIndex < input.candidates().size(); candidateIndex++) {
                CandidateSpec candidate = input.candidates().get(candidateIndex);
                String path = "inputs[" + inputIndex + "].candidates[" + candidateIndex + ']';
                KeyId candidateKey = lookup(candidate.key());
                if (candidateKey == null) {
                    return PatternCompileResult.failure(PatternCompileResult.FailureReason.UNKNOWN_KEY, path + ".key",
                            candidate.key());
                }
                Optional<CompiledRemainderSpec> remainder = Optional.empty();
                if (candidate.remainder().isPresent()) {
                    RemainderSpec sourceRemainder = candidate.remainder().get();
                    KeyId remainderKey = lookup(sourceRemainder.key());
                    if (remainderKey == null) {
                        return PatternCompileResult.failure(PatternCompileResult.FailureReason.UNKNOWN_KEY,
                                path + ".remainder.key", sourceRemainder.key());
                    }
                    remainder = Optional.of(new CompiledRemainderSpec(remainderKey, sourceRemainder.amount()));
                }
                candidates.add(new CompiledCandidateSpec(candidateKey, candidate.amountPerExecution(), remainder));
            }
            inputs.add(new CompiledInputSpec(candidates, input.policy()));
        }

        List<CompiledOutputSpec> outputs = new ArrayList<>(definition.outputs().size());
        for (int outputIndex = 0; outputIndex < definition.outputs().size(); outputIndex++) {
            OutputSpec output = definition.outputs().get(outputIndex);
            String path = "outputs[" + outputIndex + "].key";
            KeyId outputKey = lookup(output.key());
            if (outputKey == null) {
                return PatternCompileResult.failure(PatternCompileResult.FailureReason.UNKNOWN_KEY, path, output.key());
            }
            outputs.add(new CompiledOutputSpec(outputKey, output.amountPerExecution(), output.primary()));
        }

        return PatternCompileResult.success(new CompiledPattern(definition.id(), definition.kind(), inputs, outputs,
                definition.machineIntent(), definition.revision(), keyRegistry.generation()));
    }

    @Nullable
    private PatternCompileResult.Failure validateShape(PatternDefinition definition) {
        if (definition.inputs().size() > PatternLimits.MAX_INPUT_GROUPS) {
            return boundedShapeFailure("inputs");
        }
        if (definition.outputs().isEmpty() || definition.outputs().size() > PatternLimits.MAX_OUTPUTS) {
            return boundedShapeFailure("outputs");
        }
        int totalCandidates = 0;
        for (int inputIndex = 0; inputIndex < definition.inputs().size(); inputIndex++) {
            InputSpec input = definition.inputs().get(inputIndex);
            int candidateCount = input.candidates().size();
            if (candidateCount == 0 || candidateCount > PatternLimits.MAX_CANDIDATES_PER_INPUT) {
                return boundedShapeFailure("inputs[" + inputIndex + "].candidates");
            }
            if (candidateCount > PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN - totalCandidates) {
                return boundedShapeFailure("inputs");
            }
            totalCandidates += candidateCount;
        }
        return null;
    }

    private PatternCompileResult.Failure boundedShapeFailure(String sourcePath) {
        return (PatternCompileResult.Failure) PatternCompileResult.failure(
                PatternCompileResult.FailureReason.BOUNDED_SHAPE_REJECTED, sourcePath, null);
    }

    @Nullable
    private KeyId lookup(AEKey key) {
        return keyRegistry.lookup(key);
    }
}

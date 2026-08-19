package appeng.rebuild.pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Small compiled-pattern builders shared by the graph contract tests. */
final class GraphTestFixtures {
    private GraphTestFixtures() {
    }

    static CompiledPattern pattern(String id, long generation, int primaryKey, int... candidateKeys) {
        List<CompiledInputSpec> inputs = candidateKeys.length == 0
                ? List.of()
                : List.of(input(candidateKeys));
        return pattern(id, generation, inputs, List.of(output(primaryKey, true)));
    }

    static CompiledPattern pattern(String id, long generation, List<CompiledInputSpec> inputs,
            List<CompiledOutputSpec> outputs) {
        return new CompiledPattern(new PatternId(id), PatternKind.CRAFTING, inputs, outputs, Optional.empty(),
                new PatternRevision(0L), generation);
    }

    static CompiledInputSpec input(int... candidateKeys) {
        List<CompiledCandidateSpec> candidates = new ArrayList<>(candidateKeys.length);
        for (int candidateKey : candidateKeys) {
            candidates.add(candidate(candidateKey));
        }
        SubstitutionPolicy policy = candidates.size() == 1
                ? SubstitutionPolicy.EXACT
                : SubstitutionPolicy.ALLOW_ALTERNATIVES;
        return new CompiledInputSpec(candidates, policy);
    }

    static CompiledCandidateSpec candidate(int key) {
        return new CompiledCandidateSpec(new KeyId(key), AEAmount.ONE, Optional.empty());
    }

    static CompiledCandidateSpec candidateWithRemainder(int key, int remainderKey) {
        return new CompiledCandidateSpec(new KeyId(key), AEAmount.ONE,
                Optional.of(new CompiledRemainderSpec(new KeyId(remainderKey), AEAmount.ONE)));
    }

    static CompiledOutputSpec output(int key, boolean primary) {
        return new CompiledOutputSpec(new KeyId(key), AEAmount.ONE, primary);
    }
}

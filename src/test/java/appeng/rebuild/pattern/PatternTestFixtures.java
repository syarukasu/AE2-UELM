package appeng.rebuild.pattern;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import appeng.api.stacks.AEKey;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.quantity.AEAmount;

/** Small bootstrap-free builders shared by the pattern contract tests. */
final class PatternTestFixtures {
    private PatternTestFixtures() {
    }

    static AEKey key(String label) {
        AEKey key = mock(AEKey.class, label);
        when(key.getPrimaryKey()).thenReturn(new Object());
        return key;
    }

    static CandidateSpec candidate(AEKey key, long amount) {
        return candidate(key, AEAmount.of(amount), Optional.empty());
    }

    static CandidateSpec candidate(AEKey key, AEAmount amount, Optional<RemainderSpec> remainder) {
        return new CandidateSpec(key, amount, remainder);
    }

    static OutputSpec output(AEKey key, long amount, boolean primary) {
        return new OutputSpec(key, AEAmount.of(amount), primary);
    }

    static OutputSpec output(AEKey key, AEAmount amount, boolean primary) {
        return new OutputSpec(key, amount, primary);
    }

    static InputSpec input(List<CandidateSpec> candidates, SubstitutionPolicy policy) {
        return input(candidates, AEAmount.ONE, policy);
    }

    static InputSpec input(List<CandidateSpec> candidates, AEAmount multiplier, SubstitutionPolicy policy) {
        return new InputSpec(candidates, multiplier, policy);
    }

    static MachineIntent machineIntent() {
        return new MachineIntent("machine", "recipe-fingerprint", "capability-fingerprint", Map.of());
    }

    static PatternDefinition crafting(
            PatternId id,
            List<InputSpec> inputs,
            List<OutputSpec> outputs) {
        return new PatternDefinition(id, PatternKind.CRAFTING, inputs, outputs, Optional.empty(),
                new PatternRevision(0L));
    }

    static PatternDefinition processing(
            PatternId id,
            List<InputSpec> inputs,
            List<OutputSpec> outputs) {
        return new PatternDefinition(id, PatternKind.PROCESSING, inputs, outputs,
                Optional.of(machineIntent()), new PatternRevision(0L));
    }

    static CompiledCandidateSpec compiledCandidate(KeyId key, long amount) {
        return new CompiledCandidateSpec(key, AEAmount.of(amount), Optional.empty());
    }

    static CompiledOutputSpec compiledOutput(KeyId key, long amount, boolean primary) {
        return new CompiledOutputSpec(key, AEAmount.of(amount), primary);
    }

    static CompiledPattern compiled(
            PatternId id,
            long generation,
            List<CompiledInputSpec> inputs,
            List<CompiledOutputSpec> outputs) {
        return new CompiledPattern(id, PatternKind.CRAFTING, inputs, outputs, Optional.empty(),
                new PatternRevision(0L), generation);
    }

    static KeyRegistry registry(long generation, AEKey... keys) {
        KeyRegistry registry = new KeyRegistry(generation);
        for (AEKey key : keys) {
            registry.intern(key);
        }
        return registry;
    }
}

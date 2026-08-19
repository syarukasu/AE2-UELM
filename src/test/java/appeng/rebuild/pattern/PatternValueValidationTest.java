package appeng.rebuild.pattern;

import static appeng.rebuild.pattern.PatternTestFixtures.candidate;
import static appeng.rebuild.pattern.PatternTestFixtures.compiledCandidate;
import static appeng.rebuild.pattern.PatternTestFixtures.compiledOutput;
import static appeng.rebuild.pattern.PatternTestFixtures.crafting;
import static appeng.rebuild.pattern.PatternTestFixtures.input;
import static appeng.rebuild.pattern.PatternTestFixtures.key;
import static appeng.rebuild.pattern.PatternTestFixtures.output;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Immutable-value and bounded-shape contract tests for normalized pattern data. */
class PatternValueValidationTest {

    @Test
    void sourceAndCompiledValuesDefensivelyCopyCollections() {
        var sourceKey = key("source");
        var remainderKey = key("remainder");
        var sourceCandidates = new ArrayList<CandidateSpec>();
        sourceCandidates.add(candidate(sourceKey, AEAmount.ONE,
                Optional.of(new RemainderSpec(remainderKey, AEAmount.of(2L)))));
        InputSpec input = input(sourceCandidates, SubstitutionPolicy.EXACT);
        var sourceInputs = new ArrayList<InputSpec>();
        sourceInputs.add(input);
        var sourceOutputs = new ArrayList<OutputSpec>();
        sourceOutputs.add(output(sourceKey, 3L, true));
        var attributes = new LinkedHashMap<String, String>();
        attributes.put("z", "last");
        attributes.put("a", "first");
        MachineIntent intent = new MachineIntent("machine", "recipe", "capability", attributes);
        PatternDefinition definition = new PatternDefinition(
                new PatternId("source"),
                PatternKind.PROCESSING,
                sourceInputs,
                sourceOutputs,
                Optional.of(intent),
                new PatternRevision(4L));

        sourceCandidates.clear();
        sourceInputs.clear();
        sourceOutputs.clear();
        attributes.put("mutated", "source");

        assertEquals(1, input.candidates().size());
        assertEquals(1, definition.inputs().size());
        assertEquals(1, definition.outputs().size());
        assertEquals(List.of("a", "z"), new ArrayList<>(intent.attributes().keySet()));
        assertEquals(2, intent.attributes().size());
        assertThrows(UnsupportedOperationException.class, () -> input.candidates().clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.inputs().clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.outputs().clear());
        assertThrows(UnsupportedOperationException.class, () -> intent.attributes().put("x", "y"));

        var compiledCandidates = new ArrayList<CompiledCandidateSpec>();
        compiledCandidates.add(new CompiledCandidateSpec(
                new KeyId(0),
                AEAmount.of(5L),
                Optional.of(new CompiledRemainderSpec(new KeyId(1), AEAmount.of(6L)))));
        CompiledInputSpec compiledInput = new CompiledInputSpec(compiledCandidates, SubstitutionPolicy.EXACT);
        var compiledInputs = new ArrayList<CompiledInputSpec>();
        compiledInputs.add(compiledInput);
        var compiledOutputs = new ArrayList<CompiledOutputSpec>();
        compiledOutputs.add(compiledOutput(new KeyId(0), 7L, true));
        CompiledPattern compiled = new CompiledPattern(
                new PatternId("compiled"),
                PatternKind.CRAFTING,
                compiledInputs,
                compiledOutputs,
                Optional.empty(),
                new PatternRevision(8L),
                9L);

        compiledCandidates.clear();
        compiledInputs.clear();
        compiledOutputs.clear();

        assertEquals(1, compiledInput.candidates().size());
        assertEquals(1, compiled.inputs().size());
        assertEquals(1, compiled.outputs().size());
        assertThrows(UnsupportedOperationException.class, () -> compiledInput.candidates().clear());
        assertThrows(UnsupportedOperationException.class, () -> compiled.inputs().clear());
        assertThrows(UnsupportedOperationException.class, () -> compiled.outputs().clear());
    }

    @Test
    void rejectsBlankIdentifiersFingerprintsNegativeRevisionAndZeroAmounts() {
        var sourceKey = key("source");
        assertThrows(IllegalArgumentException.class, () -> new PatternId("   "));
        assertThrows(IllegalArgumentException.class,
                () -> new MachineIntent(" ", "recipe", "capability", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new MachineIntent("machine", " ", "capability", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new MachineIntent("machine", "recipe", " ", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new PatternRevision(-1L));
        assertThrows(IllegalArgumentException.class,
                () -> new CandidateSpec(sourceKey, AEAmount.ZERO, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new RemainderSpec(sourceKey, AEAmount.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new OutputSpec(sourceKey, AEAmount.ZERO, true));
        assertThrows(IllegalArgumentException.class,
                () -> new CompiledCandidateSpec(new KeyId(0), AEAmount.ZERO, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new CompiledRemainderSpec(new KeyId(0), AEAmount.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new CompiledOutputSpec(new KeyId(0), AEAmount.ZERO, true));
    }

    @Test
    void rejectsEmptyAlternativesExactAlternativesAndInvalidPrimaryOutputShapes() {
        var sourceKey = key("source");
        var secondKey = key("second");
        InputSpec validInput = input(List.of(candidate(sourceKey, 1L)), SubstitutionPolicy.EXACT);
        CompiledInputSpec validCompiledInput = new CompiledInputSpec(
                List.of(compiledCandidate(new KeyId(0), 1L)),
                SubstitutionPolicy.EXACT);
        CompiledOutputSpec validCompiledOutput = compiledOutput(new KeyId(0), 1L, true);

        assertThrows(IllegalArgumentException.class,
                () -> new InputSpec(List.of(), SubstitutionPolicy.ALLOW_ALTERNATIVES));
        assertThrows(IllegalArgumentException.class,
                () -> new CompiledInputSpec(List.of(), SubstitutionPolicy.ALLOW_ALTERNATIVES));
        assertThrows(IllegalArgumentException.class,
                () -> input(List.of(candidate(sourceKey, 1L), candidate(secondKey, 2L)), SubstitutionPolicy.EXACT));
        assertThrows(IllegalArgumentException.class,
                () -> new CompiledInputSpec(
                        List.of(compiledCandidate(new KeyId(0), 1L), compiledCandidate(new KeyId(1), 2L)),
                        SubstitutionPolicy.EXACT));

        assertThrows(IllegalArgumentException.class,
                () -> crafting(new PatternId("empty-outputs"), List.of(validInput), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new CompiledPattern(
                        new PatternId("empty-compiled-outputs"),
                        PatternKind.CRAFTING,
                        List.of(validCompiledInput),
                        List.of(),
                        Optional.empty(),
                        new PatternRevision(0L),
                        1L));
        assertThrows(IllegalArgumentException.class,
                () -> crafting(new PatternId("no-primary"), List.of(validInput),
                        List.of(output(sourceKey, 1L, false))));
        assertThrows(IllegalArgumentException.class,
                () -> crafting(new PatternId("multiple-primary"), List.of(validInput), List.of(
                        output(sourceKey, 1L, true), output(secondKey, 2L, true))));
        assertThrows(IllegalArgumentException.class,
                () -> new CompiledPattern(
                        new PatternId("compiled-no-primary"),
                        PatternKind.CRAFTING,
                        List.of(validCompiledInput),
                        List.of(new CompiledOutputSpec(new KeyId(0), AEAmount.ONE, false)),
                        Optional.empty(),
                        new PatternRevision(0L),
                        1L));
        assertThrows(IllegalArgumentException.class,
                () -> new CompiledPattern(
                        new PatternId("compiled-multiple-primary"),
                        PatternKind.CRAFTING,
                        List.of(validCompiledInput),
                        List.of(validCompiledOutput, new CompiledOutputSpec(new KeyId(1), AEAmount.ONE, true)),
                        Optional.empty(),
                        new PatternRevision(0L),
                        1L));
    }

    @Test
    void rejectsKindAndMachineIntentMismatches() {
        var sourceKey = key("source");
        InputSpec input = input(List.of(candidate(sourceKey, 1L)), SubstitutionPolicy.EXACT);
        List<OutputSpec> outputs = List.of(output(sourceKey, 1L, true));
        MachineIntent intent = new MachineIntent("machine", "recipe", "capability", Map.of());

        assertThrows(IllegalArgumentException.class,
                () -> new PatternDefinition(new PatternId("crafting-intent"), PatternKind.CRAFTING,
                        List.of(input), outputs, Optional.of(intent), new PatternRevision(0L)));
        assertThrows(IllegalArgumentException.class,
                () -> new PatternDefinition(new PatternId("processing-no-intent"), PatternKind.PROCESSING,
                        List.of(input), outputs, Optional.empty(), new PatternRevision(0L)));

        CompiledInputSpec compiledInput = new CompiledInputSpec(
                List.of(compiledCandidate(new KeyId(0), 1L)), SubstitutionPolicy.EXACT);
        List<CompiledOutputSpec> compiledOutputs = List.of(compiledOutput(new KeyId(0), 1L, true));
        assertThrows(IllegalArgumentException.class,
                () -> new CompiledPattern(new PatternId("compiled-crafting-intent"), PatternKind.CRAFTING,
                        List.of(compiledInput), compiledOutputs, Optional.of(intent), new PatternRevision(0L), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new CompiledPattern(new PatternId("compiled-processing-no-intent"), PatternKind.PROCESSING,
                        List.of(compiledInput), compiledOutputs, Optional.empty(), new PatternRevision(0L), 1L));
    }

    @Test
    void acceptsEveryDeclaredBoundAndRejectsItsSuccessor() {
        var sourceKey = key("source");
        var candidate = candidate(sourceKey, 1L);
        var compiledCandidate = compiledCandidate(new KeyId(0), 1L);

        assertDoesNotThrow(() -> new PatternId("i".repeat(PatternLimits.MAX_PATTERN_ID_LENGTH)));
        assertThrows(IllegalArgumentException.class,
                () -> new PatternId("i".repeat(PatternLimits.MAX_PATTERN_ID_LENGTH + 1)));

        assertDoesNotThrow(() -> new MachineIntent(
                "b".repeat(PatternLimits.MAX_MACHINE_FIELD_LENGTH),
                "r".repeat(PatternLimits.MAX_MACHINE_FIELD_LENGTH),
                "c".repeat(PatternLimits.MAX_MACHINE_FIELD_LENGTH),
                Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new MachineIntent("b".repeat(PatternLimits.MAX_MACHINE_FIELD_LENGTH + 1), "r", "c", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new MachineIntent("b", "r".repeat(PatternLimits.MAX_MACHINE_FIELD_LENGTH + 1), "c", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new MachineIntent("b", "r", "c".repeat(PatternLimits.MAX_MACHINE_FIELD_LENGTH + 1), Map.of()));

        Map<String, String> maxAttributes = new HashMap<>();
        for (int index = 0; index < PatternLimits.MAX_MACHINE_ATTRIBUTES; index++) {
            maxAttributes.put("key" + index, "value");
        }
        assertDoesNotThrow(() -> new MachineIntent("b", "r", "c", maxAttributes));
        maxAttributes.put("one-too-many", "value");
        assertThrows(IllegalArgumentException.class, () -> new MachineIntent("b", "r", "c", maxAttributes));
        assertDoesNotThrow(() -> new MachineIntent("b", "r", "c", Map.of(
                "k".repeat(PatternLimits.MAX_MACHINE_ATTRIBUTE_KEY_LENGTH), "v")));
        assertThrows(IllegalArgumentException.class, () -> new MachineIntent("b", "r", "c", Map.of(
                "k".repeat(PatternLimits.MAX_MACHINE_ATTRIBUTE_KEY_LENGTH + 1), "v")));
        assertDoesNotThrow(() -> new MachineIntent("b", "r", "c", Map.of(
                "k", "v".repeat(PatternLimits.MAX_MACHINE_ATTRIBUTE_VALUE_LENGTH))));
        assertThrows(IllegalArgumentException.class, () -> new MachineIntent("b", "r", "c", Map.of(
                "k", "v".repeat(PatternLimits.MAX_MACHINE_ATTRIBUTE_VALUE_LENGTH + 1))));

        List<InputSpec> maxGroups = Collections.nCopies(PatternLimits.MAX_INPUT_GROUPS, input(
                List.of(candidate), SubstitutionPolicy.EXACT));
        assertDoesNotThrow(() -> crafting(new PatternId("max-groups"), maxGroups,
                List.of(output(sourceKey, 1L, true))));
        assertThrows(IllegalArgumentException.class, () -> crafting(new PatternId("too-many-groups"),
                append(maxGroups, input(List.of(candidate), SubstitutionPolicy.EXACT)),
                List.of(output(sourceKey, 1L, true))));

        List<CandidateSpec> maxCandidates = Collections.nCopies(PatternLimits.MAX_CANDIDATES_PER_INPUT, candidate);
        assertDoesNotThrow(() -> input(maxCandidates, SubstitutionPolicy.ALLOW_ALTERNATIVES));
        assertThrows(IllegalArgumentException.class, () -> input(
                append(maxCandidates, candidate), SubstitutionPolicy.ALLOW_ALTERNATIVES));

        List<OutputSpec> maxOutputs = outputs(PatternLimits.MAX_OUTPUTS, sourceKey);
        assertDoesNotThrow(() -> crafting(new PatternId("max-outputs"), List.of(), maxOutputs));
        assertThrows(IllegalArgumentException.class, () -> crafting(new PatternId("too-many-outputs"), List.of(),
                append(maxOutputs, output(sourceKey, 1L, false))));

        List<InputSpec> maxTotalSource = List.of(
                input(Collections.nCopies(PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN / 2, candidate),
                        SubstitutionPolicy.ALLOW_ALTERNATIVES),
                input(Collections.nCopies(PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN / 2, candidate),
                        SubstitutionPolicy.ALLOW_ALTERNATIVES));
        assertDoesNotThrow(() -> crafting(new PatternId("max-total"), maxTotalSource,
                List.of(output(sourceKey, 1L, true))));
        List<InputSpec> tooManyTotalSource = List.of(
                maxTotalSource.get(0),
                input(Collections.nCopies(PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN / 2 + 1, candidate),
                        SubstitutionPolicy.ALLOW_ALTERNATIVES));
        assertThrows(IllegalArgumentException.class, () -> crafting(new PatternId("too-many-total"), tooManyTotalSource,
                List.of(output(sourceKey, 1L, true))));

        List<CompiledInputSpec> maxCompiledGroups = Collections.nCopies(PatternLimits.MAX_INPUT_GROUPS,
                new CompiledInputSpec(List.of(compiledCandidate), SubstitutionPolicy.EXACT));
        List<CompiledOutputSpec> maxCompiledOutputs = compiledOutputs(PatternLimits.MAX_OUTPUTS);
        assertDoesNotThrow(() -> new CompiledPattern(new PatternId("max-compiled"), PatternKind.CRAFTING,
                maxCompiledGroups, maxCompiledOutputs, Optional.empty(), new PatternRevision(0L), 1L));
        assertThrows(IllegalArgumentException.class, () -> new CompiledPattern(new PatternId("too-many-compiled"),
                PatternKind.CRAFTING,
                append(maxCompiledGroups, new CompiledInputSpec(List.of(compiledCandidate), SubstitutionPolicy.EXACT)),
                maxCompiledOutputs, Optional.empty(), new PatternRevision(0L), 1L));
        assertDoesNotThrow(() -> new CompiledInputSpec(
                Collections.nCopies(PatternLimits.MAX_CANDIDATES_PER_INPUT, compiledCandidate),
                SubstitutionPolicy.ALLOW_ALTERNATIVES));
        assertThrows(IllegalArgumentException.class, () -> new CompiledInputSpec(
                append(Collections.nCopies(PatternLimits.MAX_CANDIDATES_PER_INPUT, compiledCandidate),
                        compiledCandidate),
                SubstitutionPolicy.ALLOW_ALTERNATIVES));
    }

    @Test
    void compiledPatternRejectsTotalCandidatesAboveItsBound() {
        List<CompiledCandidateSpec> half = Collections.nCopies(
                PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN / 2,
                compiledCandidate(new KeyId(0), 1L));
        CompiledInputSpec first = new CompiledInputSpec(half, SubstitutionPolicy.ALLOW_ALTERNATIVES);
        CompiledInputSpec secondAtLimit = new CompiledInputSpec(half, SubstitutionPolicy.ALLOW_ALTERNATIVES);
        CompiledInputSpec secondAboveLimit = new CompiledInputSpec(
                append(half, compiledCandidate(new KeyId(0), 1L)), SubstitutionPolicy.ALLOW_ALTERNATIVES);
        List<CompiledOutputSpec> outputs = List.of(compiledOutput(new KeyId(0), 1L, true));

        assertDoesNotThrow(() -> new CompiledPattern(new PatternId("compiled-total-limit"), PatternKind.CRAFTING,
                List.of(first, secondAtLimit), outputs, Optional.empty(), new PatternRevision(0L), 1L));
        assertThrows(IllegalArgumentException.class, () -> new CompiledPattern(
                new PatternId("compiled-total-over"), PatternKind.CRAFTING, List.of(first, secondAboveLimit), outputs,
                Optional.empty(), new PatternRevision(0L), 1L));
    }

    private static List<OutputSpec> outputs(int count, appeng.api.stacks.AEKey key) {
        List<OutputSpec> outputs = new ArrayList<>();
        outputs.add(output(key, 1L, true));
        for (int index = 1; index < count; index++) {
            outputs.add(output(key, 1L, false));
        }
        return outputs;
    }

    private static List<CompiledOutputSpec> compiledOutputs(int count) {
        List<CompiledOutputSpec> outputs = new ArrayList<>();
        outputs.add(compiledOutput(new KeyId(0), 1L, true));
        for (int index = 1; index < count; index++) {
            outputs.add(compiledOutput(new KeyId(0), 1L, false));
        }
        return outputs;
    }

    private static <T> List<T> append(List<T> values, T extra) {
        List<T> result = new ArrayList<>(values);
        result.add(extra);
        return result;
    }
}

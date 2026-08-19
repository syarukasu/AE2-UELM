package appeng.rebuild.pattern;

import static appeng.rebuild.pattern.PatternTestFixtures.candidate;
import static appeng.rebuild.pattern.PatternTestFixtures.crafting;
import static appeng.rebuild.pattern.PatternTestFixtures.input;
import static appeng.rebuild.pattern.PatternTestFixtures.key;
import static appeng.rebuild.pattern.PatternTestFixtures.output;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEKey;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.quantity.AEAmount;

/** Exactness, lookup-only compilation, and deterministic randomized compiler tests. */
class PatternCompilerTest {

    @Test
    void compilesCandidateSpecificQuantitiesAndRemaindersInSourceOrder() {
        AEKey item = key("item");
        AEKey fluid = key("fluid");
        AEKey itemRemainder = key("item-remainder");
        AEKey fluidRemainder = key("fluid-remainder");
        KeyRegistry registry = new KeyRegistry(41L);
        registry.intern(item);
        registry.intern(fluid);
        registry.intern(itemRemainder);
        registry.intern(fluidRemainder);
        PatternDefinition definition = crafting(
                new PatternId("candidate-order"),
                List.of(input(List.of(
                        candidate(item, AEAmount.ONE, Optional.of(new RemainderSpec(itemRemainder, AEAmount.of(2L)))),
                        candidate(fluid, AEAmount.of(1000L),
                                Optional.of(new RemainderSpec(fluidRemainder, AEAmount.of(333L))))),
                        SubstitutionPolicy.ALLOW_ALTERNATIVES)),
                List.of(output(item, 1L, true)));

        PatternCompileResult.Success success = compileSuccess(registry, definition);
        List<CompiledCandidateSpec> candidates = success.pattern().inputs().get(0).candidates();

        assertEquals(registry.lookup(item), candidates.get(0).key());
        assertEquals(AEAmount.ONE, candidates.get(0).amountPerExecution());
        assertEquals(registry.lookup(itemRemainder), candidates.get(0).remainder().orElseThrow().key());
        assertEquals(AEAmount.of(2L), candidates.get(0).remainder().orElseThrow().amount());
        assertEquals(registry.lookup(fluid), candidates.get(1).key());
        assertEquals(AEAmount.of(1000L), candidates.get(1).amountPerExecution());
        assertEquals(registry.lookup(fluidRemainder), candidates.get(1).remainder().orElseThrow().key());
        assertEquals(AEAmount.of(333L), candidates.get(1).remainder().orElseThrow().amount());
    }

    @Test
    void preservesAllExactQuantityBoundariesWithoutNarrowing() {
        AEKey[] keys = { key("zero-never"), key("max"), key("above-max"), key("two-to-128"), key("ten-to-1000") };
        AEAmount[] values = {
                AEAmount.of(Long.MAX_VALUE),
                AEAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
                AEAmount.of(BigInteger.ONE.shiftLeft(128)),
                AEAmount.of(BigInteger.TEN.pow(1000))
        };
        KeyRegistry registry = new KeyRegistry(42L);
        for (AEKey key : keys) {
            registry.intern(key);
        }
        List<CandidateSpec> sourceCandidates = new ArrayList<>();
        for (int index = 0; index < values.length; index++) {
            sourceCandidates.add(candidate(keys[index + 1], values[index],
                    Optional.of(new RemainderSpec(keys[0], values[index]))));
        }
        PatternDefinition definition = crafting(
                new PatternId("exact-boundaries"),
                List.of(input(sourceCandidates, SubstitutionPolicy.ALLOW_ALTERNATIVES)),
                List.of(output(keys[1], values[0], true), output(keys[2], values[1], false),
                        output(keys[3], values[2], false), output(keys[4], values[3], false)));

        PatternCompileResult.Success success = compileSuccess(registry, definition);
        for (int index = 0; index < values.length; index++) {
            CompiledCandidateSpec compiled = success.pattern().inputs().get(0).candidates().get(index);
            assertEquals(values[index], compiled.amountPerExecution());
            assertEquals(values[index], compiled.remainder().orElseThrow().amount());
            assertEquals(values[index], definition.inputs().get(0).candidates().get(index).amountPerExecution());
        }
        for (int index = 0; index < values.length; index++) {
            assertEquals(values[index], success.pattern().outputs().get(index).amountPerExecution());
        }
    }

    @Test
    void unknownKeyIsATypedLookupFailureWithoutInterningOrPartialSuccess() {
        AEKey known = key("known");
        AEKey unknown = key("unknown");
        KeyRegistry registry = new KeyRegistry(43L);
        registry.intern(known);
        PatternDefinition definition = crafting(
                new PatternId("unknown-key"),
                List.of(input(List.of(candidate(known, 1L), candidate(unknown, 2L)),
                        SubstitutionPolicy.ALLOW_ALTERNATIVES)),
                List.of(output(known, 1L, true)));

        PatternCompileResult.Failure failure = assertInstanceOf(
                PatternCompileResult.Failure.class,
                new PatternCompiler(registry).compile(definition));

        assertFalse(failure.isSuccess());
        assertEquals(PatternCompileResult.FailureReason.UNKNOWN_KEY, failure.reason());
        assertEquals("inputs[0].candidates[1].key", failure.sourcePath());
        assertEquals(unknown, failure.keyOptional().orElseThrow());
        assertEquals(1, registry.size());
    }

    @Test
    void knownCompilationUsesLookupOnlyAndCarriesRegistryGeneration() {
        AEKey known = key("known");
        KeyRegistry registry = new KeyRegistry(44L);
        KeyId knownId = registry.intern(known);
        PatternDefinition definition = crafting(
                new PatternId("known-key"),
                List.of(input(List.of(candidate(known, 1L)), SubstitutionPolicy.EXACT)),
                List.of(output(known, 2L, true)));

        PatternCompileResult.Success success = compileSuccess(registry, definition);

        assertEquals(44L, success.pattern().keyRegistryGeneration());
        assertEquals(knownId, success.pattern().inputs().get(0).candidates().get(0).key());
        assertEquals(1, registry.size());
    }

    @Test
    void randomizedBoundedCompilationsMatchAnIndependentReference() {
        Random random = new Random(0xA2E5_004AL);
        AEKey[] keys = { key("random-a"), key("random-b"), key("random-c") };
        KeyRegistry registry = new KeyRegistry(45L);
        for (AEKey key : keys) {
            registry.intern(key);
        }
        PatternCompiler compiler = new PatternCompiler(registry);

        for (int patternIndex = 0; patternIndex < 256; patternIndex++) {
            int candidateCount = 1 + random.nextInt(3);
            List<CandidateSpec> sourceCandidates = new ArrayList<>();
            List<ExpectedCandidate> expectedCandidates = new ArrayList<>();
            for (int candidateIndex = 0; candidateIndex < candidateCount; candidateIndex++) {
                AEKey sourceKey = keys[random.nextInt(keys.length)];
                AEAmount amount = randomAmount(random);
                Optional<ExpectedRemainder> expectedRemainder = Optional.empty();
                Optional<RemainderSpec> remainder = Optional.empty();
                if (random.nextBoolean()) {
                    AEKey remainderKey = keys[random.nextInt(keys.length)];
                    AEAmount remainderAmount = randomAmount(random);
                    remainder = Optional.of(new RemainderSpec(remainderKey, remainderAmount));
                    expectedRemainder = Optional
                            .of(new ExpectedRemainder(registry.lookup(remainderKey), remainderAmount));
                }
                sourceCandidates.add(new CandidateSpec(sourceKey, amount, remainder));
                expectedCandidates.add(new ExpectedCandidate(registry.lookup(sourceKey), amount, expectedRemainder));
            }

            List<OutputSpec> sourceOutputs = new ArrayList<>();
            List<ExpectedOutput> expectedOutputs = new ArrayList<>();
            AEKey primaryKey = keys[random.nextInt(keys.length)];
            AEAmount primaryAmount = randomAmount(random);
            sourceOutputs.add(new OutputSpec(primaryKey, primaryAmount, true));
            expectedOutputs.add(new ExpectedOutput(registry.lookup(primaryKey), primaryAmount, true));
            if (random.nextBoolean()) {
                AEKey secondaryKey = keys[random.nextInt(keys.length)];
                AEAmount secondaryAmount = randomAmount(random);
                sourceOutputs.add(new OutputSpec(secondaryKey, secondaryAmount, false));
                expectedOutputs.add(new ExpectedOutput(registry.lookup(secondaryKey), secondaryAmount, false));
            }

            PatternDefinition definition = new PatternDefinition(
                    new PatternId("random-" + patternIndex),
                    PatternKind.CRAFTING,
                    List.of(new InputSpec(sourceCandidates,
                            candidateCount == 1 ? SubstitutionPolicy.EXACT : SubstitutionPolicy.ALLOW_ALTERNATIVES)),
                    sourceOutputs,
                    Optional.empty(),
                    new PatternRevision(patternIndex));
            PatternCompileResult.Success success = assertInstanceOf(
                    PatternCompileResult.Success.class, compiler.compile(definition));

            assertEquals(expectedCandidates.size(), success.pattern().inputs().get(0).candidates().size());
            for (int candidateIndex = 0; candidateIndex < expectedCandidates.size(); candidateIndex++) {
                ExpectedCandidate expected = expectedCandidates.get(candidateIndex);
                CompiledCandidateSpec actual = success.pattern().inputs().get(0).candidates().get(candidateIndex);
                assertEquals(expected.key(), actual.key());
                assertEquals(expected.amount(), actual.amountPerExecution());
                if (expected.remainder().isEmpty()) {
                    assertTrue(actual.remainder().isEmpty());
                } else {
                    ExpectedRemainder expectedRemainder = expected.remainder().orElseThrow();
                    assertEquals(expectedRemainder.key(), actual.remainder().orElseThrow().key());
                    assertEquals(expectedRemainder.amount(), actual.remainder().orElseThrow().amount());
                }
            }
            assertEquals(expectedOutputs.size(), success.pattern().outputs().size());
            for (int outputIndex = 0; outputIndex < expectedOutputs.size(); outputIndex++) {
                ExpectedOutput expected = expectedOutputs.get(outputIndex);
                CompiledOutputSpec actual = success.pattern().outputs().get(outputIndex);
                assertEquals(expected.key(), actual.key());
                assertEquals(expected.amount(), actual.amountPerExecution());
                assertEquals(expected.primary(), actual.primary());
            }
            assertEquals(45L, success.pattern().keyRegistryGeneration());
        }
        assertEquals(3, registry.size());
    }

    private static PatternCompileResult.Success compileSuccess(KeyRegistry registry, PatternDefinition definition) {
        return assertInstanceOf(PatternCompileResult.Success.class, new PatternCompiler(registry).compile(definition));
    }

    private static AEAmount randomAmount(Random random) {
        return switch (random.nextInt(5)) {
            case 0 -> AEAmount.of(1L + random.nextInt(100_000));
            case 1 -> AEAmount.of(BigInteger.valueOf(Long.MAX_VALUE));
            case 2 -> AEAmount.of(BigInteger.ONE.shiftLeft(128).add(BigInteger.valueOf(random.nextInt(100))));
            case 3 -> AEAmount.of(BigInteger.TEN.pow(20).add(BigInteger.valueOf(random.nextInt(1000))));
            default -> AEAmount.of(1L + random.nextInt(1_000_000));
        };
    }

    private record ExpectedCandidate(KeyId key, AEAmount amount, Optional<ExpectedRemainder> remainder) {
    }

    private record ExpectedRemainder(KeyId key, AEAmount amount) {
    }

    private record ExpectedOutput(KeyId key, AEAmount amount, boolean primary) {
    }
}

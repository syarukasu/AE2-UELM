package appeng.rebuild.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.CompiledCandidateSpec;
import appeng.rebuild.pattern.CompiledInputSpec;
import appeng.rebuild.pattern.CompiledOutputSpec;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.CompiledRemainderSpec;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternKind;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.pattern.SubstitutionPolicy;
import appeng.rebuild.quantity.AEAmount;

class ProductiveCycleSolverTest {
    private static final long KEY_GENERATION = 7L;
    private static final ProductiveCycleSolver SOLVER = new ProductiveCycleSolver();

    @Test
    void selfRingSolvesHugeRootDemandWithConstantSizeTemplate() {
        CompiledPattern pattern = selfPattern("huge-self", 1, 2);
        ProductiveCycleRing ring = selfRing(pattern);
        AEAmount huge = AEAmount.of(BigInteger.TEN.pow(1000));

        ProductiveCycleTemplate template = success(ring, root(1, huge));

        assertEquals(new KeyId(1), template.seedKey());
        assertEquals(AEAmount.ONE, template.seedAmount());
        assertEquals(AEAmount.ONE, template.gainPerTurn());
        assertEquals(huge, template.repetitions());
        assertEquals(1, template.members().size());
        assertEquals(AEAmount.ONE, template.members().get(0).executionsPerTurn());
        assertEquals(1, template.internalLinks().size());
        assertEquals(AEAmount.of(2L), template.internalLinks().get(0).producedAmountPerTurn());
        assertEquals(AEAmount.ONE, template.internalLinks().get(0).consumedAmountPerTurn());
        assertEquals(Map.of(new KeyId(1), AEAmount.of(2L)), template.outputCreditsPerTurn());
    }

    @Test
    void twoMemberRingUsesExactNormalizedExecutionVectorAndCredits() {
        CompiledPattern a = pattern("two-a", KEY_GENERATION,
                List.of(exactInput(1, 1, 1)), List.of(output(2, 3, true), output(7, 5, false)));
        CompiledPattern b = pattern("two-b", KEY_GENERATION,
                List.of(exactInput(2, 2, 1)), List.of(output(1, 1, true), output(7, 7, false)));
        ProductiveCycleRing ring = ring(member(a, 0, 0), member(b, 0, 0));

        ProductiveCycleTemplate template = success(ring, root(1, amount(4)));

        assertEquals(List.of(amount(2), amount(3)),
                template.members().stream().map(ProductiveCycleTemplate.Member::executionsPerTurn).toList());
        assertEquals(amount(2), template.seedAmount());
        assertEquals(AEAmount.ONE, template.gainPerTurn());
        assertEquals(amount(4), template.repetitions());
        assertEquals(amount(6), template.internalLinks().get(0).producedAmountPerTurn());
        assertEquals(amount(6), template.internalLinks().get(0).consumedAmountPerTurn());
        assertEquals(amount(3), template.internalLinks().get(1).producedAmountPerTurn());
        assertEquals(amount(2), template.internalLinks().get(1).consumedAmountPerTurn());
        assertEquals(Map.of(new KeyId(1), amount(3), new KeyId(2), amount(6), new KeyId(7), amount(31)),
                template.outputCreditsPerTurn());
    }

    @Test
    void executionVectorIsReducedByItsGreatestCommonDivisor() {
        CompiledPattern a = pattern("gcd-a", KEY_GENERATION,
                List.of(exactInput(1, 1, 1)), List.of(output(2, 6, true)));
        CompiledPattern b = pattern("gcd-b", KEY_GENERATION,
                List.of(exactInput(2, 4, 1)), List.of(output(1, 2, true)));

        ProductiveCycleTemplate template = success(ring(member(a, 0, 0), member(b, 0, 0)), root(1, amount(8)));

        assertEquals(List.of(amount(2), amount(3)),
                template.members().stream().map(ProductiveCycleTemplate.Member::executionsPerTurn).toList());
        assertEquals(amount(2), template.seedAmount());
        assertEquals(amount(4), template.gainPerTurn());
        assertEquals(amount(2), template.repetitions());
    }

    @Test
    void rootDemandExcludesSeedWhileInputDemandIncludesAvailableSeed() {
        ProductiveCycleRing ring = selfRing(selfPattern("cause-self", 1, 2));
        AEAmount demand = amount(7);
        PlannedBatchCause.Input inputCause = new PlannedBatchCause.Input(new PlannedBatchId(19L),
                PlannedBatchCause.Input.NORMAL_CONSUMER_MEMBER, 2, 3, new KeyId(1), demand);

        ProductiveCycleTemplate root = success(ring, root(1, demand));
        ProductiveCycleTemplate input = success(ring, inputCause);

        assertEquals(demand, root.repetitions());
        assertEquals(amount(6), input.repetitions());
        assertEquals(inputCause, input.cause());
        assertEquals(AEAmount.ONE, success(ring, new PlannedBatchCause.Input(new PlannedBatchId(20L),
                PlannedBatchCause.Input.NORMAL_CONSUMER_MEMBER, 0, 0, new KeyId(1), AEAmount.ONE)).repetitions());
    }

    @Test
    void zeroGainIsNonProductiveAndNegativeGainIsMalformed() {
        assertFailure(ProductiveCycleSolveResult.FailureReason.NON_PRODUCTIVE,
                selfRing(selfPattern("zero-gain", 1, 1)), root(1, AEAmount.ONE));
        assertFailure(ProductiveCycleSolveResult.FailureReason.MALFORMED_RING,
                selfRing(selfPattern("negative-gain", 2, 1)), root(1, AEAmount.ONE));
    }

    @Test
    void causeMustDemandTheClosingSeedKey() {
        assertFailure(ProductiveCycleSolveResult.FailureReason.MALFORMED_RING,
                selfRing(selfPattern("cause-mismatch", 1, 2)), root(9, AEAmount.ONE));
    }

    @Test
    void duplicateMembersGenerationAndInternalKeysAreMalformed() {
        CompiledPattern self = selfPattern("duplicate", 1, 2);
        assertFailure(ProductiveCycleSolveResult.FailureReason.MALFORMED_RING,
                ring(member(self, 0, 0), member(self, 0, 0)), root(1, AEAmount.ONE));

        CompiledPattern generationA = pattern("generation-a", 7L,
                List.of(exactInput(1, 1, 1)), List.of(output(2, 1, true)));
        CompiledPattern generationB = pattern("generation-b", 8L,
                List.of(exactInput(2, 1, 1)), List.of(output(1, 2, true)));
        assertFailure(ProductiveCycleSolveResult.FailureReason.MALFORMED_RING,
                ring(member(generationA, 0, 0), member(generationB, 0, 0)), root(1, AEAmount.ONE));

        CompiledPattern sameKeyA = selfPattern("same-key-a", 1, 2);
        CompiledPattern sameKeyB = selfPattern("same-key-b", 1, 2);
        assertFailure(ProductiveCycleSolveResult.FailureReason.MALFORMED_RING,
                ring(member(sameKeyA, 0, 0), member(sameKeyB, 0, 0)), root(1, AEAmount.ONE));
    }

    @Test
    void invalidIndicesRemainderAndMultiCandidateInternalInputAreMalformed() {
        CompiledPattern self = selfPattern("index-self", 1, 2);
        assertFailure(ProductiveCycleSolveResult.FailureReason.MALFORMED_RING,
                ring(new ProductiveCycleRingMember(self, -1, 0, 0)), root(1, AEAmount.ONE));
        assertFailure(ProductiveCycleSolveResult.FailureReason.MALFORMED_RING,
                ring(new ProductiveCycleRingMember(self, 0, 1, 0)), root(1, AEAmount.ONE));
        assertFailure(ProductiveCycleSolveResult.FailureReason.MALFORMED_RING,
                ring(new ProductiveCycleRingMember(self, 0, 0, 1)), root(1, AEAmount.ONE));

        CompiledPattern remainder = pattern("internal-remainder", KEY_GENERATION,
                List.of(input(1, List.of(candidate(1, 1, Optional.of(remainder(9, 1)))),
                        SubstitutionPolicy.EXACT)),
                List.of(output(1, 2, true)));
        assertFailure(ProductiveCycleSolveResult.FailureReason.MALFORMED_RING,
                selfRing(remainder), root(1, AEAmount.ONE));

        CompiledPattern alternatives = pattern("internal-alternatives", KEY_GENERATION,
                List.of(input(1, List.of(candidate(1, 1, Optional.empty()), candidate(2, 1, Optional.empty())),
                        SubstitutionPolicy.ALLOW_ALTERNATIVES)),
                List.of(output(1, 2, true)));
        assertFailure(ProductiveCycleSolveResult.FailureReason.MALFORMED_RING,
                selfRing(alternatives), root(1, AEAmount.ONE));
    }

    @Test
    void externalAlternativeGroupIsPreservedWithoutCandidateSelection() {
        CompiledInputSpec external = input(4,
                List.of(candidate(5, 2, Optional.of(remainder(8, 1))), candidate(6, 3, Optional.empty())),
                SubstitutionPolicy.ALLOW_ALTERNATIVES);
        CompiledPattern pattern = pattern("external-alternatives", KEY_GENERATION,
                List.of(exactInput(1, 1, 1), external), List.of(output(1, 2, true)));

        ProductiveCycleTemplate template = success(selfRing(pattern), root(1, amount(3)));
        ProductiveCycleTemplate.ExternalInputRequirement requirement = template.members().get(0)
                .externalInputsPerTurn().get(0);

        assertEquals(0, requirement.memberIndex());
        assertEquals(1, requirement.inputIndex());
        assertSame(external, requirement.input());
        assertEquals(amount(4), requirement.templateUnitsPerTurn());
        assertEquals(2, requirement.input().candidates().size());
        assertEquals(SubstitutionPolicy.ALLOW_ALTERNATIVES, requirement.input().policy());
        assertTrue(requirement.input().candidates().get(0).remainder().isPresent());
    }

    @Test
    void hiddenInternalCandidatesRemaindersAndOutputsAreRejected() {
        CompiledPattern hiddenCandidate = pattern("hidden-candidate", KEY_GENERATION,
                List.of(exactInput(1, 1, 1), exactInput(1, 1, 1)), List.of(output(1, 2, true)));
        assertFailure(ProductiveCycleSolveResult.FailureReason.MALFORMED_RING,
                selfRing(hiddenCandidate), root(1, AEAmount.ONE));

        CompiledPattern hiddenRemainder = pattern("hidden-remainder", KEY_GENERATION,
                List.of(exactInput(1, 1, 1), input(1,
                        List.of(candidate(5, 1, Optional.of(remainder(1, 1)))), SubstitutionPolicy.EXACT)),
                List.of(output(1, 2, true)));
        assertFailure(ProductiveCycleSolveResult.FailureReason.MALFORMED_RING,
                selfRing(hiddenRemainder), root(1, AEAmount.ONE));

        CompiledPattern hiddenOutput = pattern("hidden-output", KEY_GENERATION,
                List.of(exactInput(1, 1, 1)), List.of(output(1, 2, true), output(1, 1, false)));
        assertFailure(ProductiveCycleSolveResult.FailureReason.MALFORMED_RING,
                selfRing(hiddenOutput), root(1, AEAmount.ONE));
    }

    @Test
    void forgedTemplateRejectsLinksOutputsCreditsAndRepetitions() {
        ProductiveCycleTemplate valid = success(selfRing(selfPattern("forge-self", 1, 2)), root(1, amount(3)));
        ProductiveCycleTemplate.InternalLink link = valid.internalLinks().get(0);
        ProductiveCycleTemplate.InternalLink wrongLink = new ProductiveCycleTemplate.InternalLink(
                link.producerMemberIndex(), link.outputIndex(), link.consumerMemberIndex(), link.inputIndex(),
                link.candidateIndex(), link.key(), amount(3), link.consumedAmountPerTurn());
        assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.repetitions(), valid.members(),
                List.of(wrongLink), valid.outputCreditsPerTurn()));

        ProductiveCycleTemplate.Member member = valid.members().get(0);
        ProductiveCycleTemplate.Member missingOutput = new ProductiveCycleTemplate.Member(member.pattern(),
                member.executionsPerTurn(), member.externalInputsPerTurn(), List.of());
        assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.repetitions(), List.of(missingOutput),
                valid.internalLinks(), valid.outputCreditsPerTurn()));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.repetitions(), valid.members(),
                valid.internalLinks(), Map.of(new KeyId(1), amount(3))));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, amount(4), valid.members(),
                valid.internalLinks(), valid.outputCreditsPerTurn()));
    }

    @Test
    void forgedTemplateRejectsMissingExternalInputCompleteness() {
        CompiledInputSpec external = exactInput(5, 2, 3);
        CompiledPattern pattern = pattern("forge-external", KEY_GENERATION,
                List.of(exactInput(1, 1, 1), external), List.of(output(1, 2, true)));
        ProductiveCycleTemplate valid = success(selfRing(pattern), root(1, AEAmount.ONE));
        ProductiveCycleTemplate.Member member = valid.members().get(0);
        ProductiveCycleTemplate.Member missingExternal = new ProductiveCycleTemplate.Member(member.pattern(),
                member.executionsPerTurn(), List.of(), member.outputsPerTurn());

        assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.repetitions(), List.of(missingExternal),
                valid.internalLinks(), valid.outputCreditsPerTurn()));
    }

    @Test
    void quantityOverflowIsTypedWithoutNarrowing() {
        AEAmount boundary = AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS - 1));
        CompiledPattern pattern = pattern("quantity-limit", KEY_GENERATION,
                List.of(input(2, List.of(candidate(1, boundary, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1, true)));

        assertFailure(ProductiveCycleSolveResult.FailureReason.QUANTITY_LIMIT,
                selfRing(pattern), root(1, AEAmount.ONE));
    }

    @Test
    void boundedWorkReturnsTypedFailureBeforeLargeRingAlgebra() {
        int count = PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS;
        List<ProductiveCycleRingMember> members = new ArrayList<>(count);
        for (int memberIndex = 0; memberIndex < count; memberIndex++) {
            List<CompiledInputSpec> inputs = new ArrayList<>();
            inputs.add(exactInput(memberIndex, 1, 1));
            for (int inputIndex = 0; inputIndex < 32; inputIndex++) {
                inputs.add(exactInput(1_000 + memberIndex * 32 + inputIndex, 1, 1));
            }
            int nextKey = (memberIndex + 1) % count;
            long outputAmount = memberIndex == count - 1 ? 2L : 1L;
            CompiledPattern pattern = pattern("work-" + memberIndex, KEY_GENERATION, inputs,
                    List.of(output(nextKey, outputAmount, true)));
            members.add(member(pattern, 0, 0));
        }

        assertFailure(ProductiveCycleSolveResult.FailureReason.WORK_LIMIT,
                new ProductiveCycleRing(members), root(0, AEAmount.ONE));
    }

    @Test
    void ringAndTemplateAreImmutableAndOversizePreflightDoesNotIterate() {
        ProductiveCycleRingMember member = member(selfPattern("immutable-self", 1, 2), 0, 0);
        List<ProductiveCycleRingMember> source = new ArrayList<>(List.of(member));
        ProductiveCycleRing ring = new ProductiveCycleRing(source);
        source.clear();
        assertEquals(List.of(member), ring.members());
        assertThrows(UnsupportedOperationException.class, () -> ring.members().clear());

        ProductiveCycleTemplate template = success(ring, root(1, AEAmount.ONE));
        assertThrows(UnsupportedOperationException.class, () -> template.members().clear());
        assertThrows(UnsupportedOperationException.class, () -> template.internalLinks().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> template.outputCreditsPerTurn().put(new KeyId(9), AEAmount.ONE));

        List<ProductiveCycleRingMember> oversized = throwingList(PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS + 1);
        assertThrows(IllegalArgumentException.class, () -> new ProductiveCycleRing(oversized));

        List<ProductiveCycleTemplate.ExternalInputRequirement> tooManyInputs = throwingList(
                PatternLimits.MAX_INPUT_GROUPS);
        assertThrows(IllegalArgumentException.class, () -> new ProductiveCycleTemplate.Member(member.pattern(),
                AEAmount.ONE, tooManyInputs, List.of()));
        List<ProductiveCycleTemplate.Output> tooManyOutputs = throwingList(PatternLimits.MAX_OUTPUTS + 1);
        assertThrows(IllegalArgumentException.class, () -> new ProductiveCycleTemplate.Member(member.pattern(),
                AEAmount.ONE, List.of(), tooManyOutputs));
    }

    private static ProductiveCycleTemplate copy(ProductiveCycleTemplate source, AEAmount repetitions,
            List<ProductiveCycleTemplate.Member> members, List<ProductiveCycleTemplate.InternalLink> links,
            Map<KeyId, AEAmount> credits) {
        return new ProductiveCycleTemplate(source.cause(), source.seedKey(), source.seedAmount(), source.gainPerTurn(),
                repetitions, members, links, credits);
    }

    private static ProductiveCycleTemplate success(ProductiveCycleRing ring, PlannedBatchCause cause) {
        ProductiveCycleSolveResult result = SOLVER.solve(ring, cause);
        return assertInstanceOf(ProductiveCycleSolveResult.Success.class, result).template();
    }

    private static void assertFailure(ProductiveCycleSolveResult.FailureReason reason, ProductiveCycleRing ring,
            PlannedBatchCause cause) {
        ProductiveCycleSolveResult result = SOLVER.solve(ring, cause);
        assertEquals(reason, assertInstanceOf(ProductiveCycleSolveResult.Failure.class, result).reason());
    }

    private static PlannedBatchCause.Root root(int key, AEAmount amount) {
        return new PlannedBatchCause.Root(new KeyId(key), amount);
    }

    private static ProductiveCycleRing selfRing(CompiledPattern pattern) {
        return ring(member(pattern, 0, 0));
    }

    private static ProductiveCycleRing ring(ProductiveCycleRingMember... members) {
        return new ProductiveCycleRing(List.of(members));
    }

    private static ProductiveCycleRingMember member(CompiledPattern pattern, int inputIndex, int outputIndex) {
        return new ProductiveCycleRingMember(pattern, inputIndex, 0, outputIndex);
    }

    private static CompiledPattern selfPattern(String id, long consumption, long production) {
        return pattern(id, KEY_GENERATION, List.of(exactInput(1, consumption, 1)),
                List.of(output(1, production, true)));
    }

    private static CompiledPattern pattern(String id, long generation, List<CompiledInputSpec> inputs,
            List<CompiledOutputSpec> outputs) {
        return new CompiledPattern(new PatternId(id), PatternKind.CRAFTING, inputs, outputs, Optional.empty(),
                new PatternRevision(0L), generation);
    }

    private static CompiledInputSpec exactInput(int key, long amount, long multiplier) {
        return input(multiplier, List.of(candidate(key, amount, Optional.empty())), SubstitutionPolicy.EXACT);
    }

    private static CompiledInputSpec input(long multiplier, List<CompiledCandidateSpec> candidates,
            SubstitutionPolicy policy) {
        return new CompiledInputSpec(candidates, amount(multiplier), policy);
    }

    private static CompiledCandidateSpec candidate(int key, long amount,
            Optional<CompiledRemainderSpec> remainder) {
        return candidate(key, amount(amount), remainder);
    }

    private static CompiledCandidateSpec candidate(int key, AEAmount amount,
            Optional<CompiledRemainderSpec> remainder) {
        return new CompiledCandidateSpec(new KeyId(key), amount, remainder);
    }

    private static CompiledRemainderSpec remainder(int key, long amount) {
        return new CompiledRemainderSpec(new KeyId(key), amount(amount));
    }

    private static CompiledOutputSpec output(int key, long amount, boolean primary) {
        return new CompiledOutputSpec(new KeyId(key), amount(amount), primary);
    }

    private static AEAmount amount(long value) {
        return AEAmount.of(value);
    }

    private static <T> List<T> throwingList(int size) {
        return new AbstractList<>() {
            @Override
            public T get(int index) {
                throw new AssertionError("oversize preflight iterated");
            }

            @Override
            public int size() {
                return size;
            }
        };
    }
}

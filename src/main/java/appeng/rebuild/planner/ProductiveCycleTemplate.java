package appeng.rebuild.planner;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.CompiledCandidateSpec;
import appeng.rebuild.pattern.CompiledInputSpec;
import appeng.rebuild.pattern.CompiledOutputSpec;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.quantity.AEAmount;

/**
 * Immutable, exact algebraic expansion of a simple productive pattern ring.
 *
 * <p>
 * This is a pure template: it contains no inventory observations, world references, mutable cache, or execution state.
 * Phase 5 integration may turn it into a {@link PlannedCycleBatch} only after it has acquired each external requirement
 * transactionally.
 */
public record ProductiveCycleTemplate(PlannedBatchCause cause, KeyId seedKey, AEAmount seedAmount,
        AEAmount gainPerTurn, AEAmount repetitions, List<Member> members, List<InternalLink> internalLinks,
        Map<KeyId, AEAmount> outputCreditsPerTurn) {

    public ProductiveCycleTemplate {
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(seedKey, "seedKey");
        requirePositive(seedAmount, "seedAmount");
        requirePositive(gainPerTurn, "gainPerTurn");
        requirePositive(repetitions, "repetitions");
        Objects.requireNonNull(members, "members");
        if (members.isEmpty() || members.size() > PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS) {
            throw new IllegalArgumentException("Productive cycle has an invalid member count");
        }
        members = List.copyOf(members);
        Objects.requireNonNull(internalLinks, "internalLinks");
        if (internalLinks.size() != members.size()) {
            throw new IllegalArgumentException("Productive cycle needs one internal link per member");
        }
        internalLinks = List.copyOf(internalLinks);
        outputCreditsPerTurn = copyCredits(outputCreditsPerTurn);
        validate(cause, seedKey, seedAmount, gainPerTurn, repetitions, members, internalLinks, outputCreditsPerTurn);
    }

    /** One compiled pattern and its normalized exact number of executions in one algebraic turn. */
    public record Member(CompiledPattern pattern, AEAmount executionsPerTurn,
            List<ExternalInputRequirement> externalInputsPerTurn, List<Output> outputsPerTurn) {
        public Member {
            Objects.requireNonNull(pattern, "pattern");
            requirePositive(executionsPerTurn, "executionsPerTurn");
            Objects.requireNonNull(externalInputsPerTurn, "externalInputsPerTurn");
            if (externalInputsPerTurn.size() >= PatternLimits.MAX_INPUT_GROUPS) {
                throw new IllegalArgumentException("Cycle member has too many external inputs");
            }
            int previousInput = -1;
            for (ExternalInputRequirement requirement : externalInputsPerTurn) {
                Objects.requireNonNull(requirement, "externalInputsPerTurn cannot contain null");
                if (requirement.inputIndex() <= previousInput) {
                    throw new IllegalArgumentException("Cycle external inputs must have unique ordered input indices");
                }
                previousInput = requirement.inputIndex();
            }
            externalInputsPerTurn = List.copyOf(externalInputsPerTurn);
            Objects.requireNonNull(outputsPerTurn, "outputsPerTurn");
            if (outputsPerTurn.size() > PatternLimits.MAX_OUTPUTS) {
                throw new IllegalArgumentException("Cycle member has too many outputs");
            }
            int previousOutput = -1;
            for (Output output : outputsPerTurn) {
                Objects.requireNonNull(output, "outputsPerTurn cannot contain null");
                if (output.outputIndex() <= previousOutput) {
                    throw new IllegalArgumentException("Cycle outputs must have unique ordered output indices");
                }
                previousOutput = output.outputIndex();
            }
            outputsPerTurn = List.copyOf(outputsPerTurn);
        }
    }

    /**
     * One non-ring input group retained without selecting a candidate.
     *
     * <p>
     * Integration must run normal B2 candidate allocation (including its bounded remainder-reuse rule) against this
     * complete immutable group; cycle algebra does not preselect a candidate or acquire inventory.
     */
    public record ExternalInputRequirement(int memberIndex, int inputIndex, CompiledInputSpec input,
            AEAmount templateUnitsPerTurn) {
        public ExternalInputRequirement {
            if (memberIndex < 0 || memberIndex >= PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS || inputIndex < 0
                    || inputIndex >= PatternLimits.MAX_INPUT_GROUPS) {
                throw new IllegalArgumentException("External input index is out of bounds");
            }
            Objects.requireNonNull(input, "input");
            requirePositive(templateUnitsPerTurn, "templateUnitsPerTurn");
        }
    }

    /** Exact aggregated output from one compiled output slot in one normalized turn. */
    public record Output(int outputIndex, KeyId key, AEAmount amountPerTurn) {
        public Output {
            if (outputIndex < 0 || outputIndex >= PatternLimits.MAX_OUTPUTS) {
                throw new IllegalArgumentException("Output index is out of bounds");
            }
            Objects.requireNonNull(key, "key");
            requirePositive(amountPerTurn, "amountPerTurn");
        }
    }

    /** A selected output-to-input transfer, including the (possibly larger) closing output. */
    public record InternalLink(int producerMemberIndex, int outputIndex, int consumerMemberIndex, int inputIndex,
            int candidateIndex, KeyId key, AEAmount producedAmountPerTurn, AEAmount consumedAmountPerTurn) {
        public InternalLink {
            if (producerMemberIndex < 0 || producerMemberIndex >= PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS
                    || consumerMemberIndex < 0 || consumerMemberIndex >= PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS
                    || outputIndex < 0 || outputIndex >= PatternLimits.MAX_OUTPUTS || inputIndex < 0
                    || inputIndex >= PatternLimits.MAX_INPUT_GROUPS || candidateIndex < 0
                    || candidateIndex >= PatternLimits.MAX_CANDIDATES_PER_INPUT) {
                throw new IllegalArgumentException("Internal link index is out of bounds");
            }
            Objects.requireNonNull(key, "key");
            requirePositive(producedAmountPerTurn, "producedAmountPerTurn");
            requirePositive(consumedAmountPerTurn, "consumedAmountPerTurn");
            if (producedAmountPerTurn.compareTo(consumedAmountPerTurn) < 0) {
                throw new IllegalArgumentException("Internal link output cannot be smaller than consumption");
            }
        }
    }

    private static void requirePositive(AEAmount amount, String name) {
        PlannerAmounts.requireWithinLimit(Objects.requireNonNull(amount, name), name);
        if (amount.equals(AEAmount.ZERO)) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static Map<KeyId, AEAmount> copyCredits(Map<KeyId, AEAmount> credits) {
        Objects.requireNonNull(credits, "outputCreditsPerTurn");
        if (credits.size() > PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS) {
            throw new IllegalArgumentException("Too many output credits");
        }
        TreeMap<KeyId, AEAmount> copy = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (Map.Entry<KeyId, AEAmount> entry : credits.entrySet()) {
            KeyId key = Objects.requireNonNull(entry.getKey(), "output credit key");
            AEAmount amount = Objects.requireNonNull(entry.getValue(), "output credit amount");
            requirePositive(amount, "output credit amount");
            copy.put(key, amount);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static void validate(PlannedBatchCause cause, KeyId seedKey, AEAmount seedAmount, AEAmount gainPerTurn,
            AEAmount repetitions, List<Member> members, List<InternalLink> links, Map<KeyId, AEAmount> credits) {
        Set<PatternId> patterns = new HashSet<>();
        Set<KeyId> internalKeys = new HashSet<>();
        long generation = -1L;
        TreeMap<KeyId, AEAmount> derivedCredits = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
            Member member = Objects.requireNonNull(members.get(memberIndex), "members cannot contain null");
            CompiledPattern pattern = member.pattern();
            if (!patterns.add(pattern.id())) {
                throw new IllegalArgumentException("Productive cycle members must have unique patterns");
            }
            if (generation == -1L) {
                generation = pattern.keyRegistryGeneration();
            } else if (generation != pattern.keyRegistryGeneration()) {
                throw new IllegalArgumentException("Productive cycle members must share a key-registry generation");
            }
            validateOutputs(member, derivedCredits);
            validateExternalInputs(memberIndex, member, links.get((memberIndex - 1 + members.size()) % members.size()));
        }
        for (int index = 0; index < links.size(); index++) {
            InternalLink link = Objects.requireNonNull(links.get(index), "internalLinks cannot contain null");
            int consumerIndex = (index + 1) % members.size();
            if (link.producerMemberIndex() != index || link.consumerMemberIndex() != consumerIndex) {
                throw new IllegalArgumentException("Internal links must form the ordered ring");
            }
            Member producer = members.get(index);
            Member consumer = members.get(consumerIndex);
            Output output = outputAt(producer, link.outputIndex());
            if (output == null || !output.key().equals(link.key())
                    || !output.amountPerTurn().equals(link.producedAmountPerTurn())) {
                throw new IllegalArgumentException("Internal link must match its producer output");
            }
            CompiledInputSpec input = inputAt(consumer.pattern(), link.inputIndex());
            if (input == null || link.candidateIndex() != 0 || input.candidates().size() != 1) {
                throw new IllegalArgumentException("Internal link must select one exact consumer candidate");
            }
            CompiledCandidateSpec candidate = input.candidates().get(0);
            if (candidate.remainder().isPresent() || !candidate.key().equals(link.key())) {
                throw new IllegalArgumentException("Internal link candidate must be a remainder-free matching key");
            }
            AEAmount expectedConsumption = multiply(multiply(candidate.amountPerTemplate(), input.multiplier(),
                    "internal consumption"), consumer.executionsPerTurn(), "internal consumption");
            if (!expectedConsumption.equals(link.consumedAmountPerTurn()) || !internalKeys.add(link.key())) {
                throw new IllegalArgumentException("Internal link consumption or key uniqueness is invalid");
            }
            if (index < links.size() - 1 && !link.producedAmountPerTurn().equals(link.consumedAmountPerTurn())) {
                throw new IllegalArgumentException("Non-closing internal links must balance exactly");
            }
        }
        InternalLink closing = links.get(links.size() - 1);
        if (!closing.key().equals(seedKey) || !closing.consumedAmountPerTurn().equals(seedAmount)
                || closing.producedAmountPerTurn().compareTo(seedAmount) < 0
                || !closing.producedAmountPerTurn().subtractExact(seedAmount).equals(gainPerTurn)) {
            throw new IllegalArgumentException("Closing internal link must restore the exact seed and gain");
        }
        if (!derivedCredits.equals(credits)) {
            throw new IllegalArgumentException("Output credits must equal all compiled outputs per turn");
        }
        validateNoHiddenInternalChannels(members, links, internalKeys);
        validateCause(cause, seedKey, seedAmount, gainPerTurn, repetitions);
    }

    private static void validateOutputs(Member member, Map<KeyId, AEAmount> credits) {
        List<CompiledOutputSpec> compiled = member.pattern().outputs();
        if (member.outputsPerTurn().size() != compiled.size()) {
            throw new IllegalArgumentException("Cycle member must retain every compiled output");
        }
        int previous = -1;
        for (Output output : member.outputsPerTurn()) {
            Objects.requireNonNull(output, "outputsPerTurn cannot contain null");
            if (output.outputIndex() <= previous || output.outputIndex() >= compiled.size()) {
                throw new IllegalArgumentException("Cycle outputs must be strictly ordered compiled output slots");
            }
            CompiledOutputSpec source = compiled.get(output.outputIndex());
            AEAmount expected = multiply(source.amountPerExecution(), member.executionsPerTurn(), "cycle output");
            if (!source.key().equals(output.key()) || !expected.equals(output.amountPerTurn())) {
                throw new IllegalArgumentException("Cycle output does not match its compiled output slot");
            }
            AEAmount current = credits.get(output.key());
            credits.put(output.key(), current == null ? output.amountPerTurn()
                    : add(current, output.amountPerTurn(), "cycle output credit"));
            previous = output.outputIndex();
        }
    }

    private static void validateExternalInputs(int memberIndex, Member member, InternalLink incoming) {
        int internalInputIndex = incoming.inputIndex();
        int expectedCount = member.pattern().inputs().size() - 1;
        if (member.externalInputsPerTurn().size() != expectedCount) {
            throw new IllegalArgumentException("Cycle member must retain every non-ring input group");
        }
        int previous = -1;
        for (ExternalInputRequirement requirement : member.externalInputsPerTurn()) {
            Objects.requireNonNull(requirement, "externalInputsPerTurn cannot contain null");
            if (requirement.memberIndex() != memberIndex || requirement.inputIndex() <= previous
                    || requirement.inputIndex() == internalInputIndex
                    || requirement.inputIndex() >= member.pattern().inputs().size()) {
                throw new IllegalArgumentException("External inputs must be strictly ordered non-ring input groups");
            }
            CompiledInputSpec source = member.pattern().inputs().get(requirement.inputIndex());
            AEAmount expected = multiply(source.multiplier(), member.executionsPerTurn(), "external template units");
            if (!source.equals(requirement.input()) || !expected.equals(requirement.templateUnitsPerTurn())) {
                throw new IllegalArgumentException(
                        "External input must retain its compiled group and exact template units");
            }
            previous = requirement.inputIndex();
        }
    }

    private static Output outputAt(Member member, int outputIndex) {
        for (Output output : member.outputsPerTurn()) {
            if (output.outputIndex() == outputIndex) {
                return output;
            }
        }
        return null;
    }

    private static void validateNoHiddenInternalChannels(List<Member> members, List<InternalLink> links,
            Set<KeyId> internalKeys) {
        for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
            Member member = members.get(memberIndex);
            int internalInputIndex = links.get((memberIndex - 1 + members.size()) % members.size()).inputIndex();
            int supplyingOutputIndex = links.get(memberIndex).outputIndex();
            for (int inputIndex = 0; inputIndex < member.pattern().inputs().size(); inputIndex++) {
                if (inputIndex == internalInputIndex) {
                    continue;
                }
                for (CompiledCandidateSpec candidate : member.pattern().inputs().get(inputIndex).candidates()) {
                    if (internalKeys.contains(candidate.key()) || candidate.remainder().isPresent()
                            && internalKeys.contains(candidate.remainder().get().key())) {
                        throw new IllegalArgumentException("Cycle contains an undeclared internal input channel");
                    }
                }
            }
            for (int outputIndex = 0; outputIndex < member.pattern().outputs().size(); outputIndex++) {
                if (outputIndex != supplyingOutputIndex
                        && internalKeys.contains(member.pattern().outputs().get(outputIndex).key())) {
                    throw new IllegalArgumentException("Cycle contains an undeclared internal output channel");
                }
            }
        }
    }

    private static CompiledInputSpec inputAt(CompiledPattern pattern, int inputIndex) {
        return inputIndex >= 0 && inputIndex < pattern.inputs().size() ? pattern.inputs().get(inputIndex) : null;
    }

    private static void validateCause(PlannedBatchCause cause, KeyId seedKey, AEAmount seedAmount, AEAmount gain,
            AEAmount repetitions) {
        AEAmount demand;
        boolean root;
        if (cause instanceof PlannedBatchCause.Root value) {
            if (!value.output().equals(seedKey)) {
                throw new IllegalArgumentException("Cycle cause must demand the seed key");
            }
            demand = value.demandedAmount();
            root = true;
        } else if (cause instanceof PlannedBatchCause.Input value) {
            if (!value.key().equals(seedKey)) {
                throw new IllegalArgumentException("Cycle cause must demand the seed key");
            }
            demand = value.demandedAmount();
            root = false;
        } else {
            throw new IllegalArgumentException("Unsupported cycle cause");
        }
        AEAmount required = root ? demand
                : demand.compareTo(seedAmount) > 0 ? demand.subtractExact(seedAmount) : AEAmount.ZERO;
        AEAmount expected = required.ceilDiv(gain);
        if (!root && expected.equals(AEAmount.ZERO)) {
            expected = AEAmount.ONE;
        }
        if (!expected.equals(repetitions)) {
            throw new IllegalArgumentException("Cycle repetitions do not satisfy the exact causal demand");
        }
    }

    private static AEAmount multiply(AEAmount left, AEAmount right, String name) {
        return PlannerAmounts.checkedMultiply(left, right, name);
    }

    private static AEAmount add(AEAmount left, AEAmount right, String name) {
        return PlannerAmounts.checkedAdd(left, right, name);
    }
}

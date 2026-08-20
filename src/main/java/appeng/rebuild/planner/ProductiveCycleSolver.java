package appeng.rebuild.planner;

import java.math.BigInteger;
import java.util.ArrayList;
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
 * Pure bounded algebra for a simple productive ring.
 *
 * <p>
 * It intentionally neither discovers active paths nor mutates a craft draft. Every supplied pattern is inspected at
 * most once per bounded member/input/output slot, and all arithmetic is rejected before it crosses the planner's
 * 65,536-bit exact-quantity ceiling.
 */
public final class ProductiveCycleSolver {
    /** Solves one causally-demanded ring. The cause must demand the closing/seed key. */
    public ProductiveCycleSolveResult solve(ProductiveCycleRing ring, PlannedBatchCause cause) {
        Objects.requireNonNull(ring, "ring");
        Objects.requireNonNull(cause, "cause");
        try {
            return solveChecked(ring, cause);
        } catch (Abort abort) {
            return new ProductiveCycleSolveResult.Failure(abort.reason);
        }
    }

    private ProductiveCycleSolveResult solveChecked(ProductiveCycleRing ring, PlannedBatchCause cause) {
        Work work = new Work();
        List<ProductiveCycleRingMember> members = ring.members();
        if (members.size() > PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS) {
            return new ProductiveCycleSolveResult.Failure(ProductiveCycleSolveResult.FailureReason.WORK_LIMIT);
        }
        if (members.isEmpty()) {
            return malformed();
        }

        List<PreparedMember> prepared = new ArrayList<>(members.size());
        Set<PatternId> patternIds = new HashSet<>();
        Set<KeyId> internalKeys = new HashSet<>();
        long generation = -1L;
        for (int index = 0; index < members.size(); index++) {
            work.tick();
            ProductiveCycleRingMember member = members.get(index);
            if (member == null) {
                return malformed();
            }
            CompiledPattern pattern = member.pattern();
            if (!patternIds.add(pattern.id())) {
                return malformed();
            }
            if (generation == -1L) {
                generation = pattern.keyRegistryGeneration();
            } else if (generation != pattern.keyRegistryGeneration()) {
                return malformed();
            }
            if (member.internalInputIndex() < 0 || member.internalInputIndex() >= pattern.inputs().size()
                    || member.supplyingOutputIndex() < 0 || member.supplyingOutputIndex() >= pattern.outputs().size()) {
                return malformed();
            }
            CompiledInputSpec input = pattern.inputs().get(member.internalInputIndex());
            if (member.internalCandidateIndex() != 0 || input.candidates().size() != 1
                    || input.candidates().size() > PatternLimits.MAX_CANDIDATES_PER_INPUT) {
                return malformed();
            }
            CompiledCandidateSpec candidate = input.candidates().get(0);
            if (candidate.remainder().isPresent()) {
                return malformed();
            }
            BigInteger consumption = multiply(candidate.amountPerTemplate(), input.multiplier(), work);
            if (consumption.signum() <= 0 || !internalKeys.add(candidate.key())) {
                return malformed();
            }
            CompiledOutputSpec output = pattern.outputs().get(member.supplyingOutputIndex());
            if (output.amountPerExecution().equals(AEAmount.ZERO)) {
                return malformed();
            }
            prepared.add(new PreparedMember(member, pattern, candidate, consumption, output));
        }

        for (int index = 0; index < prepared.size(); index++) {
            work.tick();
            PreparedMember current = prepared.get(index);
            PreparedMember next = prepared.get((index + 1) % prepared.size());
            if (!current.linkOutput.key().equals(next.internalCandidate.key())) {
                return malformed();
            }
        }
        if (!hasOnlyDeclaredInternalChannels(prepared, internalKeys, work)) {
            return malformed();
        }

        List<BigInteger> executions = normalizedExecutions(prepared, work);
        List<BigInteger> consumed = new ArrayList<>(prepared.size());
        List<BigInteger> produced = new ArrayList<>(prepared.size());
        for (int index = 0; index < prepared.size(); index++) {
            work.tick();
            PreparedMember member = prepared.get(index);
            BigInteger x = executions.get(index);
            BigInteger consumedAmount = multiply(member.internalConsumption, x, work);
            BigInteger producedAmount = multiply(member.linkOutput.amountPerExecution(), x, work);
            consumed.add(consumedAmount);
            produced.add(producedAmount);
        }
        for (int index = 0; index < prepared.size() - 1; index++) {
            work.tick();
            if (!produced.get(index).equals(consumed.get(index + 1))) {
                return malformed();
            }
        }

        BigInteger seed = consumed.get(0);
        BigInteger closingOutput = produced.get(produced.size() - 1);
        if (closingOutput.compareTo(seed) < 0) {
            return malformed();
        }
        BigInteger gain = checked(closingOutput.subtract(seed), work);
        if (gain.signum() <= 0) {
            return new ProductiveCycleSolveResult.Failure(ProductiveCycleSolveResult.FailureReason.NON_PRODUCTIVE);
        }
        KeyId seedKey = prepared.get(0).internalCandidate.key();
        Demand demand = demand(cause);
        if (!seedKey.equals(demand.key)) {
            return malformed();
        }
        BigInteger repetitions = repetitions(demand, seed, gain, work);

        return new ProductiveCycleSolveResult.Success(template(cause, prepared, executions, consumed, produced, seedKey,
                seed, gain, repetitions, work));
    }

    private static List<BigInteger> normalizedExecutions(List<PreparedMember> members, Work work) {
        List<BigInteger> prefixes = new ArrayList<>(members.size());
        List<BigInteger> suffixes = new ArrayList<>(members.size());
        prefixes.add(BigInteger.ONE);
        for (int index = 1; index < members.size(); index++) {
            prefixes.add(multiply(prefixes.get(index - 1), members.get(index - 1).linkOutput.amountPerExecution(),
                    work));
        }
        for (int index = 0; index < members.size(); index++) {
            suffixes.add(BigInteger.ONE);
        }
        for (int index = members.size() - 2; index >= 0; index--) {
            suffixes.set(index, multiply(suffixes.get(index + 1), members.get(index + 1).internalConsumption, work));
        }
        List<BigInteger> executions = new ArrayList<>(members.size());
        for (int index = 0; index < members.size(); index++) {
            executions.add(multiply(prefixes.get(index), suffixes.get(index), work));
        }
        BigInteger divisor = executions.get(0);
        for (int index = 1; index < executions.size(); index++) {
            work.tick();
            divisor = divisor.gcd(executions.get(index));
        }
        if (divisor.signum() <= 0) {
            throw new Abort(ProductiveCycleSolveResult.FailureReason.MALFORMED_RING);
        }
        for (int index = 0; index < executions.size(); index++) {
            executions.set(index, executions.get(index).divide(divisor));
        }
        return List.copyOf(executions);
    }

    private static boolean hasOnlyDeclaredInternalChannels(List<PreparedMember> members, Set<KeyId> internalKeys,
            Work work) {
        for (PreparedMember member : members) {
            for (int inputIndex = 0; inputIndex < member.pattern.inputs().size(); inputIndex++) {
                work.tick();
                if (inputIndex == member.ring.internalInputIndex()) {
                    continue;
                }
                for (CompiledCandidateSpec candidate : member.pattern.inputs().get(inputIndex).candidates()) {
                    work.tick();
                    if (internalKeys.contains(candidate.key()) || candidate.remainder().isPresent()
                            && internalKeys.contains(candidate.remainder().get().key())) {
                        return false;
                    }
                }
            }
            for (int outputIndex = 0; outputIndex < member.pattern.outputs().size(); outputIndex++) {
                work.tick();
                if (outputIndex != member.ring.supplyingOutputIndex()
                        && internalKeys.contains(member.pattern.outputs().get(outputIndex).key())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Demand demand(PlannedBatchCause cause) {
        if (cause instanceof PlannedBatchCause.Root root) {
            return new Demand(root.output(), root.demandedAmount(), true);
        }
        if (cause instanceof PlannedBatchCause.Input input) {
            return new Demand(input.key(), input.demandedAmount(), false);
        }
        throw new IllegalStateException("Unsupported planned batch cause");
    }

    private static BigInteger repetitions(Demand demand, BigInteger seed, BigInteger gain, Work work) {
        BigInteger requested = checked(demand.amount.toBigInteger(), work);
        BigInteger required;
        if (demand.root) {
            required = requested;
        } else {
            required = checked(requested.subtract(seed).max(BigInteger.ZERO), work);
        }
        BigInteger repetitions = ceilDiv(required, gain);
        if (!demand.root && repetitions.signum() == 0) {
            repetitions = BigInteger.ONE;
        }
        return checked(repetitions, work);
    }

    private static ProductiveCycleTemplate template(PlannedBatchCause cause, List<PreparedMember> prepared,
            List<BigInteger> executions, List<BigInteger> consumed, List<BigInteger> produced, KeyId seedKey,
            BigInteger seed, BigInteger gain, BigInteger repetitions, Work work) {
        List<ProductiveCycleTemplate.Member> members = new ArrayList<>(prepared.size());
        List<ProductiveCycleTemplate.InternalLink> links = new ArrayList<>(prepared.size());
        TreeMap<KeyId, AEAmount> credits = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (int memberIndex = 0; memberIndex < prepared.size(); memberIndex++) {
            work.tick();
            PreparedMember member = prepared.get(memberIndex);
            BigInteger executionsPerTurn = executions.get(memberIndex);
            List<ProductiveCycleTemplate.ExternalInputRequirement> external = externalInputs(memberIndex, member,
                    executionsPerTurn, work);
            List<ProductiveCycleTemplate.Output> outputs = outputs(member, executionsPerTurn, credits, work);
            members.add(
                    new ProductiveCycleTemplate.Member(member.pattern, amount(executionsPerTurn), external, outputs));
            int consumerIndex = (memberIndex + 1) % prepared.size();
            links.add(new ProductiveCycleTemplate.InternalLink(memberIndex, member.ring.supplyingOutputIndex(),
                    consumerIndex, prepared.get(consumerIndex).ring.internalInputIndex(),
                    prepared.get(consumerIndex).ring.internalCandidateIndex(), member.linkOutput.key(),
                    amount(produced.get(memberIndex)), amount(consumed.get(consumerIndex))));
        }
        return new ProductiveCycleTemplate(cause, seedKey, amount(seed), amount(gain), amount(repetitions), members,
                links, credits);
    }

    private static List<ProductiveCycleTemplate.ExternalInputRequirement> externalInputs(int memberIndex,
            PreparedMember member, BigInteger executions, Work work) {
        List<ProductiveCycleTemplate.ExternalInputRequirement> inputs = new ArrayList<>();
        for (int inputIndex = 0; inputIndex < member.pattern.inputs().size(); inputIndex++) {
            work.tick();
            if (inputIndex == member.ring.internalInputIndex()) {
                continue;
            }
            CompiledInputSpec input = member.pattern.inputs().get(inputIndex);
            BigInteger units = multiply(input.multiplier(), executions, work);
            inputs.add(new ProductiveCycleTemplate.ExternalInputRequirement(memberIndex, inputIndex, input,
                    amount(units)));
        }
        return List.copyOf(inputs);
    }

    private static List<ProductiveCycleTemplate.Output> outputs(PreparedMember member, BigInteger executions,
            Map<KeyId, AEAmount> credits, Work work) {
        List<ProductiveCycleTemplate.Output> outputs = new ArrayList<>(member.pattern.outputs().size());
        for (int outputIndex = 0; outputIndex < member.pattern.outputs().size(); outputIndex++) {
            work.tick();
            CompiledOutputSpec output = member.pattern.outputs().get(outputIndex);
            AEAmount amount = amount(multiply(output.amountPerExecution(), executions, work));
            outputs.add(new ProductiveCycleTemplate.Output(outputIndex, output.key(), amount));
            AEAmount previous = credits.get(output.key());
            credits.put(output.key(),
                    previous == null ? amount : amount(add(previous.toBigInteger(), amount.toBigInteger(), work)));
        }
        return List.copyOf(outputs);
    }

    private static BigInteger multiply(AEAmount left, AEAmount right, Work work) {
        return multiply(left.toBigInteger(), right.toBigInteger(), work);
    }

    private static BigInteger multiply(AEAmount left, BigInteger right, Work work) {
        return multiply(left.toBigInteger(), right, work);
    }

    private static BigInteger multiply(BigInteger left, AEAmount right, Work work) {
        return multiply(left, right.toBigInteger(), work);
    }

    private static BigInteger multiply(BigInteger left, BigInteger right, Work work) {
        work.tick();
        return checked(left.multiply(right), work);
    }

    private static BigInteger add(BigInteger left, BigInteger right, Work work) {
        work.tick();
        return checked(left.add(right), work);
    }

    private static BigInteger checked(BigInteger value, Work work) {
        work.tick();
        if (value.signum() < 0 || value.bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
            throw new Abort(ProductiveCycleSolveResult.FailureReason.QUANTITY_LIMIT);
        }
        return value;
    }

    private static AEAmount amount(BigInteger value) {
        return AEAmount.of(value);
    }

    private static BigInteger ceilDiv(BigInteger value, BigInteger divisor) {
        if (value.signum() == 0) {
            return BigInteger.ZERO;
        }
        return value.subtract(BigInteger.ONE).divide(divisor).add(BigInteger.ONE);
    }

    private static ProductiveCycleSolveResult malformed() {
        return new ProductiveCycleSolveResult.Failure(ProductiveCycleSolveResult.FailureReason.MALFORMED_RING);
    }

    private record PreparedMember(ProductiveCycleRingMember ring, CompiledPattern pattern,
            CompiledCandidateSpec internalCandidate, BigInteger internalConsumption, CompiledOutputSpec linkOutput) {
    }

    private record Demand(KeyId key, AEAmount amount, boolean root) {
    }

    private static final class Work {
        private int attempts;

        private void tick() {
            if (attempts == PlannerLimits.MAX_PRODUCTIVE_CYCLE_ATTEMPTS) {
                throw new Abort(ProductiveCycleSolveResult.FailureReason.WORK_LIMIT);
            }
            attempts++;
        }
    }

    private static final class Abort extends RuntimeException {
        private final ProductiveCycleSolveResult.FailureReason reason;

        private Abort(ProductiveCycleSolveResult.FailureReason reason) {
            this.reason = reason;
        }
    }
}

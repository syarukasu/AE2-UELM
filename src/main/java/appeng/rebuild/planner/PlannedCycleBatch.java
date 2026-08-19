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
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.quantity.AEAmount;

/**
 * Immutable compact representation of a bounded productive cycle.
 *
 * <p>
 * The derived credits must satisfy this step's causal demand. A root demand for the seed key excludes the retained seed
 * from its crafted output, while an input demand may legitimately consume that returned seed downstream. Either form
 * must still reject a neutral ring that only restores its seed.
 */
public record PlannedCycleBatch(PlannedBatchId id, PlannedBatchCause cause, AEAmount repetitions, KeyId seedKey,
        AEAmount seedAmount, List<PlannedCycleMember> members, List<PlannedCycleLink> links,
        Map<KeyId, AEAmount> finalCredits) implements PlannedCausalStep {

    public PlannedCycleBatch {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cause, "cause");
        PlannedCycleMember.requirePositive(repetitions, "repetitions");
        Objects.requireNonNull(seedKey, "seedKey");
        PlannedCycleMember.requirePositive(seedAmount, "seedAmount");
        Objects.requireNonNull(members, "members");
        if (members.isEmpty() || members.size() > PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS) {
            throw new IllegalArgumentException("Planned cycle must contain between one and "
                    + PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS + " members");
        }
        members = List.copyOf(members);
        Set<PatternId> patterns = new HashSet<>();
        for (PlannedCycleMember member : members) {
            Objects.requireNonNull(member, "members cannot contain null");
            if (!patterns.add(member.patternId())) {
                throw new IllegalArgumentException("Planned cycle members must have unique patterns");
            }
        }
        Objects.requireNonNull(links, "links");
        if (links.size() != members.size()) {
            throw new IllegalArgumentException("Planned cycle must have exactly one ordered ring link per member");
        }
        links = List.copyOf(links);
        validateLinks(members, links, seedKey, seedAmount);
        finalCredits = copyCredits(finalCredits);
        Map<KeyId, AEAmount> expectedCredits = deriveFinalCredits(members, links, repetitions, seedKey, seedAmount);
        if (!finalCredits.equals(expectedCredits)) {
            throw new IllegalArgumentException("Planned cycle final credits must equal the derived exact credits");
        }
        validateDemandProductivity(cause, seedKey, seedAmount, finalCredits);
    }

    /**
     * Rejects neutral rings before publication. A retained seed only proves that the ring closes. For a root demand it
     * is excluded from the crafted output; for an input demand it remains legitimate downstream input material.
     */
    private static void validateDemandProductivity(PlannedBatchCause cause, KeyId seedKey, AEAmount seedAmount,
            Map<KeyId, AEAmount> finalCredits) {
        KeyId demandedKey;
        AEAmount demandedAmount;
        boolean rootCause;
        if (cause instanceof PlannedBatchCause.Root root) {
            demandedKey = root.output();
            demandedAmount = root.demandedAmount();
            rootCause = true;
        } else if (cause instanceof PlannedBatchCause.Input input) {
            demandedKey = input.key();
            demandedAmount = input.demandedAmount();
            rootCause = false;
        } else {
            throw new IllegalArgumentException("Unsupported planned cycle cause");
        }

        AEAmount creditedAmount = finalCredits.get(demandedKey);
        if (creditedAmount == null || creditedAmount.compareTo(demandedAmount) < 0) {
            throw new IllegalArgumentException("Planned cycle final credits must satisfy its causal demand");
        }
        if (rootCause && demandedKey.equals(seedKey)) {
            AEAmount requiredCredit = PlannerAmounts.checkedAdd(seedAmount, demandedAmount,
                    "root cycle seed demand");
            if (creditedAmount.compareTo(requiredCredit) < 0) {
                throw new IllegalArgumentException(
                        "Planned root cycle demand for its seed must exclude the retained seed credit");
            }
        } else if (demandedKey.equals(seedKey) && creditedAmount.compareTo(seedAmount) <= 0) {
            throw new IllegalArgumentException(
                    "Planned cycle demand for its seed must exceed the retained seed credit");
        }
    }

    private static void validateLinks(List<PlannedCycleMember> members, List<PlannedCycleLink> links, KeyId seedKey,
            AEAmount seedAmount) {
        Set<CycleLinkIdentity> seenSelections = new HashSet<>();
        Set<KeyId> seenKeys = new HashSet<>();
        for (int index = 0; index < links.size(); index++) {
            PlannedCycleLink link = Objects.requireNonNull(links.get(index), "links cannot contain null");
            int expectedConsumer = (index + 1) % members.size();
            if (link.producerMemberIndex() != index || link.consumerMemberIndex() != expectedConsumer) {
                throw new IllegalArgumentException("Planned cycle links must form an ordered ring");
            }
            if (link.producerMemberIndex() >= members.size() || link.consumerMemberIndex() >= members.size()) {
                throw new IllegalArgumentException("Planned cycle link member index is out of range");
            }
            PlannedCycleMember consumer = members.get(link.consumerMemberIndex());
            PlannedInputSelection selected = findOnlyInput(consumer, link.inputIndex(), link.candidateIndex());
            if (selected == null || !selected.consumedKey().equals(link.key())
                    || !selected.grossConsumedAmount().equals(link.amountPerTurn())) {
                throw new IllegalArgumentException("Planned cycle link must match a consumer input selection");
            }
            if (!selected.initialRequiredAmount().equals(selected.grossConsumedAmount())
                    || selected.remainderReturn().isPresent()) {
                throw new IllegalArgumentException("Planned cycle links cannot use an input shortcut or remainder");
            }
            if (!seenSelections.add(new CycleLinkIdentity(link.consumerMemberIndex(), link.inputIndex(),
                    link.candidateIndex()))) {
                throw new IllegalArgumentException("Planned cycle links must have unique consumer selections");
            }
            if (!seenKeys.add(link.key())) {
                throw new IllegalArgumentException("Planned cycle links must have unique internal keys");
            }
            PlannedCycleOutput producerOutput = findOutput(members.get(link.producerMemberIndex()), link.outputIndex());
            if (producerOutput == null || !producerOutput.key().equals(link.key())
                    || producerOutput.amountPerTurn().compareTo(link.amountPerTurn()) < 0) {
                throw new IllegalArgumentException("Planned cycle link must be supplied by its producer output");
            }
            if (index < links.size() - 1 && !producerOutput.amountPerTurn().equals(link.amountPerTurn())) {
                throw new IllegalArgumentException(
                        "Non-closing ring links must transfer their complete producer output");
            }
        }
        PlannedCycleLink closing = links.get(links.size() - 1);
        if (!closing.key().equals(seedKey) || !closing.amountPerTurn().equals(seedAmount)) {
            throw new IllegalArgumentException("Planned cycle closing link must restore the seed");
        }
    }

    private static PlannedInputSelection findOnlyInput(PlannedCycleMember member, int inputIndex, int candidateIndex) {
        PlannedInputSelection matched = null;
        for (PlannedInputSelection input : member.inputsPerTurn()) {
            if (input.inputIndex() == inputIndex) {
                if (input.candidateIndex() != candidateIndex || matched != null) {
                    return null;
                }
                matched = input;
            }
        }
        return matched;
    }

    private static PlannedCycleOutput findOutput(PlannedCycleMember member, int outputIndex) {
        for (PlannedCycleOutput output : member.outputsPerTurn()) {
            if (output.outputIndex() == outputIndex) {
                return output;
            }
        }
        return null;
    }

    private static Map<KeyId, AEAmount> deriveFinalCredits(List<PlannedCycleMember> members,
            List<PlannedCycleLink> links, AEAmount repetitions, KeyId seedKey, AEAmount seedAmount) {
        TreeMap<KeyId, AEAmount> perTurn = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (PlannedCycleMember member : members) {
            for (PlannedCycleOutput output : member.outputsPerTurn()) {
                addCredit(perTurn, output.key(), output.amountPerTurn(), "cycle output credit");
            }
            for (PlannedInputSelection input : member.inputsPerTurn()) {
                input.remainderReturn().ifPresent(remainder -> addCredit(perTurn, remainder.key(), remainder.amount(),
                        "cycle remainder credit"));
            }
        }
        for (PlannedCycleLink link : links) {
            subtractCredit(perTurn, link.key(), link.amountPerTurn());
        }

        TreeMap<KeyId, AEAmount> result = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (Map.Entry<KeyId, AEAmount> entry : perTurn.entrySet()) {
            if (!entry.getValue().equals(AEAmount.ZERO)) {
                result.put(entry.getKey(), PlannerAmounts.checkedMultiply(entry.getValue(), repetitions,
                        "cycle final credit"));
            }
        }
        addCredit(result, seedKey, seedAmount, "cycle seed credit");
        return Collections.unmodifiableMap(result);
    }

    private static void addCredit(Map<KeyId, AEAmount> credits, KeyId key, AEAmount amount, String name) {
        AEAmount existing = credits.get(key);
        credits.put(key, existing == null ? amount : PlannerAmounts.checkedAdd(existing, amount, name));
    }

    private static void subtractCredit(Map<KeyId, AEAmount> credits, KeyId key, AEAmount amount) {
        AEAmount existing = credits.get(key);
        if (existing == null || existing.compareTo(amount) < 0) {
            throw new IllegalArgumentException("Planned cycle link would produce a negative final credit");
        }
        AEAmount remainder = existing.subtractExact(amount);
        if (remainder.equals(AEAmount.ZERO)) {
            credits.remove(key);
        } else {
            credits.put(key, remainder);
        }
    }

    private static Map<KeyId, AEAmount> copyCredits(Map<KeyId, AEAmount> source) {
        Objects.requireNonNull(source, "finalCredits");
        if (source.size() > PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS) {
            throw new IllegalArgumentException("Planned cycle has too many final credits");
        }
        TreeMap<KeyId, AEAmount> result = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (Map.Entry<KeyId, AEAmount> entry : source.entrySet()) {
            KeyId key = Objects.requireNonNull(entry.getKey(), "finalCredits cannot contain null keys");
            AEAmount amount = Objects.requireNonNull(entry.getValue(), "finalCredits cannot contain null amounts");
            PlannedCycleMember.requirePositive(amount, "finalCredits amount");
            result.put(key, amount);
        }
        return Collections.unmodifiableMap(result);
    }

    private record CycleLinkIdentity(int consumerMemberIndex, int inputIndex, int candidateIndex) {
    }
}

package appeng.rebuild.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
import appeng.rebuild.pattern.NormalizedPatternSnapshot;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.storage.StorageSnapshot;

/**
 * Pure, exact and bounded planner over immutable snapshots.
 *
 * <p>
 * Each demand gets at most two deterministic producer passes. A target producer is first attempted whole and then
 * through a bounded descending-power fallback; a second pass is only entered after the first has committed a target
 * reduction. A repeated active producer is considered only as a bounded simple productive ring, then acquired as one
 * transaction rather than by recursive expansion. Input candidates are tried in bounded cyclic rotations, and later
 * input failure can roll a chunk back to rotate an earlier input group. No operation touches world or legacy state.
 */
public final class ExactCraftPlanner {
    public ExactCraftPlanResult plan(ExactCraftRequest request, StorageSnapshot storageSnapshot,
            NormalizedPatternSnapshot patternSnapshot) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(storageSnapshot, "storageSnapshot");
        Objects.requireNonNull(patternSnapshot, "patternSnapshot");
        final GridRevision revision;
        try {
            revision = GridRevision.capture(storageSnapshot, patternSnapshot);
        } catch (IllegalArgumentException invalid) {
            return new ExactCraftPlanResult.Failure(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
        }
        if (request.output().value() >= storageSnapshot.keyCount()) {
            return new ExactCraftPlanResult.Failure(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
        }
        try {
            return new Search(request, storageSnapshot, patternSnapshot, revision).run();
        } catch (Abort abort) {
            return new ExactCraftPlanResult.Failure(abort.reason);
        }
    }

    private static final class Search {
        private final ExactCraftRequest request;
        private final StorageSnapshot storage;
        private final NormalizedPatternSnapshot patterns;
        private final GridRevision gridRevision;
        private final DependencySet.Builder dependencies;
        private final Map<KeyId, AEAmount> storageRemaining = new HashMap<>();
        private final Set<KeyId> loadedStorage = new HashSet<>();
        private final Map<KeyId, AEAmount> produced = new HashMap<>();
        private final Map<KeyId, AEAmount> storageConsumed = new HashMap<>();
        private final Map<PatternId, AEAmount> executions = new HashMap<>();
        private final List<PlannedCausalStep> causalSteps = new ArrayList<>();
        private final List<Undo> undo = new ArrayList<>();
        private final Set<PatternId> activePatterns = new HashSet<>();
        private final ArrayDeque<Frame> frames = new ArrayDeque<>();
        private int decisions;
        private int mutations;
        private long nextBatchId;
        private ExactCraftPlanResult.FailureReason rootFailure;

        private Search(ExactCraftRequest request, StorageSnapshot storage, NormalizedPatternSnapshot patterns,
                GridRevision revision) {
            this.request = request;
            this.storage = storage;
            this.patterns = patterns;
            this.gridRevision = revision;
            this.dependencies = new DependencySet.Builder(revision);
            checked(request.amount());
        }

        private ExactCraftPlanResult run() {
            frames.push(new Frame(request.output(), request.amount(), true, checkpoint(),
                    new PlannedBatchCause.Root(request.output(), request.amount())));
            while (!frames.isEmpty()) {
                if (frames.size() > PlannerLimits.MAX_CRAFT_STACK_DEPTH) {
                    throw new Abort(ExactCraftPlanResult.FailureReason.WORK_LIMIT);
                }
                step(frames.peek());
            }
            if (rootFailure != null) {
                return new ExactCraftPlanResult.Failure(rootFailure);
            }
            return new ExactCraftPlanResult.Success(new ExactCraftPlanDraft(gridRevision, request, executions,
                    storageConsumed, produced, causalSteps, dependencies.build()));
        }

        private void step(Frame frame) {
            switch (frame.phase) {
                case START -> start(frame);
                case NEXT_PRODUCER -> nextProducer(frame);
                case START_CHUNK -> startChunk(frame);
                case NEXT_INPUT -> nextInput(frame);
                case NEXT_CANDIDATE -> nextCandidate(frame);
                case WAIT_CHILD -> handleChild(frame);
                case COMMIT_CHUNK -> commitChunk(frame);
                case CYCLE_NEXT_EXTERNAL -> nextCycleExternal(frame);
                case CYCLE_NEXT_CANDIDATE -> nextCycleCandidate(frame);
                case CYCLE_WAIT_CHILD -> handleCycleChild(frame);
                case CYCLE_PUBLISH -> publishCycle(frame);
            }
        }

        private void start(Frame frame) {
            frame.remaining = frame.root ? consumeProduced(frame.key, frame.required)
                    : consumeAvailable(frame.key, frame.required);
            if (frame.remaining.equals(AEAmount.ZERO)) {
                succeed(frame);
                return;
            }
            frame.producers = producersFor(frame.key);
            if (frame.producers.isEmpty()) {
                fail(frame, ExactCraftPlanResult.FailureReason.NO_PRODUCER);
                return;
            }
            frame.producerPasses = 1;
            frame.passStartRemaining = frame.remaining;
            frame.phase = Phase.NEXT_PRODUCER;
        }

        private void nextProducer(Frame frame) {
            if (frame.remaining.equals(AEAmount.ZERO)) {
                succeed(frame);
                return;
            }
            if (frame.producerIndex >= frame.producers.size()) {
                if (frame.producerPasses < PlannerLimits.MAX_CRAFT_PRODUCER_PASSES
                        && frame.remaining.compareTo(frame.passStartRemaining) < 0) {
                    frame.producerPasses++;
                    frame.producerIndex = 0;
                    frame.passStartRemaining = frame.remaining;
                    return;
                }
                fail(frame, frame.sawCycle && !frame.sawUnsatisfiable
                        ? ExactCraftPlanResult.FailureReason.CYCLE
                        : ExactCraftPlanResult.FailureReason.UNSATISFIABLE_WITHIN_STRATEGY);
                return;
            }
            decision();
            Producer producer = frame.producers.get(frame.producerIndex++);
            if (!activePatterns.add(producer.id)) {
                frame.sawCycle = true;
                beginProductiveCycle(frame, producer);
                return;
            }
            frame.producer = producer;
            frame.outputPerExecution = matchingOutput(producer.pattern, frame.key);
            if (frame.outputPerExecution.equals(AEAmount.ZERO)) {
                throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
            }
            frame.wholeAttempt = ceilDiv(frame.remaining, frame.outputPerExecution);
            frame.chunkSchedule = null; // An eager fallback bound would reject a feasible enormous whole craft.
            frame.chunkIndex = -1;
            frame.phase = Phase.START_CHUNK;
        }

        private void startChunk(Frame frame) {
            AEAmount chunk;
            if (frame.retryChunk) {
                frame.retryChunk = false;
                frame.retryingExistingChunk = true;
                chunk = frame.executions;
            } else if (frame.chunkIndex < 0) {
                frame.retryingExistingChunk = false;
                chunk = frame.wholeAttempt;
                frame.chunkIndex = 0;
            } else {
                frame.retryingExistingChunk = false;
                if (frame.chunkSchedule == null) {
                    activePatterns.remove(frame.producer.id);
                    frame.producer = null;
                    frame.phase = Phase.NEXT_PRODUCER;
                    return;
                }
                AEAmount allowed = ceilDiv(frame.remaining, frame.outputPerExecution);
                while (frame.chunkIndex < frame.chunkSchedule.size()
                        && frame.chunkSchedule.get(frame.chunkIndex).compareTo(allowed) > 0) {
                    frame.chunkIndex++;
                }
                if (frame.chunkIndex >= frame.chunkSchedule.size()) {
                    activePatterns.remove(frame.producer.id);
                    frame.producer = null;
                    frame.phase = Phase.NEXT_PRODUCER;
                    return;
                }
                chunk = frame.chunkSchedule.get(frame.chunkIndex++);
                if (frame.remaining.equals(AEAmount.ZERO)) {
                    activePatterns.remove(frame.producer.id);
                    frame.producer = null;
                    succeed(frame);
                    return;
                }
            }
            if (!frame.retryingExistingChunk) {
                frame.chunkCheckpoint = checkpoint();
                frame.candidateStarts = new int[frame.producer.pattern.inputs().size()];
                frame.rotationCounts = new int[frame.producer.pattern.inputs().size()];
                frame.batchId = reserveBatchId();
            }
            frame.executions = chunk;
            frame.inputIndex = 0;
            frame.selections = new ArrayList<>();
            frame.phase = frame.producer.pattern.inputs().isEmpty() ? Phase.COMMIT_CHUNK : Phase.NEXT_INPUT;
        }

        private void nextInput(Frame frame) {
            if (frame.inputIndex >= frame.producer.pattern.inputs().size()) {
                frame.phase = Phase.COMMIT_CHUNK;
                return;
            }
            CompiledInputSpec input = frame.producer.pattern.inputs().get(frame.inputIndex);
            frame.allocation = new InputAllocation(input, multiply(input.multiplier(), frame.executions),
                    frame.candidateStarts[frame.inputIndex]);
            frame.phase = Phase.NEXT_CANDIDATE;
        }

        private void nextCandidate(Frame frame) {
            InputAllocation allocation = frame.allocation;
            if (allocation.remaining.equals(AEAmount.ZERO)) {
                frame.selections.addAll(allocation.selections(frame.inputIndex, frame.producer.pattern));
                frame.allocation = null;
                frame.inputIndex++;
                frame.phase = Phase.NEXT_INPUT;
                return;
            }
            if (allocation.candidatesTried >= allocation.input.candidates().size()) {
                if (!rotateEarlierInput(frame)) {
                    rejectChunk(frame, frame.sawCycle && !frame.sawUnsatisfiable
                            ? ExactCraftPlanResult.FailureReason.CYCLE
                            : ExactCraftPlanResult.FailureReason.UNSATISFIABLE_WITHIN_STRATEGY);
                }
                return;
            }
            if (!allocation.attemptingCandidate) {
                allocation.beginCandidate();
            }
            AEAmount units = allocation.nextAttemptUnits();
            if (units == null) {
                allocation.finishCandidate();
                return;
            }
            decision();
            CompiledCandidateSpec candidate = allocation.candidate();
            AEAmount initial = allocation.initialRequirement(candidate, units, frame.producer.pattern);
            frame.candidateCheckpoint = checkpoint();
            frame.pendingUnits = units;
            frame.pendingInitial = initial;
            frame.phase = Phase.WAIT_CHILD;
            frames.push(new Frame(candidate.key(), initial, false, frame.candidateCheckpoint,
                    new PlannedBatchCause.Input(frame.batchId, PlannedBatchCause.Input.NORMAL_CONSUMER_MEMBER,
                            frame.inputIndex, frame.allocation.candidateIndex(), candidate.key(), initial)));
        }

        private void handleChild(Frame frame) {
            if (frame.childFailure != null) {
                rollback(frame.candidateCheckpoint);
                if (frame.childFailure == ExactCraftPlanResult.FailureReason.CYCLE) {
                    frame.sawCycle = true;
                } else {
                    frame.sawUnsatisfiable = true;
                }
                frame.childFailure = null;
                frame.allocation.afterFailedAttempt();
                frame.phase = Phase.NEXT_CANDIDATE;
                return;
            }
            if (!frame.childSucceeded) {
                throw new IllegalStateException("Child frame did not report an outcome");
            }
            frame.childSucceeded = false;
            frame.allocation.accept(frame.pendingUnits, frame.pendingInitial);
            frame.pendingUnits = null;
            frame.pendingInitial = null;
            frame.phase = Phase.NEXT_CANDIDATE;
        }

        private void commitChunk(Frame frame) {
            CompiledPattern pattern = frame.producer.pattern;
            for (CompiledOutputSpec output : pattern.outputs()) {
                credit(output.key(), multiply(output.amountPerExecution(), frame.executions));
            }
            for (PlannedInputSelection selection : frame.selections) {
                selection.remainderReturn().ifPresent(remainder -> credit(remainder.key(), remainder.amount()));
            }
            add(executions, pattern.id(), frame.executions, PatternLimits.MAX_GRAPH_NODES);
            addBatch(frame.batchId, frame.cause, pattern, frame.executions, frame.selections);
            AEAmount beforeCommit = frame.remaining;
            frame.remaining = consumeProduced(frame.key, frame.remaining);
            if (frame.remaining.compareTo(beforeCommit) < 0) {
                frame.rejectedCycles.clear();
            }
            if (frame.remaining.equals(AEAmount.ZERO)) {
                activePatterns.remove(pattern.id());
                frame.producer = null;
                succeed(frame);
            } else {
                frame.phase = Phase.START_CHUNK;
            }
        }

        private void rejectChunk(Frame frame, ExactCraftPlanResult.FailureReason reason) {
            rollback(frame.chunkCheckpoint);
            if (reason == ExactCraftPlanResult.FailureReason.CYCLE) {
                frame.sawCycle = true;
            } else {
                frame.sawUnsatisfiable = true;
            }
            frame.allocation = null;
            frame.selections = null;
            frame.batchId = null;
            if (frame.chunkSchedule == null) {
                // The full attempt has just failed. Only now is a bounded binary fallback relevant.
                frame.chunkSchedule = binarySchedule(frame.wholeAttempt);
                frame.chunkIndex = 0;
            }
            frame.phase = Phase.START_CHUNK;
        }

        /**
         * A later input may prove an earlier locally-valid allocation unusable. Roll the entire chunk back and rotate
         * the rightmost earlier input with another candidate. This is bounded by normal search decisions, and retains
         * the actual read dependencies from the rejected allocation.
         */
        private boolean rotateEarlierInput(Frame frame) {
            for (int index = frame.inputIndex - 1; index >= 0; index--) {
                int candidateCount = frame.producer.pattern.inputs().get(index).candidates().size();
                if (frame.rotationCounts[index] + 1 < candidateCount) {
                    decision();
                    frame.candidateStarts[index] = (frame.candidateStarts[index] + 1) % candidateCount;
                    frame.rotationCounts[index]++;
                    for (int reset = index + 1; reset < frame.candidateStarts.length; reset++) {
                        frame.candidateStarts[reset] = 0;
                        frame.rotationCounts[reset] = 0;
                    }
                    rollback(frame.chunkCheckpoint);
                    frame.allocation = null;
                    frame.selections = null;
                    frame.retryChunk = true;
                    frame.phase = Phase.START_CHUNK;
                    return true;
                }
            }
            return false;
        }

        /**
         * Replaces the active suffix ending in {@code duplicate} with one compact productive-cycle transaction. The
         * cycle is causally owned by the outermost member because that is the only demand edge outside the ring;
         * retaining the inner normal batches would leave their now-internal input causes dangling.
         */
        private void beginProductiveCycle(Frame current, Producer duplicate) {
            CyclePath path = activeCyclePath(current, duplicate);
            if (path == null || path.outer.rejectedCycles.contains(path.signature)) {
                return;
            }
            ProductiveCycleSolveResult solved = new ProductiveCycleSolver().solve(path.ring, path.outer.cause);
            if (solved instanceof ProductiveCycleSolveResult.Failure failure) {
                if (failure.reason() == ProductiveCycleSolveResult.FailureReason.QUANTITY_LIMIT) {
                    throw new Abort(ExactCraftPlanResult.FailureReason.QUANTITY_LIMIT);
                }
                if (failure.reason() == ProductiveCycleSolveResult.FailureReason.WORK_LIMIT) {
                    throw new Abort(ExactCraftPlanResult.FailureReason.WORK_LIMIT);
                }
                rememberRejectedCycle(path.outer, path.signature);
                return;
            }

            ProductiveCycleTemplate template = ((ProductiveCycleSolveResult.Success) solved).template();
            // Every pending normal member belongs to the proposed ring. Undo all of their direct reads and partial
            // child plans before reserving the seed, otherwise the same seed could be counted twice.
            rollback(path.outer.entryCheckpoint);
            for (Frame member : path.members) {
                activePatterns.remove(member.producer.id);
            }
            while (frames.peek() != path.outer) {
                frames.pop();
            }

            PlannedBatchId batchId = reserveBatchId();
            AEAmount unavailable = consumeAvailable(template.seedKey(), template.seedAmount());
            if (!unavailable.equals(AEAmount.ZERO)) {
                rejectCycle(path.outer, path.signature);
                return;
            }
            CycleAttempt attempt = new CycleAttempt(template, batchId, checkpoint(), path.signature);
            path.outer.resetForCycle(attempt);
        }

        /** Reconstructs the immediate-parent-to-duplicate ordered simple ring from active WAIT_CHILD frames. */
        private CyclePath activeCyclePath(Frame current, Producer duplicate) {
            List<Frame> members = new ArrayList<>();
            boolean skippedCurrent = false;
            for (Frame candidate : frames) {
                if (!skippedCurrent) {
                    if (candidate != current) {
                        throw new IllegalStateException("Current planner frame is not the active stack head");
                    }
                    skippedCurrent = true;
                    continue;
                }
                if (candidate.producer == null || candidate.phase != Phase.WAIT_CHILD || candidate.allocation == null
                        || candidate.inputIndex < 0
                        || candidate.inputIndex >= candidate.producer.pattern.inputs().size()) {
                    return null;
                }
                members.add(candidate);
                if (candidate.producer.id.equals(duplicate.id)) {
                    break;
                }
            }
            if (members.isEmpty() || !members.get(members.size() - 1).producer.id.equals(duplicate.id)) {
                return null;
            }
            if (members.size() > PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS) {
                throw new Abort(ExactCraftPlanResult.FailureReason.WORK_LIMIT);
            }

            List<ProductiveCycleRingMember> ringMembers = new ArrayList<>(members.size());
            List<CycleMemberSignature> signatureMembers = new ArrayList<>(members.size());
            for (int index = 0; index < members.size(); index++) {
                Frame member = members.get(index);
                KeyId suppliedKey = index + 1 == members.size() ? current.key : member.key;
                int outputIndex = onlyOutputIndex(member.producer.pattern, suppliedKey);
                if (outputIndex < 0) {
                    return null;
                }
                ringMembers.add(new ProductiveCycleRingMember(member.producer.pattern, member.inputIndex,
                        member.allocation.candidateIndex(), outputIndex));
                signatureMembers.add(new CycleMemberSignature(member.producer.id, member.inputIndex,
                        member.allocation.candidateIndex(), outputIndex));
            }
            return new CyclePath(members.get(members.size() - 1), List.copyOf(members),
                    new ProductiveCycleRing(ringMembers), new CycleSignature(signatureMembers));
        }

        private int onlyOutputIndex(CompiledPattern pattern, KeyId key) {
            int result = -1;
            for (int index = 0; index < pattern.outputs().size(); index++) {
                if (!pattern.outputs().get(index).key().equals(key)) {
                    continue;
                }
                if (result != -1) {
                    return -1;
                }
                result = index;
            }
            return result;
        }

        private void nextCycleExternal(Frame frame) {
            CycleAttempt cycle = requireCycle(frame);
            if (cycle.externalIndex >= cycle.externals.size()) {
                frame.phase = Phase.CYCLE_PUBLISH;
                return;
            }
            CycleExternal external = cycle.externals.get(cycle.externalIndex);
            if (cycle.allocation == null) {
                cycle.allocation = new InputAllocation(external.requirement.input(),
                        external.requirement.templateUnitsPerTurn(), cycle.candidateStarts[cycle.externalIndex]);
            }
            if (cycle.allocation.remaining.equals(AEAmount.ZERO)) {
                cycle.selections.put(external.identity, cycle.allocation.selections(external.requirement.inputIndex(),
                        external.pattern));
                cycle.allocation = null;
                cycle.externalIndex++;
                return;
            }
            frame.phase = Phase.CYCLE_NEXT_CANDIDATE;
        }

        private void nextCycleCandidate(Frame frame) {
            CycleAttempt cycle = requireCycle(frame);
            CycleExternal external = cycle.externals.get(cycle.externalIndex);
            InputAllocation allocation = cycle.allocation;
            if (allocation.remaining.equals(AEAmount.ZERO)) {
                frame.phase = Phase.CYCLE_NEXT_EXTERNAL;
                return;
            }
            if (allocation.candidatesTried >= allocation.input.candidates().size()) {
                if (!rotateEarlierCycleExternal(frame)) {
                    rejectCycle(frame, cycle.signature);
                }
                return;
            }
            if (!allocation.attemptingCandidate) {
                allocation.beginCandidate();
            }
            AEAmount units = allocation.nextAttemptUnits();
            if (units == null) {
                allocation.finishCandidate();
                return;
            }
            decision();
            CompiledCandidateSpec candidate = allocation.candidate();
            AEAmount perTurnInitial = allocation.initialRequirement(candidate, units, external.pattern);
            AEAmount totalInitial = multiply(perTurnInitial, cycle.template.repetitions());
            frame.candidateCheckpoint = checkpoint();
            frame.pendingUnits = units;
            frame.pendingInitial = perTurnInitial;
            frame.phase = Phase.CYCLE_WAIT_CHILD;
            frames.push(new Frame(candidate.key(), totalInitial, false, frame.candidateCheckpoint,
                    new PlannedBatchCause.Input(cycle.batchId, external.requirement.memberIndex(),
                            external.requirement.inputIndex(), allocation.candidateIndex(), candidate.key(),
                            totalInitial)));
        }

        private void handleCycleChild(Frame frame) {
            CycleAttempt cycle = requireCycle(frame);
            if (frame.childFailure != null) {
                rollback(frame.candidateCheckpoint);
                frame.childFailure = null;
                cycle.allocation.afterFailedAttempt();
                frame.phase = Phase.CYCLE_NEXT_CANDIDATE;
                return;
            }
            if (!frame.childSucceeded) {
                throw new IllegalStateException("Cycle input child did not report an outcome");
            }
            frame.childSucceeded = false;
            cycle.allocation.accept(frame.pendingUnits, frame.pendingInitial);
            frame.pendingUnits = null;
            frame.pendingInitial = null;
            frame.phase = Phase.CYCLE_NEXT_CANDIDATE;
        }

        private boolean rotateEarlierCycleExternal(Frame frame) {
            CycleAttempt cycle = requireCycle(frame);
            for (int index = cycle.externalIndex - 1; index >= 0; index--) {
                int candidates = cycle.externals.get(index).requirement.input().candidates().size();
                if (cycle.rotationCounts[index] + 1 < candidates) {
                    decision();
                    cycle.candidateStarts[index] = (cycle.candidateStarts[index] + 1) % candidates;
                    cycle.rotationCounts[index]++;
                    for (int reset = index + 1; reset < cycle.candidateStarts.length; reset++) {
                        cycle.candidateStarts[reset] = 0;
                        cycle.rotationCounts[reset] = 0;
                    }
                    rollback(cycle.externalCheckpoint);
                    cycle.resetExternalAllocations();
                    frame.phase = Phase.CYCLE_NEXT_EXTERNAL;
                    return true;
                }
            }
            return false;
        }

        private void publishCycle(Frame frame) {
            CycleAttempt cycle = requireCycle(frame);
            PlannedCycleBatch batch = materializeCycle(cycle);
            for (PlannedCycleMember member : batch.members()) {
                add(executions, member.patternId(), multiply(member.executionsPerTurn(), batch.repetitions()),
                        PatternLimits.MAX_GRAPH_NODES);
            }
            addCycleBatch(batch);
            for (Map.Entry<KeyId, AEAmount> credit : batch.finalCredits().entrySet()) {
                credit(credit.getKey(), credit.getValue());
            }
            frame.remaining = consumeProduced(frame.key, frame.required);
            if (!frame.remaining.equals(AEAmount.ZERO)) {
                throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
            }
            frame.cycle = null;
            succeed(frame);
        }

        private PlannedCycleBatch materializeCycle(CycleAttempt cycle) {
            List<PlannedCycleMember> members = new ArrayList<>(cycle.template.members().size());
            for (int memberIndex = 0; memberIndex < cycle.template.members().size(); memberIndex++) {
                ProductiveCycleTemplate.Member source = cycle.template.members().get(memberIndex);
                List<PlannedInputSelection> inputs = new ArrayList<>();
                ProductiveCycleTemplate.InternalLink incoming = cycle.template.internalLinks()
                        .get((memberIndex - 1 + cycle.template.members().size()) % cycle.template.members().size());
                CompiledInputSpec internalInput = source.pattern().inputs().get(incoming.inputIndex());
                CompiledCandidateSpec internalCandidate = internalInput.candidates().get(incoming.candidateIndex());
                AEAmount internalUnits = multiply(internalInput.multiplier(), source.executionsPerTurn());
                inputs.add(new PlannedInputSelection(incoming.inputIndex(), incoming.candidateIndex(), internalUnits,
                        internalCandidate.key(), incoming.consumedAmountPerTurn(), incoming.consumedAmountPerTurn(),
                        java.util.Optional.empty()));
                for (ProductiveCycleTemplate.ExternalInputRequirement external : source.externalInputsPerTurn()) {
                    List<PlannedInputSelection> selections = cycle.selections
                            .get(new CycleInputIdentity(external.memberIndex(), external.inputIndex()));
                    if (selections == null) {
                        throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
                    }
                    inputs.addAll(selections);
                }
                inputs.sort(Comparator.comparingInt(PlannedInputSelection::inputIndex)
                        .thenComparingInt(PlannedInputSelection::candidateIndex));
                List<PlannedCycleOutput> outputs = new ArrayList<>(source.outputsPerTurn().size());
                for (ProductiveCycleTemplate.Output output : source.outputsPerTurn()) {
                    outputs.add(new PlannedCycleOutput(output.outputIndex(), output.key(), output.amountPerTurn()));
                }
                members.add(new PlannedCycleMember(source.pattern().id(), source.executionsPerTurn(), inputs, outputs));
            }
            List<PlannedCycleLink> links = new ArrayList<>(cycle.template.internalLinks().size());
            for (ProductiveCycleTemplate.InternalLink link : cycle.template.internalLinks()) {
                links.add(
                        new PlannedCycleLink(link.producerMemberIndex(), link.outputIndex(), link.consumerMemberIndex(),
                                link.inputIndex(), link.candidateIndex(), link.key(), link.consumedAmountPerTurn()));
            }
            return new PlannedCycleBatch(cycle.batchId, cycle.template.cause(), cycle.template.repetitions(),
                    cycle.template.seedKey(), cycle.template.seedAmount(), members, links,
                    cycleFinalCredits(members, links, cycle.template.repetitions(), cycle.template.seedKey(),
                            cycle.template.seedAmount()));
        }

        private Map<KeyId, AEAmount> cycleFinalCredits(List<PlannedCycleMember> members, List<PlannedCycleLink> links,
                AEAmount repetitions, KeyId seedKey, AEAmount seedAmount) {
            TreeMap<KeyId, AEAmount> perTurn = new TreeMap<>(Comparator.comparingInt(KeyId::value));
            for (PlannedCycleMember member : members) {
                for (PlannedCycleOutput output : member.outputsPerTurn()) {
                    addCredit(perTurn, output.key(), output.amountPerTurn());
                }
                for (PlannedInputSelection input : member.inputsPerTurn()) {
                    input.remainderReturn()
                            .ifPresent(remainder -> addCredit(perTurn, remainder.key(), remainder.amount()));
                }
            }
            for (PlannedCycleLink link : links) {
                AEAmount amount = perTurn.getOrDefault(link.key(), AEAmount.ZERO);
                if (amount.compareTo(link.amountPerTurn()) < 0) {
                    throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
                }
                AEAmount result = subtractAmounts(amount, link.amountPerTurn());
                if (result.equals(AEAmount.ZERO)) {
                    perTurn.remove(link.key());
                } else {
                    perTurn.put(link.key(), result);
                }
            }
            TreeMap<KeyId, AEAmount> finalCredits = new TreeMap<>(Comparator.comparingInt(KeyId::value));
            for (Map.Entry<KeyId, AEAmount> entry : perTurn.entrySet()) {
                finalCredits.put(entry.getKey(), multiply(entry.getValue(), repetitions));
            }
            finalCredits.merge(seedKey, seedAmount, this::addAmounts);
            return finalCredits;
        }

        private void addCredit(Map<KeyId, AEAmount> credits, KeyId key, AEAmount amount) {
            if (credits.size() >= PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS && !credits.containsKey(key)) {
                throw new Abort(ExactCraftPlanResult.FailureReason.WORK_LIMIT);
            }
            credits.put(key, addAmounts(credits.getOrDefault(key, AEAmount.ZERO), amount));
        }

        private void addCycleBatch(PlannedCycleBatch batch) {
            if (causalSteps.size() >= PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS) {
                throw new Abort(ExactCraftPlanResult.FailureReason.WORK_LIMIT);
            }
            mutation();
            causalSteps.add(batch);
            undo.add(new ListUndo<>(causalSteps));
        }

        private CycleAttempt requireCycle(Frame frame) {
            if (frame.cycle == null) {
                throw new IllegalStateException("Cycle planner phase without a cycle attempt");
            }
            return frame.cycle;
        }

        private void rejectCycle(Frame outer, CycleSignature signature) {
            rollback(outer.entryCheckpoint);
            rememberRejectedCycle(outer, signature);
            outer.resetForRestart();
        }

        private void rememberRejectedCycle(Frame frame, CycleSignature signature) {
            if (!frame.rejectedCycles.contains(signature)
                    && frame.rejectedCycles.size() >= PlannerLimits.MAX_PRODUCTIVE_CYCLE_ATTEMPTS) {
                throw new Abort(ExactCraftPlanResult.FailureReason.WORK_LIMIT);
            }
            frame.rejectedCycles.add(signature);
        }

        private void credit(KeyId key, AEAmount amount) {
            if (key.value() >= storage.keyCount()) {
                throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
            }
            add(produced, key, amount, PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS);
        }

        private AEAmount consumeAvailable(KeyId key, AEAmount amount) {
            loadStorage(key);
            AEAmount remaining = consumeFrom(storageRemaining, storageConsumed, key, amount, true);
            return consumeFrom(produced, null, key, remaining, false);
        }

        private AEAmount consumeProduced(KeyId key, AEAmount amount) {
            return consumeFrom(produced, null, key, amount, false);
        }

        private AEAmount consumeFrom(Map<KeyId, AEAmount> source, Map<KeyId, AEAmount> consumed, KeyId key,
                AEAmount required, boolean storageSource) {
            AEAmount available = source.getOrDefault(key, AEAmount.ZERO);
            AEAmount taken = available.min(required);
            if (!taken.equals(AEAmount.ZERO)) {
                set(source, key, available.subtractExact(taken), PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS);
                if (storageSource) {
                    add(consumed, key, taken, PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS);
                }
            }
            return required.subtractExact(taken);
        }

        private void loadStorage(KeyId key) {
            if (loadedStorage.add(key)) {
                mutation();
                if (key.value() >= storage.keyCount()) {
                    throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
                }
                dependencies.recordStorageRead(key, storage.keyRevision(key));
                AEAmount amount = checked(storage.amount(key));
                if (!amount.equals(AEAmount.ZERO)) {
                    storageRemaining.put(key, amount);
                }
            }
        }

        private List<Producer> producersFor(KeyId key) {
            List<Producer> result = new ArrayList<>();
            for (PatternId id : patterns.graph().producerIndex().producers(key)) {
                CompiledPattern pattern = patterns.patternsById().get(id);
                if (pattern == null) {
                    throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
                }
                dependencies.recordPatternRead(id, pattern.revision());
                result.add(new Producer(id, pattern, patterns.maxProviderPriorities().getOrDefault(id, 0)));
            }
            result.sort(Comparator.comparingInt(Producer::priority).reversed().thenComparing(Producer::id));
            return result;
        }

        private AEAmount matchingOutput(CompiledPattern pattern, KeyId key) {
            AEAmount total = AEAmount.ZERO;
            for (CompiledOutputSpec output : pattern.outputs()) {
                if (output.key().equals(key)) {
                    total = addAmounts(total, output.amountPerExecution());
                }
            }
            return total;
        }

        private List<AEAmount> binarySchedule(AEAmount maximum) {
            checked(maximum);
            int bits = maximum.toBigInteger().bitLength();
            if (bits > PlannerLimits.MAX_CRAFT_CHUNK_BITS) {
                throw new Abort(ExactCraftPlanResult.FailureReason.WORK_LIMIT);
            }
            List<AEAmount> chunks = new ArrayList<>(bits);
            for (int bit = bits - 1; bit >= 0; bit--) {
                chunks.add(AEAmount.of(BigInteger.ONE.shiftLeft(bit)));
            }
            return chunks;
        }

        private AEAmount multiply(AEAmount left, AEAmount right) {
            checked(left);
            checked(right);
            if (left.equals(AEAmount.ZERO) || right.equals(AEAmount.ZERO)) {
                return AEAmount.ZERO;
            }
            long bits = (long) left.toBigInteger().bitLength() + right.toBigInteger().bitLength();
            if (bits - 1 > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
                throw new Abort(ExactCraftPlanResult.FailureReason.QUANTITY_LIMIT);
            }
            return checked(left.multiply(right));
        }

        private AEAmount ceilDiv(AEAmount numerator, AEAmount denominator) {
            return checked(numerator.ceilDiv(denominator));
        }

        private AEAmount addAmounts(AEAmount left, AEAmount right) {
            return checked(left.add(right));
        }

        private AEAmount subtractAmounts(AEAmount left, AEAmount right) {
            return checked(left.subtractExact(right));
        }

        private static AEAmount checked(AEAmount amount) {
            if (amount.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
                throw new Abort(ExactCraftPlanResult.FailureReason.QUANTITY_LIMIT);
            }
            return amount;
        }

        private void decision() {
            if (decisions++ >= PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS) {
                throw new Abort(ExactCraftPlanResult.FailureReason.WORK_LIMIT);
            }
        }

        private int checkpoint() {
            return undo.size();
        }

        private void rollback(int checkpoint) {
            while (undo.size() > checkpoint) {
                undo.remove(undo.size() - 1).undo();
            }
        }

        private <K> void add(Map<K, AEAmount> map, K key, AEAmount amount, int maximum) {
            set(map, key, addAmounts(map.getOrDefault(key, AEAmount.ZERO), amount), maximum);
        }

        private <K> void set(Map<K, AEAmount> map, K key, AEAmount amount, int maximum) {
            AEAmount previous = map.get(key);
            if (Objects.equals(previous, amount)) {
                return;
            }
            if (previous == null && !amount.equals(AEAmount.ZERO) && map.size() >= maximum) {
                throw new Abort(ExactCraftPlanResult.FailureReason.WORK_LIMIT);
            }
            mutation();
            undo.add(new MapUndo<>(map, key, previous));
            if (amount.equals(AEAmount.ZERO)) {
                map.remove(key);
            } else {
                map.put(key, amount);
            }
        }

        private void addBatch(PlannedBatchId id, PlannedBatchCause cause, CompiledPattern pattern,
                AEAmount batchExecutions,
                List<PlannedInputSelection> selections) {
            if (causalSteps.size() >= PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS) {
                throw new Abort(ExactCraftPlanResult.FailureReason.WORK_LIMIT);
            }
            validateSelections(pattern, batchExecutions, selections);
            mutation();
            causalSteps.add(new PlannedPatternBatch(id, cause, pattern.id(), batchExecutions, selections));
            undo.add(new ListUndo<>(causalSteps));
        }

        private PlannedBatchId reserveBatchId() {
            if (nextBatchId == Long.MAX_VALUE) {
                throw new Abort(ExactCraftPlanResult.FailureReason.WORK_LIMIT);
            }
            return new PlannedBatchId(nextBatchId++);
        }

        private void validateSelections(CompiledPattern pattern, AEAmount batchExecutions,
                List<PlannedInputSelection> selections) {
            int selectionIndex = 0;
            for (int inputIndex = 0; inputIndex < pattern.inputs().size(); inputIndex++) {
                CompiledInputSpec input = pattern.inputs().get(inputIndex);
                AEAmount expectedUnits = multiply(input.multiplier(), batchExecutions);
                AEAmount selectedUnits = AEAmount.ZERO;
                int lastCandidate = -1;
                while (selectionIndex < selections.size()
                        && selections.get(selectionIndex).inputIndex() == inputIndex) {
                    PlannedInputSelection selection = selections.get(selectionIndex++);
                    if (selection.candidateIndex() <= lastCandidate
                            || selection.candidateIndex() >= input.candidates().size()) {
                        throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
                    }
                    lastCandidate = selection.candidateIndex();
                    CompiledCandidateSpec candidate = input.candidates().get(selection.candidateIndex());
                    AEAmount expectedGross = multiply(candidate.amountPerTemplate(), selection.templateUnits());
                    if (!selection.consumedKey().equals(candidate.key())
                            || !selection.grossConsumedAmount().equals(expectedGross)
                            || selection.initialRequiredAmount().compareTo(expectedGross) > 0
                            || !selection.remainderReturn().equals(expectedReturn(input, candidate, pattern,
                                    selection.templateUnits(), selection.initialRequiredAmount()))) {
                        throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
                    }
                    validateInitialAndReturn(input, candidate, pattern, selection, expectedGross);
                    if (!seedAllowed(input, candidate, pattern)
                            && !selection.initialRequiredAmount().equals(expectedGross)) {
                        throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
                    }
                    selectedUnits = addAmounts(selectedUnits, selection.templateUnits());
                }
                if (!selectedUnits.equals(expectedUnits)) {
                    throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
                }
            }
            if (selectionIndex != selections.size()) {
                throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
            }
        }

        private boolean seedAllowed(CompiledInputSpec input, CompiledCandidateSpec candidate, CompiledPattern pattern) {
            if (input.candidates().size() != 1 || candidate.remainder().isEmpty()
                    || !candidate.remainder().get().key().equals(candidate.key())) {
                return false;
            }
            return pattern.outputs().stream().noneMatch(output -> output.key().equals(candidate.key()));
        }

        private void validateInitialAndReturn(CompiledInputSpec input, CompiledCandidateSpec candidate,
                CompiledPattern pattern, PlannedInputSelection selection, AEAmount gross) {
            if (!seedAllowed(input, candidate, pattern)) {
                if (!selection.initialRequiredAmount().equals(gross)) {
                    throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
                }
                return;
            }
            AEAmount seed = reusableSeed(input, candidate, selection.templateUnits());
            if (!selection.initialRequiredAmount().equals(gross) && !selection.initialRequiredAmount().equals(seed)) {
                throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
            }
        }

        private java.util.Optional<PlannedRemainderReturn> expectedReturn(CompiledInputSpec input,
                CompiledCandidateSpec candidate, CompiledPattern pattern, AEAmount templateUnits, AEAmount initial) {
            return candidate.remainder().map(remainder -> {
                AEAmount rawReturn = multiply(remainder.amountPerTemplate(), templateUnits);
                AEAmount gross = multiply(candidate.amountPerTemplate(), templateUnits);
                if (!seedAllowed(input, candidate, pattern) || initial.equals(gross)) {
                    return new PlannedRemainderReturn(remainder.key(), rawReturn);
                }
                if (!initial.equals(reusableSeed(input, candidate, templateUnits))) {
                    throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
                }
                return new PlannedRemainderReturn(remainder.key(),
                        reusableFinalReturn(input, candidate, templateUnits));
            });
        }

        private AEAmount reusableSeed(CompiledInputSpec input, CompiledCandidateSpec candidate, AEAmount units) {
            if (!units.toBigInteger().remainder(input.multiplier().toBigInteger()).equals(BigInteger.ZERO)) {
                throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
            }
            AEAmount inputPerExecution = multiply(candidate.amountPerTemplate(), input.multiplier());
            AEAmount returnedPerExecution = multiply(candidate.remainder().orElseThrow().amountPerTemplate(),
                    input.multiplier());
            AEAmount executions = units.divide(input.multiplier());
            AEAmount net = inputPerExecution.compareTo(returnedPerExecution) > 0
                    ? subtractAmounts(inputPerExecution, returnedPerExecution)
                    : AEAmount.ZERO;
            return addAmounts(inputPerExecution, multiply(subtractAmounts(executions, AEAmount.ONE), net));
        }

        private AEAmount reusableFinalReturn(CompiledInputSpec input, CompiledCandidateSpec candidate, AEAmount units) {
            AEAmount inputPerExecution = multiply(candidate.amountPerTemplate(), input.multiplier());
            AEAmount returnedPerExecution = multiply(candidate.remainder().orElseThrow().amountPerTemplate(),
                    input.multiplier());
            AEAmount executions = units.divide(input.multiplier());
            return returnedPerExecution.compareTo(inputPerExecution) < 0
                    ? returnedPerExecution
                    : addAmounts(inputPerExecution,
                            multiply(executions, subtractAmounts(returnedPerExecution, inputPerExecution)));
        }

        private void mutation() {
            if (mutations++ >= PlannerLimits.MAX_CRAFT_MUTATIONS) {
                throw new Abort(ExactCraftPlanResult.FailureReason.WORK_LIMIT);
            }
        }

        private void succeed(Frame frame) {
            frames.pop();
            if (!frames.isEmpty()) {
                frames.peek().childSucceeded = true;
            }
        }

        private void fail(Frame frame, ExactCraftPlanResult.FailureReason reason) {
            rollback(frame.entryCheckpoint);
            if (frame.producer != null) {
                activePatterns.remove(frame.producer.id);
            }
            frames.pop();
            if (frames.isEmpty()) {
                rootFailure = reason;
            } else {
                frames.peek().childFailure = reason;
            }
        }

        private final class InputAllocation {
            private final CompiledInputSpec input;
            private final Map<Integer, Allocation> allocations = new HashMap<>();
            private AEAmount remaining;
            private int candidateIndex;
            private boolean attemptingCandidate;
            private boolean wholeAttempt;
            private boolean lastAttemptWasWhole;
            private List<AEAmount> chunks;
            private int chunkIndex;
            private int candidatesTried;

            private InputAllocation(CompiledInputSpec input, AEAmount total, int firstCandidate) {
                this.input = input;
                this.remaining = total;
                this.candidateIndex = firstCandidate;
            }

            private void beginCandidate() {
                attemptingCandidate = true;
                wholeAttempt = true;
                chunks = null;
                chunkIndex = 0;
            }

            private CompiledCandidateSpec candidate() {
                return input.candidates().get(candidateIndex);
            }

            private int candidateIndex() {
                return candidateIndex;
            }

            private AEAmount nextAttemptUnits() {
                if (wholeAttempt) {
                    wholeAttempt = false;
                    lastAttemptWasWhole = true;
                    return remaining;
                }
                lastAttemptWasWhole = false;
                while (chunks != null && chunkIndex < chunks.size()
                        && chunks.get(chunkIndex).compareTo(remaining) > 0) {
                    chunkIndex++;
                }
                return chunks != null && chunkIndex < chunks.size() ? chunks.get(chunkIndex++) : null;
            }

            private void afterFailedAttempt() {
                if (lastAttemptWasWhole && chunks == null) {
                    chunks = binarySchedule(remaining);
                    chunkIndex = 0;
                }
            }

            private AEAmount initialRequirement(CompiledCandidateSpec candidate, AEAmount units,
                    CompiledPattern pattern) {
                AEAmount gross = multiply(candidate.amountPerTemplate(), units);
                // Reuse is a dedicated whole-group attempt. The generic fallback chunks acquire their full gross
                // requirement, so their later aggregation cannot claim a seed it did not actually obtain.
                if (!lastAttemptWasWhole || !seedAllowed(input, candidate, pattern)) {
                    return gross;
                }
                return reusableSeed(input, candidate, units);
            }

            private void accept(AEAmount units, AEAmount initial) {
                Allocation previous = allocations.get(candidateIndex);
                allocations.put(candidateIndex,
                        new Allocation(previous == null ? units : addAmounts(previous.units, units),
                                previous == null ? initial : addAmounts(previous.initial, initial)));
                remaining = subtractAmounts(remaining, units);
            }

            private void finishCandidate() {
                attemptingCandidate = false;
                candidatesTried++;
                candidateIndex = (candidateIndex + 1) % input.candidates().size();
            }

            private List<PlannedInputSelection> selections(int inputIndex, CompiledPattern pattern) {
                List<PlannedInputSelection> result = new ArrayList<>();
                for (int index = 0; index < input.candidates().size(); index++) {
                    Allocation allocation = allocations.get(index);
                    if (allocation == null) {
                        continue;
                    }
                    CompiledCandidateSpec candidate = input.candidates().get(index);
                    AEAmount gross = multiply(candidate.amountPerTemplate(), allocation.units);
                    var returnValue = expectedReturn(input, candidate, pattern, allocation.units, allocation.initial);
                    result.add(new PlannedInputSelection(inputIndex, index, allocation.units, candidate.key(), gross,
                            allocation.initial, returnValue));
                }
                return result;
            }
        }
    }

    private enum Phase {
        START, NEXT_PRODUCER, START_CHUNK, NEXT_INPUT, NEXT_CANDIDATE, WAIT_CHILD, COMMIT_CHUNK,
        CYCLE_NEXT_EXTERNAL, CYCLE_NEXT_CANDIDATE, CYCLE_WAIT_CHILD, CYCLE_PUBLISH
    }

    /** Exact structural identity for one bounded rejected active ring. */
    private record CycleSignature(List<CycleMemberSignature> members) {
        private CycleSignature {
            members = List.copyOf(members);
        }
    }

    private record CycleMemberSignature(PatternId patternId, int internalInputIndex, int internalCandidateIndex,
            int supplyingOutputIndex) {
        private CycleMemberSignature {
            Objects.requireNonNull(patternId, "patternId");
        }
    }

    private record CycleInputIdentity(int memberIndex, int inputIndex) {
    }

    private record CyclePath(Frame outer, List<Frame> members, ProductiveCycleRing ring, CycleSignature signature) {
    }

    private static final class CycleExternal {
        private final ProductiveCycleTemplate.ExternalInputRequirement requirement;
        private final CompiledPattern pattern;
        private final CycleInputIdentity identity;

        private CycleExternal(ProductiveCycleTemplate.ExternalInputRequirement requirement, CompiledPattern pattern) {
            this.requirement = requirement;
            this.pattern = pattern;
            this.identity = new CycleInputIdentity(requirement.memberIndex(), requirement.inputIndex());
        }
    }

    private static final class CycleAttempt {
        private final ProductiveCycleTemplate template;
        private final PlannedBatchId batchId;
        private final int externalCheckpoint;
        private final CycleSignature signature;
        private final List<CycleExternal> externals;
        private final int[] candidateStarts;
        private final int[] rotationCounts;
        private final Map<CycleInputIdentity, List<PlannedInputSelection>> selections = new HashMap<>();
        private int externalIndex;
        private Search.InputAllocation allocation;

        private CycleAttempt(ProductiveCycleTemplate template, PlannedBatchId batchId, int externalCheckpoint,
                CycleSignature signature) {
            this.template = template;
            this.batchId = batchId;
            this.externalCheckpoint = externalCheckpoint;
            this.signature = signature;
            List<CycleExternal> flattened = new ArrayList<>();
            for (ProductiveCycleTemplate.Member member : template.members()) {
                for (ProductiveCycleTemplate.ExternalInputRequirement input : member.externalInputsPerTurn()) {
                    flattened.add(new CycleExternal(input, member.pattern()));
                }
            }
            this.externals = List.copyOf(flattened);
            this.candidateStarts = new int[externals.size()];
            this.rotationCounts = new int[externals.size()];
        }

        private void resetExternalAllocations() {
            externalIndex = 0;
            allocation = null;
            selections.clear();
        }
    }

    private static final class Frame {
        private final KeyId key;
        private final AEAmount required;
        private final boolean root;
        private final int entryCheckpoint;
        private Phase phase = Phase.START;
        private AEAmount remaining;
        private List<Producer> producers = List.of();
        private int producerIndex;
        private int producerPasses;
        private AEAmount passStartRemaining;
        private Producer producer;
        private AEAmount outputPerExecution;
        private AEAmount wholeAttempt;
        private List<AEAmount> chunkSchedule = List.of();
        private int chunkIndex;
        private int chunkCheckpoint;
        private boolean retryChunk;
        private boolean retryingExistingChunk;
        private int[] candidateStarts = new int[0];
        private int[] rotationCounts = new int[0];
        private AEAmount executions;
        private int inputIndex;
        private Search.InputAllocation allocation;
        private List<PlannedInputSelection> selections;
        private int candidateCheckpoint;
        private AEAmount pendingUnits;
        private AEAmount pendingInitial;
        private boolean childSucceeded;
        private ExactCraftPlanResult.FailureReason childFailure;
        private boolean sawCycle;
        private boolean sawUnsatisfiable;
        private final PlannedBatchCause cause;
        private PlannedBatchId batchId;
        private CycleAttempt cycle;
        private final Set<CycleSignature> rejectedCycles = new HashSet<>();

        private Frame(KeyId key, AEAmount required, boolean root, int entryCheckpoint, PlannedBatchCause cause) {
            this.key = key;
            this.required = required;
            this.root = root;
            this.entryCheckpoint = entryCheckpoint;
            this.cause = cause;
        }

        private void resetForCycle(CycleAttempt cycle) {
            resetForRestart();
            this.cycle = Objects.requireNonNull(cycle, "cycle");
            this.phase = Phase.CYCLE_NEXT_EXTERNAL;
        }

        private void resetForRestart() {
            phase = Phase.START;
            remaining = null;
            producers = List.of();
            producerIndex = 0;
            producerPasses = 0;
            passStartRemaining = null;
            producer = null;
            outputPerExecution = null;
            wholeAttempt = null;
            chunkSchedule = List.of();
            chunkIndex = 0;
            chunkCheckpoint = 0;
            retryChunk = false;
            retryingExistingChunk = false;
            candidateStarts = new int[0];
            rotationCounts = new int[0];
            executions = null;
            inputIndex = 0;
            allocation = null;
            selections = null;
            candidateCheckpoint = 0;
            pendingUnits = null;
            pendingInitial = null;
            childSucceeded = false;
            childFailure = null;
            sawCycle = false;
            sawUnsatisfiable = false;
            batchId = null;
            cycle = null;
        }
    }

    private record Producer(PatternId id, CompiledPattern pattern, int priority) {
    }

    private record Allocation(AEAmount units, AEAmount initial) {
    }

    private interface Undo {
        void undo();
    }

    private record MapUndo<K>(Map<K, AEAmount> map, K key, AEAmount previous) implements Undo {
        @Override
        public void undo() {
            if (previous == null)
                map.remove(key);
            else
                map.put(key, previous);
        }
    }

    private record ListUndo<T>(List<T> list) implements Undo {
        @Override
        public void undo() {
            list.remove(list.size() - 1);
        }
    }

    private static final class Abort extends RuntimeException {
        private final ExactCraftPlanResult.FailureReason reason;

        private Abort(ExactCraftPlanResult.FailureReason reason) {
            this.reason = reason;
        }
    }
}

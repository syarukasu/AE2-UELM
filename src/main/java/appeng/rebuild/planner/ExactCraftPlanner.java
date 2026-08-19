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
 * A target producer is visited once, first as a whole and then through a bounded descending-power fallback. It is not
 * revisited after another target producer, so productive SCCs and A/B/A allocation are deliberately deferred rather
 * than approximated. Input candidates are tried in bounded cyclic rotations, and later input failure can roll a chunk
 * back to rotate an earlier input group. No operation touches world or legacy state.
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
        private final List<PlannedPatternBatch> batches = new ArrayList<>();
        private final List<Undo> undo = new ArrayList<>();
        private final Set<PatternId> activePatterns = new HashSet<>();
        private final ArrayDeque<Frame> frames = new ArrayDeque<>();
        private int decisions;
        private int mutations;
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
            frames.push(new Frame(request.output(), request.amount(), true, checkpoint()));
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
                    storageConsumed, produced, batches, dependencies.build()));
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
            frame.phase = Phase.NEXT_PRODUCER;
        }

        private void nextProducer(Frame frame) {
            if (frame.remaining.equals(AEAmount.ZERO)) {
                succeed(frame);
                return;
            }
            if (frame.producerIndex >= frame.producers.size()) {
                fail(frame, frame.sawCycle && !frame.sawUnsatisfiable
                        ? ExactCraftPlanResult.FailureReason.CYCLE
                        : ExactCraftPlanResult.FailureReason.UNSATISFIABLE_WITHIN_STRATEGY);
                return;
            }
            decision();
            Producer producer = frame.producers.get(frame.producerIndex++);
            if (!activePatterns.add(producer.id)) {
                frame.sawCycle = true;
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
            frames.push(new Frame(candidate.key(), initial, false, frame.candidateCheckpoint));
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
            addBatch(pattern, frame.executions, frame.selections);
            frame.remaining = consumeProduced(frame.key, frame.remaining);
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

        private void addBatch(CompiledPattern pattern, AEAmount batchExecutions,
                List<PlannedInputSelection> selections) {
            if (batches.size() >= PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS) {
                throw new Abort(ExactCraftPlanResult.FailureReason.WORK_LIMIT);
            }
            validateSelections(pattern, batchExecutions, selections);
            mutation();
            batches.add(new PlannedPatternBatch(pattern.id(), batchExecutions, selections));
            undo.add(new ListUndo<>(batches));
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
        START, NEXT_PRODUCER, START_CHUNK, NEXT_INPUT, NEXT_CANDIDATE, WAIT_CHILD, COMMIT_CHUNK
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

        private Frame(KeyId key, AEAmount required, boolean root, int entryCheckpoint) {
            this.key = key;
            this.required = required;
            this.root = root;
            this.entryCheckpoint = entryCheckpoint;
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

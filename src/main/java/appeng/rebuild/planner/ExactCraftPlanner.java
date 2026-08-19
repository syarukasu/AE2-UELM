package appeng.rebuild.planner;

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
import appeng.rebuild.pattern.CompiledRemainderSpec;
import appeng.rebuild.pattern.NormalizedPatternSnapshot;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.storage.StorageSnapshot;

/**
 * Pure bounded planner for one exact craft request.
 *
 * <p>
 * This foundation chooses one producer for each remaining requirement and one candidate for each whole input group. It
 * deliberately does not allocate one requirement across multiple producers or candidates; a failure can therefore be
 * {@link ExactCraftPlanResult.FailureReason#UNSATISFIABLE_WITHIN_STRATEGY} even where a later mixed-allocation planner
 * could succeed. Productive active recursion and container-return reuse are conservatively classified as cycle or
 * strategy failures in B1; their explicit reuse algebra is deferred to B2.
 */
public final class ExactCraftPlanner {
    /** Plans only from immutable snapshots; it never reconciles, retries, or accesses legacy/world state. */
    public ExactCraftPlanResult plan(ExactCraftRequest request, StorageSnapshot storageSnapshot,
            NormalizedPatternSnapshot patternSnapshot) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(storageSnapshot, "storageSnapshot");
        Objects.requireNonNull(patternSnapshot, "patternSnapshot");
        GridRevision revision;
        try {
            revision = GridRevision.capture(storageSnapshot, patternSnapshot);
        } catch (IllegalArgumentException invalid) {
            return new ExactCraftPlanResult.Failure(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
        }
        if (storageSnapshot.keyCount() == 0 || request.output().value() >= storageSnapshot.keyCount()) {
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
            frames.push(new Frame(request.output(), request.amount(), true, 0));
            while (!frames.isEmpty()) {
                if (frames.size() > PlannerLimits.MAX_CRAFT_STACK_DEPTH) {
                    throw new Abort(ExactCraftPlanResult.FailureReason.WORK_LIMIT);
                }
                step(frames.peek());
            }
            if (rootFailure != null) {
                return new ExactCraftPlanResult.Failure(rootFailure);
            }
            return new ExactCraftPlanResult.Success(new ExactCraftPlanDraft(gridRevision, request,
                    executions, storageConsumed, produced, batches, dependencies.build()));
        }

        private void step(Frame frame) {
            switch (frame.phase) {
                case START -> start(frame);
                case TRY_PRODUCER -> tryProducer(frame);
                case TRY_CANDIDATE -> tryCandidate(frame);
                case WAIT_CHILD -> handleChild(frame);
                case COMMIT -> commit(frame);
            }
        }

        private void start(Frame frame) {
            AEAmount remaining = frame.root ? consumeProduced(frame.key, frame.required)
                    : consumeAvailable(frame.key, frame.required);
            frame.remaining = remaining;
            if (remaining.equals(AEAmount.ZERO)) {
                succeed(frame);
                return;
            }
            List<Producer> producers = producersFor(frame.key);
            if (producers.isEmpty()) {
                fail(frame, ExactCraftPlanResult.FailureReason.NO_PRODUCER);
                return;
            }
            frame.producers = producers;
            frame.phase = Phase.TRY_PRODUCER;
        }

        private void tryProducer(Frame frame) {
            while (frame.producerIndex < frame.producers.size()) {
                decision();
                Producer producer = frame.producers.get(frame.producerIndex++);
                if (!activePatterns.add(producer.id)) {
                    frame.sawCycle = true;
                    continue;
                }
                frame.producer = producer;
                frame.producerCheckpoint = checkpoint();
                AEAmount outputPerExecution = matchingOutput(producer.pattern, frame.key);
                if (outputPerExecution.equals(AEAmount.ZERO)) {
                    fail(frame, ExactCraftPlanResult.FailureReason.INVALID_INPUT);
                    return;
                }
                frame.executions = ceilDiv(frame.remaining, outputPerExecution);
                frame.inputIndex = 0;
                frame.selections = new ArrayList<>();
                frame.phase = frame.producer.pattern.inputs().isEmpty() ? Phase.COMMIT : Phase.TRY_CANDIDATE;
                return;
            }
            fail(frame, frame.sawCycle && !frame.sawUnsatisfiable
                    ? ExactCraftPlanResult.FailureReason.CYCLE
                    : ExactCraftPlanResult.FailureReason.UNSATISFIABLE_WITHIN_STRATEGY);
        }

        private void tryCandidate(Frame frame) {
            if (frame.inputIndex >= frame.producer.pattern.inputs().size()) {
                frame.phase = Phase.COMMIT;
                return;
            }
            CompiledInputSpec input = frame.producer.pattern.inputs().get(frame.inputIndex);
            while (frame.candidateIndex < input.candidates().size()) {
                decision();
                int candidateIndex = frame.candidateIndex++;
                CompiledCandidateSpec candidate = input.candidates().get(candidateIndex);
                AEAmount templateUnits = multiply(input.multiplier(), frame.executions);
                AEAmount consumedAmount = multiply(candidate.amountPerTemplate(), templateUnits);
                frame.candidateCheckpoint = checkpoint();
                frame.pendingSelection = new PlannedInputSelection(frame.inputIndex, candidateIndex, templateUnits,
                        candidate.key(), consumedAmount);
                frame.phase = Phase.WAIT_CHILD;
                frames.push(new Frame(candidate.key(), consumedAmount, false, frame.candidateCheckpoint));
                return;
            }
            abandonProducer(frame);
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
                frame.phase = Phase.TRY_CANDIDATE;
                return;
            }
            if (!frame.childSucceeded) {
                throw new IllegalStateException("Child frame did not report an outcome");
            }
            frame.childSucceeded = false;
            frame.selections.add(frame.pendingSelection);
            frame.pendingSelection = null;
            frame.inputIndex++;
            frame.candidateIndex = 0;
            frame.phase = frame.inputIndex == frame.producer.pattern.inputs().size()
                    ? Phase.COMMIT
                    : Phase.TRY_CANDIDATE;
        }

        private void commit(Frame frame) {
            CompiledPattern pattern = frame.producer.pattern;
            for (CompiledOutputSpec output : pattern.outputs()) {
                credit(output.key(), multiply(output.amountPerExecution(), frame.executions));
            }
            for (PlannedInputSelection selection : frame.selections) {
                CompiledCandidateSpec candidate = pattern.inputs().get(selection.inputIndex()).candidates()
                        .get(selection.candidateIndex());
                candidate.remainder().ifPresent(remainder -> creditRemainder(remainder, selection.templateUnits()));
            }
            add(executions, pattern.id(), frame.executions, PatternLimits.MAX_GRAPH_NODES);
            addBatch(pattern, frame.executions, frame.selections);
            activePatterns.remove(pattern.id());
            frame.producer = null;
            frame.remaining = consumeProduced(frame.key, frame.remaining);
            if (!frame.remaining.equals(AEAmount.ZERO)) {
                throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
            }
            succeed(frame);
        }

        private void creditRemainder(CompiledRemainderSpec remainder, AEAmount templateUnits) {
            credit(remainder.key(), multiply(remainder.amountPerTemplate(), templateUnits));
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
                // This bounded lazy-read cache is structural planner work as well as dependency observation.
                mutation();
                if (key.value() >= storage.keyCount()) {
                    throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
                }
                dependencies.recordStorageRead(key, storage.keyRevision(key));
                AEAmount amount = storage.amount(key);
                checked(amount);
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

        private AEAmount multiply(AEAmount left, AEAmount right) {
            checked(left);
            checked(right);
            if (left.equals(AEAmount.ZERO) || right.equals(AEAmount.ZERO)) {
                return AEAmount.ZERO;
            }
            long bitLengthSum = (long) left.toBigInteger().bitLength() + right.toBigInteger().bitLength();
            if (bitLengthSum - 1 > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
                throw new Abort(ExactCraftPlanResult.FailureReason.QUANTITY_LIMIT);
            }
            return checked(left.multiply(right));
        }

        private AEAmount ceilDiv(AEAmount numerator, AEAmount denominator) {
            checked(numerator);
            checked(denominator);
            return checked(numerator.ceilDiv(denominator));
        }

        private AEAmount addAmounts(AEAmount left, AEAmount right) {
            checked(left);
            checked(right);
            return checked(left.add(right));
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
            if (selections.size() != pattern.inputs().size()) {
                throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
            }
            for (int inputIndex = 0; inputIndex < selections.size(); inputIndex++) {
                PlannedInputSelection selection = selections.get(inputIndex);
                CompiledInputSpec input = pattern.inputs().get(inputIndex);
                if (selection.inputIndex() != inputIndex || selection.candidateIndex() >= input.candidates().size()) {
                    throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
                }
                CompiledCandidateSpec candidate = input.candidates().get(selection.candidateIndex());
                AEAmount expectedTemplateUnits = multiply(input.multiplier(), batchExecutions);
                AEAmount expectedConsumed = multiply(candidate.amountPerTemplate(), expectedTemplateUnits);
                if (!selection.consumedKey().equals(candidate.key())
                        || !selection.templateUnits().equals(expectedTemplateUnits)
                        || !selection.consumedAmount().equals(expectedConsumed)) {
                    throw new Abort(ExactCraftPlanResult.FailureReason.INVALID_INPUT);
                }
            }
            mutation();
            batches.add(new PlannedPatternBatch(pattern.id(), batchExecutions, selections));
            undo.add(new ListUndo<>(batches));
        }

        private void mutation() {
            if (mutations++ >= PlannerLimits.MAX_CRAFT_MUTATIONS) {
                throw new Abort(ExactCraftPlanResult.FailureReason.WORK_LIMIT);
            }
        }

        private void abandonProducer(Frame frame) {
            rollback(frame.producerCheckpoint);
            activePatterns.remove(frame.producer.id);
            frame.producer = null;
            frame.candidateIndex = 0;
            frame.phase = Phase.TRY_PRODUCER;
        }

        private void succeed(Frame frame) {
            frames.pop();
            if (frames.isEmpty()) {
                return;
            }
            Frame parent = frames.peek();
            parent.childSucceeded = true;
        }

        private void fail(Frame frame, ExactCraftPlanResult.FailureReason reason) {
            rollback(frame.entryCheckpoint);
            if (frame.producer != null)
                activePatterns.remove(frame.producer.id);
            frames.pop();
            if (frames.isEmpty()) {
                rootFailure = reason;
                return;
            }
            frames.peek().childFailure = reason;
        }
    }

    private enum Phase {
        START,
        TRY_PRODUCER,
        TRY_CANDIDATE,
        WAIT_CHILD,
        COMMIT
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
        private int producerCheckpoint;
        private AEAmount executions;
        private int inputIndex;
        private int candidateIndex;
        private int candidateCheckpoint;
        private List<PlannedInputSelection> selections;
        private PlannedInputSelection pendingSelection;
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

    private interface Undo {
        void undo();
    }

    private record MapUndo<K>(Map<K, AEAmount> map, K key, AEAmount previous) implements Undo {
        @Override
        public void undo() {
            if (previous == null) {
                map.remove(key);
            } else {
                map.put(key, previous);
            }
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

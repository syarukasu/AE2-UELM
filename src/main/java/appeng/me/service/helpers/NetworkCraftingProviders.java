package appeng.me.service.helpers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.google.common.collect.Iterators;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.Level;

import appeng.api.config.FuzzyMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.AEKeyFilter;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.crafting.pattern.AESmithingTablePattern;
import appeng.crafting.pattern.AEStonecuttingPattern;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.hooks.ticking.TickHandler;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.CompiledPatternGraph;
import appeng.rebuild.pattern.CompiledPatternGraphBuilder;
import appeng.rebuild.pattern.GraphBuildResult;
import appeng.rebuild.pattern.GraphGeneration;
import appeng.rebuild.pattern.LegacyPatternNormalizer;
import appeng.rebuild.pattern.NormalizedPatternBuildResult;
import appeng.rebuild.pattern.NormalizedPatternDiagnostic;
import appeng.rebuild.pattern.NormalizedPatternSnapshot;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternIdCreationResult;
import appeng.rebuild.pattern.PatternIdFactory;
import appeng.rebuild.pattern.PatternKind;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.pattern.PatternNormalizationResult;
import appeng.rebuild.pattern.PatternProviderRecipeReloadFailure;
import appeng.rebuild.pattern.PatternProviderRecipeReloadResult;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.pattern.PreparedPatternProviderRecipeReload;
import appeng.rebuild.pattern.RecipeRevision;

/**
 * Keeps track of the crafting patterns in the network, and related information.
 */
public class NetworkCraftingProviders {
    private final Map<IGridNode, ProviderState> craftingProviders = new HashMap<>();
    private final Map<IPatternDetails, CraftingProviderList> craftingMethods = new HashMap<>();
    private final Map<AEKey, PatternsForKey> craftableItems = new HashMap<>();
    /**
     * Used for looking up craftable alternatives using fuzzy search (i.e. ignore NBT).
     */
    private final KeyCounter craftableItemsList = new KeyCounter();
    private final Map<AEKey, Integer> emitableItems = new HashMap<>();

    private final Set<AEKey> craftableKeys = Collections.unmodifiableSet(craftableItems.keySet());
    private final Set<AEKey> emittableKeys = Collections.unmodifiableSet(emitableItems.keySet());

    private long lastModifiedOnTick = TickHandler.instance().getCurrentTick();

    public void addProvider(IGridNode node) {
        var provider = node.getService(ICraftingProvider.class);
        if (provider != null) {
            if (craftingProviders.containsKey(node)) {
                throw new IllegalArgumentException("Duplicate crafting provider registration for node " + node);
            }
            var state = new ProviderState(node, provider);
            state.mount(this);
            craftingProviders.put(node, state);
            setLastModifiedOnTick();
        }
    }

    public void removeProvider(IGridNode node) {
        var provider = node.getService(ICraftingProvider.class);
        if (provider != null) {
            var state = craftingProviders.remove(node);
            if (state != null) {
                state.unmount(this);
                setLastModifiedOnTick();
            }
        }
    }

    public Set<AEKey> getCraftables(AEKeyFilter filter) {
        var result = new HashSet<AEKey>();

        // add craftable items!
        for (var stack : this.craftableItems.keySet()) {
            if (filter.matches(stack)) {
                result.add(stack);
            }
        }

        for (var stack : this.emitableItems.keySet()) {
            if (filter.matches(stack)) {
                result.add(stack);
            }
        }

        return result;
    }

    public Set<AEKey> getCraftableKeys() {
        return craftableKeys;
    }

    public Set<AEKey> getEmittableKeys() {
        return emittableKeys;
    }

    public Collection<IPatternDetails> getCraftingFor(AEKey whatToCraft) {
        var patterns = this.craftableItems.get(whatToCraft);
        if (patterns != null) {
            return patterns.getSortedPatterns(); // The result of Stream.toList() is already unmodifiable
        }
        return Collections.emptyList();
    }

    @Nullable
    public AEKey getFuzzyCraftable(AEKey whatToCraft, AEKeyFilter filter) {
        for (var fuzzy : craftableItemsList.findFuzzy(whatToCraft, FuzzyMode.IGNORE_ALL)) {
            if (filter.matches(fuzzy.getKey())) {
                return fuzzy.getKey();
            }
        }
        return null;
    }

    public boolean canEmitFor(AEKey someItem) {
        return this.emitableItems.containsKey(someItem);
    }

    public Iterable<ICraftingProvider> getMediums(IPatternDetails key) {
        var mediumList = this.craftingMethods.get(key);
        return Objects.requireNonNullElse(mediumList, Collections.emptyList());
    }

    /**
     * Resolves the current physical binding for a sealed rebuild pattern. The caller must still reject a busy provider
     * and a changed normalized snapshot before issuing physical work.
     */
    @Nullable
    public ExactPatternBinding getExactBinding(PatternId patternId, PatternRevision revision) {
        Objects.requireNonNull(patternId, "patternId");
        Objects.requireNonNull(revision, "revision");
        for (IPatternDetails details : craftingMethods.keySet()) {
            PatternId current = previewPatternId(details);
            if (patternId.equals(current)) {
                return new ExactPatternBinding(details, getMediums(details));
            }
        }
        return null;
    }

    /** Current physical legacy binding used only to execute an already sealed exact command. */
    public record ExactPatternBinding(IPatternDetails details, Iterable<ICraftingProvider> providers) {
        public ExactPatternBinding {
            Objects.requireNonNull(details, "details");
            Objects.requireNonNull(providers, "providers");
        }
    }

    /**
     * Builds a bounded, observational normalized shadow from a stable provider-state snapshot.
     *
     * <p>
     * This method deliberately does not commit prepared provider state or mutate legacy provider maps. Legacy crafting
     * remains authoritative regardless of whether the returned snapshot is successful.
     */
    public NormalizedPatternBuildResult buildNormalizedPatternSnapshot(GraphGeneration graphGeneration,
            long serverGeneration, RecipeRevision revision, LegacyPatternNormalizer normalizer) {
        if (serverGeneration < 0) {
            throw new IllegalArgumentException("Server generation must be non-negative");
        }
        Objects.requireNonNull(graphGeneration, "graphGeneration");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(normalizer, "normalizer");

        if (craftingProviders.size() > PatternLimits.MAX_NORMALIZED_PATTERN_PROVIDERS_PER_GRID) {
            return failure(NormalizedPatternBuildResult.FailureReason.GRID_LIMIT, "providers");
        }
        List<ProviderSnapshot> providerSnapshot;
        try {
            providerSnapshot = snapshotProviderStates();
        } catch (RuntimeException exception) {
            return failure(NormalizedPatternBuildResult.FailureReason.LEGACY_EXCEPTION, "provider-snapshot");
        }
        TreeMap<PatternId, CompiledPattern> patternsById = new TreeMap<>();
        TreeMap<PatternId, Integer> maxPriorities = new TreeMap<>();
        TreeSet<NormalizedPatternDiagnostic> diagnostics = new TreeSet<>(Comparator
                .comparing((NormalizedPatternDiagnostic diagnostic) -> diagnostic.reason().name())
                .thenComparing(NormalizedPatternDiagnostic::context));
        boolean hasLegacyFallback = false;
        int inspectedBindings = 0;
        int physicalBindings = 0;
        List<PreparedBinding> eligibleBindings = new ArrayList<>();

        try {
            for (ProviderSnapshot state : providerSnapshot) {
                if (!(state.provider() instanceof PatternProviderLogic providerLogic)) {
                    hasLegacyFallback = true;
                    if (!addFallbackDiagnostic(diagnostics, "non-pattern-provider")) {
                        return failure(NormalizedPatternBuildResult.FailureReason.DIAGNOSTIC_LIMIT, "diagnostics");
                    }
                    continue;
                }

                PatternProviderRecipeReloadResult preparedResult = providerLogic.prepareRecipeReload(revision);
                if (preparedResult instanceof PatternProviderRecipeReloadFailure preparationFailure) {
                    return failure(NormalizedPatternBuildResult.FailureReason.PREPARATION_FAILURE,
                            "provider-" + preparationFailure.reason().name());
                }
                PreparedPatternProviderRecipeReload prepared = (PreparedPatternProviderRecipeReload) preparedResult;
                for (int index = 0; index < prepared.legacyPatterns().size(); index++) {
                    if (inspectedBindings >= PatternLimits.MAX_NORMALIZED_PATTERN_BINDINGS_PER_GRID) {
                        return failure(NormalizedPatternBuildResult.FailureReason.GRID_LIMIT, "pattern-bindings");
                    }
                    inspectedBindings++;
                    if (!prepared.rebuildEligible(index)) {
                        hasLegacyFallback = true;
                        if (!addFallbackDiagnostic(diagnostics, "custom-pattern")) {
                            return failure(NormalizedPatternBuildResult.FailureReason.DIAGNOSTIC_LIMIT, "diagnostics");
                        }
                        continue;
                    }
                    Level level = state.node().getLevel();
                    if (level == null) {
                        return failure(NormalizedPatternBuildResult.FailureReason.SERVER_CONTEXT, "provider-level");
                    }
                    IPatternDetails details = prepared.legacyPatterns().get(index);
                    PatternId previewId = previewPatternId(details);
                    if (previewId == null) {
                        return failure(NormalizedPatternBuildResult.FailureReason.NORMALIZATION_FAILURE,
                                "pattern-identity");
                    }
                    eligibleBindings.add(new PreparedBinding(previewId, details, level, state.priority()));
                }
            }

            eligibleBindings.sort(Comparator.comparing(PreparedBinding::patternId));
            for (PreparedBinding binding : eligibleBindings) {
                PatternNormalizationResult normalization = normalizer.normalize(binding.details(), binding.level(),
                        revision);
                if (normalization instanceof PatternNormalizationResult.Failure normalizationFailure) {
                    return failure(NormalizedPatternBuildResult.FailureReason.NORMALIZATION_FAILURE,
                            "pattern-" + normalizationFailure.reason().name());
                }
                CompiledPattern compiled = ((PatternNormalizationResult.Success) normalization).compiledPattern();
                PatternId id = compiled.id();
                if (!id.equals(binding.patternId())) {
                    return failure(NormalizedPatternBuildResult.FailureReason.PATTERN_COLLISION, "pattern-identity");
                }
                CompiledPattern existing = patternsById.get(id);
                if (existing == null) {
                    if (patternsById.size() >= PatternLimits.MAX_NORMALIZED_PATTERNS_PER_GRID) {
                        return failure(NormalizedPatternBuildResult.FailureReason.GRID_LIMIT, "patterns");
                    }
                    patternsById.put(id, compiled);
                } else if (!existing.equals(compiled)) {
                    return failure(NormalizedPatternBuildResult.FailureReason.PATTERN_COLLISION, "pattern-id");
                }
                if (physicalBindings >= PatternLimits.MAX_NORMALIZED_PATTERN_BINDINGS_PER_GRID) {
                    return failure(NormalizedPatternBuildResult.FailureReason.GRID_LIMIT, "physical-bindings");
                }
                maxPriorities.merge(id, binding.priority(), Math::max);
                physicalBindings++;
            }
        } catch (RuntimeException exception) {
            return failure(NormalizedPatternBuildResult.FailureReason.LEGACY_EXCEPTION, "legacy-callback");
        }

        GraphBuildResult graphResult;
        try {
            graphResult = new CompiledPatternGraphBuilder(graphGeneration, normalizer.keyRegistryGeneration())
                    .build(patternsById.values());
        } catch (RuntimeException exception) {
            return failure(NormalizedPatternBuildResult.FailureReason.GRAPH_FAILURE, "graph-runtime");
        }
        if (graphResult instanceof GraphBuildResult.Failure graphFailure) {
            return failure(NormalizedPatternBuildResult.FailureReason.GRAPH_FAILURE,
                    "graph-" + graphFailure.reason().name());
        }
        CompiledPatternGraph graph = ((GraphBuildResult.Success) graphResult).graph();
        return new NormalizedPatternBuildResult.Success(new NormalizedPatternSnapshot(serverGeneration, revision,
                normalizer.keyRegistryGeneration(), patternsById, graph, maxPriorities, physicalBindings,
                hasLegacyFallback, List.copyOf(diagnostics)));
    }

    private static boolean addFallbackDiagnostic(Set<NormalizedPatternDiagnostic> diagnostics, String context) {
        NormalizedPatternDiagnostic diagnostic = new NormalizedPatternDiagnostic(
                NormalizedPatternDiagnostic.Reason.LEGACY_FALLBACK, context);
        if (diagnostics.contains(diagnostic)) {
            return true;
        }
        if (diagnostics.size() >= PatternLimits.MAX_NORMALIZED_PATTERN_DIAGNOSTICS) {
            return false;
        }
        diagnostics.add(diagnostic);
        return true;
    }

    private static NormalizedPatternBuildResult.Failure failure(NormalizedPatternBuildResult.FailureReason reason,
            String context) {
        return new NormalizedPatternBuildResult.Failure(reason, context);
    }

    @Nullable
    private static PatternId previewPatternId(IPatternDetails details) {
        PatternKind kind = patternKind(details);
        if (kind == null) {
            return null;
        }
        AEItemKey definition = details.getDefinition();
        if (definition == null) {
            return null;
        }
        PatternIdCreationResult result = PatternIdFactory.create(kind, definition);
        return result instanceof PatternIdCreationResult.Success success ? success.patternId() : null;
    }

    @Nullable
    private static PatternKind patternKind(IPatternDetails details) {
        Class<?> type = details.getClass();
        if (type == AECraftingPattern.class) {
            return PatternKind.CRAFTING;
        }
        if (type == AEProcessingPattern.class) {
            return PatternKind.PROCESSING;
        }
        if (type == AESmithingTablePattern.class) {
            return PatternKind.SMITHING;
        }
        return type == AEStonecuttingPattern.class ? PatternKind.STONECUTTING : null;
    }

    private List<ProviderSnapshot> snapshotProviderStates() {
        List<ProviderSnapshot> snapshot = new ArrayList<>(craftingProviders.size());
        for (ProviderState state : craftingProviders.values()) {
            snapshot.add(new ProviderSnapshot(state.node, state.provider, state.priority));
        }
        return List.copyOf(snapshot);
    }

    private static class CraftingProviderList implements Iterable<ICraftingProvider> {
        private final List<ICraftingProvider> providers = new ArrayList<>();
        /**
         * Cycling iterator for round-robin. Has to be refreshed after every addition or removal to providers to prevent
         * CMEs.
         */
        private Iterator<ICraftingProvider> cycleIterator = Iterators.cycle(providers);

        private void add(ICraftingProvider provider) {
            providers.add(provider);
            cycleIterator = Iterators.cycle(providers);
        }

        private void remove(ICraftingProvider provider) {
            providers.remove(provider);
            cycleIterator = Iterators.cycle(providers);
        }

        @Override
        public Iterator<ICraftingProvider> iterator() {
            return Iterators.limit(cycleIterator, providers.size());
        }
    }

    private static class ProviderState {
        private final IGridNode node;
        private final ICraftingProvider provider;
        private final Set<AEKey> emitableItems;
        private final List<IPatternDetails> patterns;
        private final int priority;

        private ProviderState(IGridNode node, ICraftingProvider provider) {
            this.node = node;
            this.provider = provider;
            this.emitableItems = new HashSet<>(provider.getEmitableItems());
            this.patterns = new ArrayList<>(provider.getAvailablePatterns());
            this.priority = provider.getPatternPriority();
        }

        private void mount(NetworkCraftingProviders methods) {
            for (var emitable : emitableItems) {
                methods.emitableItems.merge(emitable, 1, Integer::sum);
            }
            for (var pattern : patterns) {
                // output -> pattern (for simulation)
                var primaryOutput = pattern.getPrimaryOutput();

                methods.craftableItemsList.add(primaryOutput.what(), 1);

                var patternsForKey = methods.craftableItems.computeIfAbsent(primaryOutput.what(),
                        k -> new PatternsForKey());
                patternsForKey.patterns.add(new PatternInfo(pattern, this));
                patternsForKey.needsSorting = true;

                // pattern -> method (for execution)
                methods.craftingMethods.computeIfAbsent(pattern, d -> new CraftingProviderList()).add(provider);
            }
        }

        private void unmount(NetworkCraftingProviders methods) {
            for (var emitable : emitableItems) {
                methods.emitableItems.compute(emitable, (key, cnt) -> cnt == 1 ? null : cnt - 1);
            }
            for (var pattern : patterns) {
                var primaryOutput = pattern.getPrimaryOutput();

                methods.craftableItemsList.remove(primaryOutput.what(), 1);

                methods.craftableItems.computeIfPresent(primaryOutput.what(), (key, patternsForKey) -> {
                    patternsForKey.patterns.remove(new PatternInfo(pattern, this));
                    patternsForKey.needsSorting = true;
                    return patternsForKey.patterns.isEmpty() ? null : patternsForKey;
                });

                methods.craftingMethods.computeIfPresent(pattern, (pat, list) -> {
                    list.remove(provider);
                    return list.providers.isEmpty() ? null : list;
                });
            }
        }
    }

    private record ProviderSnapshot(IGridNode node, ICraftingProvider provider, int priority) {
        private ProviderSnapshot {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(provider, "provider");
        }
    }

    private record PreparedBinding(PatternId patternId, IPatternDetails details, Level level, int priority) {
    }

    private static class PatternsForKey {
        private final Set<PatternInfo> patterns = new HashSet<>();
        private List<IPatternDetails> sortedPatterns = Collections.emptyList();
        private boolean needsSorting = false;

        private void sortPatterns() {
            sortedPatterns = patterns.stream()
                    .sorted(Comparator.comparingInt((PatternInfo pi) -> pi.state.priority).reversed())
                    .map(PatternInfo::pattern)
                    .distinct()
                    .toList();
        }

        private List<IPatternDetails> getSortedPatterns() {
            if (needsSorting) {
                sortPatterns();
            }
            return sortedPatterns;
        }
    }

    private record PatternInfo(IPatternDetails pattern, ProviderState state) {
    }

    private void setLastModifiedOnTick() {
        lastModifiedOnTick = TickHandler.instance().getCurrentTick();
    }

    /**
     * @see TickHandler#getCurrentTick()
     */
    public long getLastModifiedOnTick() {
        return lastModifiedOnTick;
    }
}

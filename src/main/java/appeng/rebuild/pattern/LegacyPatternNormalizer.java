package appeng.rebuild.pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetails.IInput;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.crafting.pattern.AESmithingTablePattern;
import appeng.crafting.pattern.AEStonecuttingPattern;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.quantity.AEAmount;

/**
 * Server-thread-confined normalizer for AE2's four built-in legacy pattern implementations.
 *
 * <p>
 * Each normalization confirms the supplied level's server thread before it reads legacy state, then fully validates
 * that state before it assigns any {@link KeyId KeyIds}; unsupported custom patterns retain the legacy fallback path.
 */
public final class LegacyPatternNormalizer {
    private static final String PROCESSING_BACKEND_TYPE = "ae2-processing";
    private static final String PROCESSING_CAPABILITY = "external-inputs";

    private final KeyRegistry keyRegistry;

    /** Creates a server-thread-only normalizer backed by {@code keyRegistry}. */
    public LegacyPatternNormalizer(KeyRegistry keyRegistry) {
        this.keyRegistry = Objects.requireNonNull(keyRegistry, "keyRegistry");
    }

    /** Returns the key-registry generation assigned to all successful compiled patterns. */
    public long keyRegistryGeneration() {
        return keyRegistry.generation();
    }

    /**
     * Normalizes a built-in pattern on its owning server thread. All legacy callbacks run before the first registry
     * mutation; runtime failures from those callbacks become bounded typed failures.
     */
    public PatternNormalizationResult normalize(IPatternDetails details, Level level, RecipeRevision recipeRevision) {
        Objects.requireNonNull(details, "details");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(recipeRevision, "recipeRevision");
        assertServerThread(level);

        PatternKind kind = patternKind(details);
        if (kind == null) {
            return failure(PatternNormalizationResult.FailureReason.UNSUPPORTED_PATTERN, "pattern-type");
        }

        ReadPattern read;
        try {
            read = readLegacyPattern(details, level);
        } catch (RuntimeException exception) {
            return failure(PatternNormalizationResult.FailureReason.LEGACY_EXCEPTION, "legacy-callback");
        }
        if (read.failure != null) {
            return read.failure;
        }

        PatternIdCreationResult identity = PatternIdFactory.create(kind, read.definition);
        if (identity instanceof PatternIdCreationResult.Failure failure) {
            return failure(PatternNormalizationResult.FailureReason.IDENTITY_FAILURE, failure.context());
        }
        PatternId patternId = ((PatternIdCreationResult.Success) identity).patternId();

        PatternDefinition definition;
        try {
            Optional<MachineIntent> machineIntent = kind == PatternKind.PROCESSING
                    ? Optional.of(new MachineIntent(PROCESSING_BACKEND_TYPE, patternId.value(), PROCESSING_CAPABILITY,
                            Map.of()))
                    : Optional.empty();
            definition = new PatternDefinition(patternId, kind, read.inputs, read.outputs, machineIntent,
                    new PatternRevision(recipeRevision.value()));
        } catch (IllegalArgumentException exception) {
            return failure(PatternNormalizationResult.FailureReason.INVALID_DEFINITION, "normalized-definition");
        }

        return PatternNormalizationResult.success(definition, compileAndIntern(definition));
    }

    private ReadPattern readLegacyPattern(IPatternDetails details, Level level) {
        AEItemKey definition = details.getDefinition();
        if (definition == null) {
            return ReadPattern.failure(failure(PatternNormalizationResult.FailureReason.INVALID_DEFINITION,
                    "definition"));
        }

        IInput[] legacyInputs = details.getInputs();
        if (legacyInputs == null) {
            return ReadPattern.failure(failure(PatternNormalizationResult.FailureReason.INVALID_INPUT, "inputs"));
        }
        if (legacyInputs.length > PatternLimits.MAX_INPUT_GROUPS) {
            return ReadPattern.failure(failure(PatternNormalizationResult.FailureReason.SHAPE_LIMIT, "inputs"));
        }

        List<InputSpec> inputs = new ArrayList<>(legacyInputs.length);
        int totalCandidates = 0;
        for (IInput legacyInput : legacyInputs) {
            if (legacyInput == null) {
                return ReadPattern.failure(failure(PatternNormalizationResult.FailureReason.INVALID_INPUT, "input"));
            }
            GenericStack[] possibleInputs = legacyInput.getPossibleInputs();
            if (possibleInputs == null || possibleInputs.length == 0) {
                return ReadPattern.failure(failure(PatternNormalizationResult.FailureReason.INVALID_INPUT,
                        "possible-inputs"));
            }
            if (possibleInputs.length > PatternLimits.MAX_CANDIDATES_PER_INPUT
                    || possibleInputs.length > PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN - totalCandidates) {
                return ReadPattern.failure(failure(PatternNormalizationResult.FailureReason.SHAPE_LIMIT, "candidates"));
            }
            totalCandidates += possibleInputs.length;

            long multiplier = legacyInput.getMultiplier();
            if (multiplier <= 0) {
                return ReadPattern.failure(failure(PatternNormalizationResult.FailureReason.INVALID_INPUT,
                        "input-multiplier"));
            }

            List<CandidateSpec> candidates = new ArrayList<>(possibleInputs.length);
            for (GenericStack candidate : possibleInputs) {
                if (candidate == null || candidate.amount() <= 0) {
                    return ReadPattern.failure(failure(PatternNormalizationResult.FailureReason.INVALID_INPUT,
                            "candidate"));
                }
                AEKey candidateKey = candidate.what();
                if (candidateKey == null) {
                    return ReadPattern.failure(failure(PatternNormalizationResult.FailureReason.INVALID_INPUT,
                            "candidate-key"));
                }
                if (!legacyInput.isValid(candidateKey, level)) {
                    continue;
                }
                AEKey remainderKey = legacyInput.getRemainingKey(candidateKey);
                Optional<RemainderSpec> remainder = remainderKey == null
                        ? Optional.empty()
                        : Optional.of(new RemainderSpec(remainderKey, AEAmount.ONE));
                candidates.add(new CandidateSpec(candidateKey, AEAmount.of(candidate.amount()), remainder));
            }
            if (candidates.isEmpty()) {
                return ReadPattern.failure(failure(PatternNormalizationResult.FailureReason.INVALID_INPUT,
                        "valid-candidates"));
            }
            SubstitutionPolicy policy = possibleInputs.length == 1
                    ? SubstitutionPolicy.EXACT
                    : SubstitutionPolicy.ALLOW_ALTERNATIVES;
            inputs.add(new InputSpec(candidates, AEAmount.of(multiplier), policy));
        }

        GenericStack[] legacyOutputs = details.getOutputs();
        if (legacyOutputs == null || legacyOutputs.length == 0) {
            return ReadPattern.failure(failure(PatternNormalizationResult.FailureReason.INVALID_OUTPUT, "outputs"));
        }
        if (legacyOutputs.length > PatternLimits.MAX_OUTPUTS) {
            return ReadPattern.failure(failure(PatternNormalizationResult.FailureReason.SHAPE_LIMIT, "outputs"));
        }
        List<OutputSpec> outputs = new ArrayList<>(legacyOutputs.length);
        for (int index = 0; index < legacyOutputs.length; index++) {
            GenericStack output = legacyOutputs[index];
            if (output == null || output.amount() <= 0) {
                return ReadPattern.failure(failure(PatternNormalizationResult.FailureReason.INVALID_OUTPUT, "output"));
            }
            AEKey outputKey = output.what();
            if (outputKey == null) {
                return ReadPattern.failure(failure(PatternNormalizationResult.FailureReason.INVALID_OUTPUT,
                        "output-key"));
            }
            outputs.add(new OutputSpec(outputKey, AEAmount.of(output.amount()), index == 0));
        }
        return new ReadPattern(definition, inputs, outputs, null);
    }

    private CompiledPattern compileAndIntern(PatternDefinition definition) {
        List<CompiledInputSpec> inputs = new ArrayList<>(definition.inputs().size());
        for (InputSpec input : definition.inputs()) {
            List<CompiledCandidateSpec> candidates = new ArrayList<>(input.candidates().size());
            for (CandidateSpec candidate : input.candidates()) {
                KeyId candidateKey = keyRegistry.intern(candidate.key());
                Optional<CompiledRemainderSpec> remainder = candidate.remainder()
                        .map(source -> new CompiledRemainderSpec(keyRegistry.intern(source.key()),
                                source.amountPerTemplate()));
                candidates.add(new CompiledCandidateSpec(candidateKey, candidate.amountPerTemplate(), remainder));
            }
            inputs.add(new CompiledInputSpec(candidates, input.multiplier(), input.policy()));
        }

        List<CompiledOutputSpec> outputs = new ArrayList<>(definition.outputs().size());
        for (OutputSpec output : definition.outputs()) {
            outputs.add(new CompiledOutputSpec(keyRegistry.intern(output.key()), output.amountPerExecution(),
                    output.primary()));
        }
        return new CompiledPattern(definition.id(), definition.kind(), inputs, outputs, definition.machineIntent(),
                definition.revision(), keyRegistry.generation());
    }

    private static PatternKind patternKind(IPatternDetails details) {
        if (details.getClass() == AECraftingPattern.class) {
            return PatternKind.CRAFTING;
        }
        if (details.getClass() == AEProcessingPattern.class) {
            return PatternKind.PROCESSING;
        }
        if (details.getClass() == AESmithingTablePattern.class) {
            return PatternKind.SMITHING;
        }
        if (details.getClass() == AEStonecuttingPattern.class) {
            return PatternKind.STONECUTTING;
        }
        return null;
    }

    private static void assertServerThread(Level level) {
        MinecraftServer server = level.getServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("LegacyPatternNormalizer must run on the supplied level's server thread");
        }
    }

    private static PatternNormalizationResult.Failure failure(PatternNormalizationResult.FailureReason reason,
            String context) {
        return (PatternNormalizationResult.Failure) PatternNormalizationResult.failure(reason, context);
    }

    private record ReadPattern(AEItemKey definition, List<InputSpec> inputs, List<OutputSpec> outputs,
            PatternNormalizationResult.Failure failure) {
        private static ReadPattern failure(PatternNormalizationResult.Failure failure) {
            return new ReadPattern(null, List.of(), List.of(), failure);
        }
    }
}

package appeng.rebuild.pattern;

import static appeng.rebuild.pattern.PatternTestFixtures.compiled;
import static appeng.rebuild.pattern.PatternTestFixtures.compiledOutput;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import appeng.rebuild.key.KeyId;

/** Deterministic, generation-scoped primary-output producer index tests. */
class ProducerIndexTest {

    @Test
    void orderingIsIndependentOfInputPatternOrderAndSecondaryOutputsAreIgnored() {
        KeyId firstKey = new KeyId(0);
        KeyId secondaryKey = new KeyId(1);
        CompiledPattern zulu = compiled(
                new PatternId("zulu"),
                7L,
                List.of(),
                List.of(compiledOutput(firstKey, 1L, true), compiledOutput(secondaryKey, 2L, false)));
        CompiledPattern alpha = compiled(
                new PatternId("alpha"),
                7L,
                List.of(),
                List.of(compiledOutput(firstKey, 1L, true)));
        CompiledPattern beta = compiled(
                new PatternId("beta"),
                7L,
                List.of(),
                List.of(compiledOutput(secondaryKey, 1L, true)));

        ProducerIndex withoutBeta = new ProducerIndex(7L, List.of(zulu, alpha));
        ProducerIndex reordered = new ProducerIndex(7L, List.of(beta, alpha, zulu));

        assertEquals(List.of(new PatternId("alpha"), new PatternId("zulu")),
                withoutBeta.producers(firstKey));
        assertEquals(List.of(), withoutBeta.producers(secondaryKey));
        assertEquals(List.of(new PatternId("alpha"), new PatternId("zulu")),
                reordered.producers(firstKey));
        assertEquals(List.of(new PatternId("beta")), reordered.producers(secondaryKey));
    }

    @Test
    void emptyAndMissingLookupsReturnEmptyImmutableLists() {
        ProducerIndex index = new ProducerIndex(8L, List.of());

        assertEquals(8L, index.keyRegistryGeneration());
        assertEquals(List.of(), index.producers(new KeyId(0)));
        assertThrows(UnsupportedOperationException.class, () -> index.producers(new KeyId(0)).clear());
    }

    @Test
    void rejectsDuplicatePatternIdsAndMixedRegistryGenerations() {
        KeyId outputKey = new KeyId(0);
        CompiledPattern first = compiled(
                new PatternId("duplicate"), 9L, List.of(), List.of(compiledOutput(outputKey, 1L, true)));
        CompiledPattern duplicate = compiled(
                new PatternId("duplicate"), 9L, List.of(), List.of(compiledOutput(outputKey, 2L, true)));
        CompiledPattern mixedGeneration = compiled(
                new PatternId("mixed"), 10L, List.of(), List.of(compiledOutput(outputKey, 1L, true)));

        assertThrows(IllegalArgumentException.class, () -> new ProducerIndex(9L, List.of(first, duplicate)));
        assertThrows(IllegalArgumentException.class, () -> new ProducerIndex(9L, List.of(first, mixedGeneration)));
    }
}

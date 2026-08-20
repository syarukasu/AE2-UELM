package appeng.rebuild.addon;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;

class StandardAddonMachineExactAdapterTest {
    @Test
    void dispatchesOneValidatedStandardPatternCommand() {
        ICraftingProvider provider = mock(ICraftingProvider.class);
        AEProcessingPattern details = mock(AEProcessingPattern.class);
        KeyCounter[] inputs = { new KeyCounter() };
        when(provider.pushPattern(details, inputs)).thenReturn(true);

        assertTrue(StandardAddonMachineExactAdapter.pushPattern(provider, details, inputs));
        verify(provider).pushPattern(details, inputs);
    }

    @Test
    void rejectsUnknownCustomPatternBeforeProviderCallback() {
        ICraftingProvider provider = mock(ICraftingProvider.class);
        IPatternDetails details = mock(IPatternDetails.class);

        assertThrows(IllegalArgumentException.class,
                () -> StandardAddonMachineExactAdapter.pushPattern(provider, details, new KeyCounter[0]));
    }
}

package appeng.rebuild.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Recipe-source revision boundary tests. */
class RecipeRevisionTest {
    @Test
    void zeroAndNextAreExact() {
        assertEquals(new RecipeRevision(0L), RecipeRevision.ZERO);
        assertEquals(new RecipeRevision(1L), RecipeRevision.ZERO.next());
        assertEquals(new RecipeRevision(Long.MAX_VALUE), new RecipeRevision(Long.MAX_VALUE - 1L).next());
    }

    @Test
    void negativeRevisionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RecipeRevision(-1L));
    }

    @Test
    void maxRevisionOverflowFailsClosed() {
        assertThrows(ArithmeticException.class, () -> new RecipeRevision(Long.MAX_VALUE).next());
    }
}

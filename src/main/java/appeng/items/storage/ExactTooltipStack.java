package appeng.items.storage;

import java.util.Objects;

import appeng.api.stacks.AEKey;
import appeng.rebuild.quantity.AEAmount;

/** Exact quantity retained by storage-cell visual tooltips. */
public record ExactTooltipStack(AEKey what, AEAmount amount) {
    public ExactTooltipStack {
        Objects.requireNonNull(what, "what");
        Objects.requireNonNull(amount, "amount");
    }
}

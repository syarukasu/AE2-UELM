/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2021, TeamAppliedEnergistics, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.menu.me.crafting;

import java.util.Comparator;

import net.minecraft.network.FriendlyByteBuf;

import appeng.api.stacks.AEKey;
import appeng.rebuild.api.exact.ExactAmountCodec;
import appeng.rebuild.quantity.AEAmount;

/**
 * Describes an entry in the crafting plan which describes how many items of one type are missing, already stored in the
 * network, or have to be crafted.
 */
public class CraftingPlanSummaryEntry implements Comparable<CraftingPlanSummaryEntry> {
    private static final Comparator<CraftingPlanSummaryEntry> COMPARATOR = Comparator
            .comparing(CraftingPlanSummaryEntry::getMissingAmount)
            .thenComparing(CraftingPlanSummaryEntry::getCraftAmount)
            .thenComparing(CraftingPlanSummaryEntry::getStoredAmount)
            .thenComparing(CraftingPlanSummaryEntry::getAvailableAmount)
            .reversed();

    private final AEKey what;
    private final AEAmount missingAmount;
    private final AEAmount storedAmount;
    private final AEAmount craftAmount;
    private final AEAmount availableAmount;

    public CraftingPlanSummaryEntry(AEKey what, AEAmount missingAmount, AEAmount storedAmount, AEAmount craftAmount,
            AEAmount availableAmount) {
        this.what = what;
        this.missingAmount = missingAmount;
        this.storedAmount = storedAmount;
        this.craftAmount = craftAmount;
        this.availableAmount = availableAmount;
    }

    @Deprecated
    public CraftingPlanSummaryEntry(AEKey what, long missingAmount, long storedAmount, long craftAmount) {
        this(what, AEAmount.of(missingAmount), AEAmount.of(storedAmount), AEAmount.of(craftAmount),
                AEAmount.of(storedAmount));
    }

    public AEKey getWhat() {
        return what;
    }

    public AEAmount getMissingAmount() {
        return missingAmount;
    }

    public AEAmount getStoredAmount() {
        return storedAmount;
    }

    public AEAmount getCraftAmount() {
        return craftAmount;
    }

    public AEAmount getAvailableAmount() {
        return availableAmount;
    }

    @Override
    public int compareTo(final CraftingPlanSummaryEntry o) {
        return COMPARATOR.compare(this, o);
    }

    public void write(FriendlyByteBuf buffer) {
        AEKey.writeKey(buffer, what);
        ExactAmountCodec.write(buffer, missingAmount);
        ExactAmountCodec.write(buffer, storedAmount);
        ExactAmountCodec.write(buffer, craftAmount);
        ExactAmountCodec.write(buffer, availableAmount);
    }

    public static CraftingPlanSummaryEntry read(FriendlyByteBuf buffer) {
        var what = AEKey.readKey(buffer);
        AEAmount missingAmount = readAmount(buffer);
        AEAmount storedAmount = readAmount(buffer);
        AEAmount craftAmount = readAmount(buffer);
        AEAmount availableAmount = readAmount(buffer);
        return new CraftingPlanSummaryEntry(what, missingAmount, storedAmount, craftAmount, availableAmount);
    }

    private static AEAmount readAmount(FriendlyByteBuf buffer) {
        var decoded = ExactAmountCodec.read(buffer);
        if (decoded instanceof ExactAmountCodec.Success success) {
            return success.amount();
        }
        throw new IllegalArgumentException("Malformed exact crafting plan amount");
    }
}

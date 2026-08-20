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

package appeng.client.gui.me.crafting;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.client.gui.AEBaseScreen;
import appeng.core.localization.GuiText;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import appeng.rebuild.api.legacy.LegacyAmountProjection;
import appeng.rebuild.quantity.AEAmount;
import appeng.util.NumberUtil;

public class CraftConfirmTableRenderer extends AbstractTableRenderer<CraftingPlanSummaryEntry> {

    public CraftConfirmTableRenderer(AEBaseScreen<?> screen, int x, int y) {
        super(screen, x, y, 5);
    }

    @Override
    protected List<Component> getEntryDescription(CraftingPlanSummaryEntry entry) {
        List<Component> lines = new ArrayList<>(3);
        if (positive(entry.getStoredAmount())) {
            String amount = format(entry.getWhat(), entry.getStoredAmount(), AmountFormat.SLOT);
            lines.add(GuiText.FromStorage.text(amount));
        }

        if (positive(entry.getMissingAmount())) {
            String amount = format(entry.getWhat(), entry.getMissingAmount(), AmountFormat.SLOT);
            lines.add(GuiText.Missing.text(amount));
        }

        if (positive(entry.getCraftAmount())) {
            String amount = format(entry.getWhat(), entry.getCraftAmount(), AmountFormat.SLOT);
            lines.add(GuiText.ToCraft.text(amount));
        }
        // same check as we want percentage to be the last element
        if (positive(entry.getStoredAmount())) {
            var hasMissing = positive(entry.getMissingAmount());
            var percentage = NumberUtil.coloredPercentage(
                    LegacyAmountProjection
                            .saturatingLong(hasMissing ? entry.getMissingAmount() : entry.getStoredAmount()),
                    LegacyAmountProjection.saturatingLong(entry.getAvailableAmount()),
                    hasMissing);
            lines.add(GuiText.UsedAmount.text(percentage).withStyle(percentage.getStyle())); // style the entire
                                                                                             // component instead of
                                                                                             // only the number
        }
        return lines;
    }

    @Override
    protected AEKey getEntryStack(CraftingPlanSummaryEntry entry) {
        return entry.getWhat();
    }

    @Override
    protected List<Component> getEntryTooltip(CraftingPlanSummaryEntry entry) {
        List<Component> lines = AEKeyRendering.getTooltip(entry.getWhat());

        // The tooltip compares the unabbreviated amounts
        if (positive(entry.getStoredAmount())) {
            lines.add(GuiText.FromStorage
                    .text(format(entry.getWhat(), entry.getStoredAmount(), AmountFormat.FULL)));
        }
        if (positive(entry.getMissingAmount())) {
            lines.add(GuiText.Missing.text(
                    format(entry.getWhat(), entry.getMissingAmount(), AmountFormat.FULL)));
        }
        if (positive(entry.getCraftAmount())) {
            lines.add(GuiText.ToCraft
                    .text(format(entry.getWhat(), entry.getCraftAmount(), AmountFormat.FULL)));
        }
        // same check as we want percentage to be the last element
        if (positive(entry.getStoredAmount())) {
            var hasMissing = positive(entry.getMissingAmount());
            var percentage = NumberUtil.coloredPercentage(
                    LegacyAmountProjection
                            .saturatingLong(hasMissing ? entry.getMissingAmount() : entry.getStoredAmount()),
                    LegacyAmountProjection.saturatingLong(entry.getAvailableAmount()),
                    hasMissing);
            lines.add(GuiText.UsedAmount.text(percentage).withStyle(percentage.getStyle())); // style the entire
                                                                                             // component instead of
                                                                                             // only the number
        }
        return lines;

    }

    @Override
    protected int getEntryOverlayColor(CraftingPlanSummaryEntry entry) {
        return positive(entry.getMissingAmount()) ? 0x1AFF0000 : 0;
    }

    private static boolean positive(AEAmount amount) {
        return amount.compareTo(AEAmount.ZERO) > 0;
    }

    private static String format(AEKey key, AEAmount amount, AmountFormat format) {
        var projected = LegacyAmountProjection.project(amount);
        return projected.saturated() ? amount.toString() : key.formatAmount(projected.amount(), format);
    }

}

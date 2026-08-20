/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
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

package appeng.core.sync.packets;

import io.netty.buffer.Unpooled;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import appeng.core.sync.BasePacket;
import appeng.menu.me.crafting.CraftAmountMenu;
import appeng.rebuild.api.exact.ExactAmountCodec;
import appeng.rebuild.quantity.AEAmount;

public class ConfirmAutoCraftPacket extends BasePacket {

    private final AEAmount amount;
    private final boolean valid;
    private final boolean craftMissingAmount;
    private final boolean autoStart;

    public ConfirmAutoCraftPacket(FriendlyByteBuf stream) {
        this.autoStart = stream.readBoolean();
        this.craftMissingAmount = stream.readBoolean();
        var decoded = ExactAmountCodec.read(stream);
        this.valid = decoded instanceof ExactAmountCodec.Success;
        this.amount = valid ? ((ExactAmountCodec.Success) decoded).amount() : AEAmount.ZERO;
    }

    public ConfirmAutoCraftPacket(AEAmount craftAmt, boolean craftMissingAmount, boolean autoStart) {
        this.amount = craftAmt;
        this.valid = !craftAmt.equals(AEAmount.ZERO);
        this.craftMissingAmount = craftMissingAmount;
        this.autoStart = autoStart;

        final FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.buffer());
        data.writeInt(this.getPacketID());
        data.writeBoolean(autoStart);
        data.writeBoolean(craftMissingAmount);
        ExactAmountCodec.write(data, this.amount);
        this.configureWrite(data);
    }

    @Override
    public void serverPacketData(ServerPlayer player) {
        if (valid && player.containerMenu instanceof CraftAmountMenu menu) {
            menu.confirm(amount, craftMissingAmount, autoStart);
        }
    }
}

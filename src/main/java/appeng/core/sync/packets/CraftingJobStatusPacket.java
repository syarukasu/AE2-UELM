package appeng.core.sync.packets;

import java.util.UUID;

import io.netty.buffer.Unpooled;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;

import appeng.api.stacks.AEKey;
import appeng.client.gui.me.common.PendingCraftingJobs;
import appeng.client.gui.me.common.PinnedKeys;
import appeng.core.AEConfig;
import appeng.core.sync.BasePacket;
import appeng.rebuild.api.exact.ExactAmountCodec;
import appeng.rebuild.quantity.AEAmount;

/**
 * Confirms to the player that a crafting job has started.
 */
public class CraftingJobStatusPacket extends BasePacket {
    /**
     * What is being crafted.
     */
    private UUID jobId;
    private AEKey what;
    private AEAmount requestedAmount;
    private AEAmount remainingAmount;
    private long elapsedTime;
    private Status status;

    public CraftingJobStatusPacket(FriendlyByteBuf stream) {
        this.jobId = stream.readUUID();
        this.status = stream.readEnum(Status.class);
        this.what = AEKey.readKey(stream);
        this.requestedAmount = readAmount(stream);
        this.remainingAmount = readAmount(stream);
        this.elapsedTime = stream.readLong();
    }

    @Deprecated
    public CraftingJobStatusPacket(UUID jobId, AEKey what, long requestedAmount, long remainingAmount,
            Status status) {
        this(jobId, what, requestedAmount, remainingAmount, 0, status);
    }

    public CraftingJobStatusPacket(UUID jobId, AEKey what, long requestedAmount, long remainingAmount,
            long elapsedTime, Status status) {
        this(jobId, what, AEAmount.of(requestedAmount), AEAmount.of(remainingAmount), elapsedTime, status);
    }

    public CraftingJobStatusPacket(UUID jobId, AEKey what, AEAmount requestedAmount, AEAmount remainingAmount,
            long elapsedTime, Status status) {
        var data = new FriendlyByteBuf(Unpooled.buffer());
        data.writeInt(getPacketID());
        data.writeUUID(jobId);
        data.writeEnum(status);
        AEKey.writeKey(data, what);
        ExactAmountCodec.write(data, requestedAmount);
        ExactAmountCodec.write(data, remainingAmount);
        data.writeLong(elapsedTime);
        this.configureWrite(data);
    }

    private static AEAmount readAmount(FriendlyByteBuf data) {
        var decoded = ExactAmountCodec.read(data);
        if (decoded instanceof ExactAmountCodec.Success success) {
            return success.amount();
        }
        throw new IllegalArgumentException("Malformed exact crafting status amount");
    }

    @Override
    public void clientPacketData(Player player) {
        if (status == Status.STARTED) {
            if (AEConfig.instance().isPinAutoCraftedItems()) {
                PinnedKeys.pinKey(what, PinnedKeys.PinReason.CRAFTING);
            }
        }

        PendingCraftingJobs.jobStatus(jobId, what, requestedAmount, remainingAmount, elapsedTime, status);
    }

    public enum Status {
        STARTED,
        CANCELLED,
        FINISHED
    }
}

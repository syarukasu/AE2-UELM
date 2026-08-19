package appeng.server.subcommands;

import static net.minecraft.commands.Commands.argument;

import java.util.UUID;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import appeng.rebuild.cell.ExactCellId;
import appeng.rebuild.cell.ExactCellStorageManager;
import appeng.server.ISubCommand;

/** Administrator recovery for a lost UUID-backed exact cell. Recovery always rotates the UUID. */
public final class ExactCellRecoveryCommand implements ISubCommand {
    @Override
    public void addArguments(LiteralArgumentBuilder<CommandSourceStack> builder) {
        builder.then(argument("uuid", StringArgumentType.word()).executes(context -> recover(context)));
    }

    @Override
    public void call(MinecraftServer server, CommandContext<CommandSourceStack> context,
            CommandSourceStack sender) {
        sender.sendFailure(Component.literal("Usage: /ae2 cellrecover <uuid>"));
    }

    private int recover(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            UUID lostId = UUID.fromString(StringArgumentType.getString(context, "uuid"));
            ItemStack recovered = ExactCellStorageManager.get(source.getServer())
                    .recoverAndRotate(new ExactCellId(lostId));
            if (!player.addItem(recovered)) {
                player.drop(recovered, false);
            }
            UUID replacement = recovered.getOrCreateTag().getUUID(ExactCellStorageManager.CELL_ID_TAG);
            source.sendSuccess(() -> Component.literal("Recovered exact cell as " + replacement), true);
            return 1;
        } catch (RuntimeException failure) {
            source.sendFailure(Component.literal("Exact cell recovery failed: " + failure.getMessage()));
            return 0;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
            source.sendFailure(Component.literal("Exact cell recovery requires a player command source"));
            return 0;
        }
    }
}

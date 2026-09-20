package net.flameslight.zones.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.flameslight.zones.config.WorldZoneConfig;
import net.flameslight.zones.types.ZoneInstance;
import net.flameslight.zones.ZoneManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class ZoneLocateCommand {
    private static final DynamicCommandExceptionType UNKNOWN_ZONE = new DynamicCommandExceptionType(
            id -> Component.literal("Unknown zone id '" + id));
    private static final DynamicCommandExceptionType NOT_FOUND = new DynamicCommandExceptionType(
            id -> Component.literal("No instance of zone '" + id + "' exists in this dimension."));

    private ZoneLocateCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locatezone")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("zoneId", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                WorldZoneConfig.getParsedDefinitions().stream().map(d -> d.id).distinct(), builder))
                        .executes(ZoneLocateCommand::run)));
    }

    private static int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String zoneId = StringArgumentType.getString(context, "zoneId");
        CommandSourceStack source = context.getSource();
        ResourceLocation dimension = source.getLevel().dimension().location();

        List<ZoneInstance> zones = ZoneManager.getAllZones(dimension);
        Vec3 pos = source.getPosition();

        ZoneInstance nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (ZoneInstance zi : zones) {
            if (!zi.zoneType.equals(zoneId)) {
                continue;
            }
            double dx = zi.centerX - pos.x;
            double dz = zi.centerZ - pos.z;
            double distSq = dx * dx + dz * dz;
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = zi;
            }
        }

        if (nearest == null) {
            boolean zoneIdKnown = WorldZoneConfig.findDefinition(zoneId) != null;
            throw (zoneIdKnown ? NOT_FOUND : UNKNOWN_ZONE).create(zoneId);
        }

        int distance = (int) Math.sqrt(nearestDistSq);
        ZoneInstance found = nearest;

        String teleportCommand = String.format("/tp @s %d ~ %d", found.centerX, found.centerZ);
        MutableComponent coords = Component.literal(
                        String.format("[%d, ~, %d]", found.centerX, found.centerZ))
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, teleportCommand))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to teleport to this location"))));
        MutableComponent message = Component.literal(String.format(
                        "The nearest zone '%s' is at ", found.zoneType))
                .append(coords)
                .append(Component.literal(String.format(
                        " (%d blocks away, radius %d)", distance, found.radius)));

        source.sendSuccess(() -> message, false);

        return 1;
    }
}

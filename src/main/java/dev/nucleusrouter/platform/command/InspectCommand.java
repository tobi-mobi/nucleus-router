package dev.nucleusrouter.platform.command;

import com.mojang.brigadier.context.CommandContext;
import dev.nucleusrouter.core.location.LocationParser;
import dev.nucleusrouter.platform.NucleusRouterClient;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code /nr inspect}: lists the block you look at and every entity near it (type, name tag, worn and
 * held items, invisible or not). Used to learn how something is built in-game, e.g. what a crystal is,
 * so the mod can recognise it. Read-only; also written to the game log.
 */
final class InspectCommand {
	private static final double LOOK_RANGE = 30, RADIUS = 6;
	private static final int MAX_LINES = 15;

	private InspectCommand() {}

	static int run(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource src = ctx.getSource();
		Minecraft client = src.getClient();
		if (client.player == null || client.level == null) return 0;
		HitResult hit = client.player.pick(LOOK_RANGE, 1.0f, false);
		Vec3 centre = hit.getLocation();
		src.sendFeedback(Component.literal("[NR] ").withStyle(ChatFormatting.DARK_AQUA).append(Component.literal("Inspect").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
		StringBuilder log = new StringBuilder("[Nucleus Router] /nr inspect:");
		if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos p = bh.getBlockPos();
			String block = BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(p).getBlock()).toString();
			line(src, "Looking at block", block + " at " + p.getX() + " " + p.getY() + " " + p.getZ());
			log.append(" block=").append(block).append('@').append(p.toShortString());
		} else {
			line(src, "Looking at", "no block within " + (int) LOOK_RANGE + " (listing entities around the end of your view)");
		}

		List<Entity> near = new ArrayList<>(client.level.getEntities(client.player, new AABB(centre, centre).inflate(RADIUS)));
		near.sort(Comparator.comparingDouble(e -> e.distanceToSqr(centre)));
		line(src, "Entities within " + (int) RADIUS + " blocks", near.isEmpty() ? "none" : String.valueOf(near.size()));
		int shown = 0;
		for (Entity e : near) {
			StringBuilder sb = new StringBuilder(e.getType().toShortString());
			if (e.hasCustomName() && e.getCustomName() != null) sb.append(" \"").append(LocationParser.clean(e.getCustomName().getString())).append('"');
			if (e.isInvisible()) sb.append(" invisible");
			if (e instanceof LivingEntity le) {
				item(sb, "head", le.getItemBySlot(EquipmentSlot.HEAD));
				item(sb, "hand", le.getItemBySlot(EquipmentSlot.MAINHAND));
				item(sb, "chest", le.getItemBySlot(EquipmentSlot.CHEST));
			}
			sb.append(String.format(" at %.1f %.1f %.1f", e.getX(), e.getY(), e.getZ()));
			log.append("\n  ").append(sb);
			if (shown++ < MAX_LINES) src.sendFeedback(Component.literal("  " + sb).withStyle(ChatFormatting.WHITE));
		}
		if (shown > MAX_LINES) src.sendFeedback(Component.literal("  ... " + (shown - MAX_LINES) + " more in the game log").withStyle(ChatFormatting.GRAY));
		NucleusRouterClient.LOG.info(log.toString());
		return 1;
	}

	private static void item(StringBuilder sb, String slot, ItemStack s) {
		if (s == null || s.isEmpty()) return;
		sb.append(' ').append(slot).append('=').append(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())
				.append(" \"").append(LocationParser.clean(s.getHoverName().getString())).append('"');
	}

	private static void line(FabricClientCommandSource src, String k, String v) {
		src.sendFeedback(Component.literal("  " + k + ": ").withStyle(ChatFormatting.GRAY).append(Component.literal(v).withStyle(ChatFormatting.WHITE)));
	}
}

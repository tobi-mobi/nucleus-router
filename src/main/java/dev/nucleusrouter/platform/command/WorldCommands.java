package dev.nucleusrouter.platform.command;

import com.mojang.brigadier.context.CommandContext;
import dev.nucleusrouter.core.world.VoxelClass;
import dev.nucleusrouter.core.world.WorldSnapshot;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.world.WorldCapture;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** {@code /nr world ...}: inspect the remembered terrain. All heavy work runs off the client thread. */
final class WorldCommands {
	private WorldCommands() {}

	static int stats(CommandContext<FabricClientCommandSource> ctx) {
		String status = WorldCapture.status();
		long chunks = WorldCapture.chunksSeen(), changes = WorldCapture.blockChanges();
		WorldCapture.describeLobby().thenCombine(WorldCapture.snapshot(), (lobby, snap) -> new Object[]{lobby, snap})
				.thenApplyAsync(arr -> {
					WorldSnapshot snap = (WorldSnapshot) arr[1];
					long t = System.nanoTime();
					Map<VoxelClass, Long> counts = snap.countClasses();
					return new Object[]{arr[0], snap.sectionCount(), counts, (System.nanoTime() - t) / 1_000_000};
				})
				.thenAccept(r -> {
					show(Component.literal("World model").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
					kv("Lobby", (String) r[0]);
					kv("Status", status);
					kv("Chunks seen / block changes", chunks + " / " + changes);
					kv("Sections stored", r[1] + "  (~" + ((int) r[1] * 8 / 1024) + " MB)");
					@SuppressWarnings("unchecked") Map<VoxelClass, Long> counts = (Map<VoxelClass, Long>) r[2];
					StringBuilder sb = new StringBuilder();
					for (VoxelClass c : VoxelClass.values()) {
						Long n = counts.get(c);
						if (n != null) sb.append(c.name().toLowerCase()).append('=').append(n).append("  ");
					}
					kv("Blocks by class", sb.length() == 0 ? "none yet" : sb.toString().trim());
					kv("Counting took", r[3] + " ms (background thread)");
				})
				.exceptionally(NucleusRouterClient::reportAsyncError);
		return 1;
	}

	static int here(CommandContext<FabricClientCommandSource> ctx) {
		Minecraft client = ctx.getSource().getClient();
		if (client.player == null) return 0;
		BlockPos feet = client.player.blockPosition().below();
		HitResult hit = client.hitResult;
		BlockPos looked = hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK ? bh.getBlockPos() : null;
		CompletableFuture<WorldSnapshot> snap = WorldCapture.snapshot();
		snap.thenAccept(s -> {
			show(Component.literal("Remembered blocks").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
			describe(s, "Standing on", feet);
			if (looked != null) describe(s, "Looking at", looked);
			else kv("Looking at", "no block in reach");
		}).exceptionally(NucleusRouterClient::reportAsyncError);
		return 1;
	}

	static int save(CommandContext<FabricClientCommandSource> ctx) {
		WorldCapture.saveNow().thenAccept(msg -> show(Component.literal(msg).withStyle(ChatFormatting.GREEN)))
				.exceptionally(NucleusRouterClient::reportAsyncError);
		return 1;
	}

	private static void describe(WorldSnapshot s, String label, BlockPos p) {
		VoxelClass c = s.classAt(p.getX(), p.getY(), p.getZ());
		String now = s.currentBlock(p.getX(), p.getY(), p.getZ());
		String orig = s.originalBlock(p.getX(), p.getY(), p.getZ());
		kv(label + " " + p.getX() + " " + p.getY() + " " + p.getZ(),
				c.name() + "  (now " + shortName(now) + ", originally " + shortName(orig) + ")");
	}

	private static String shortName(String id) {
		if (id == null) return "unseen";
		return id.startsWith("minecraft:") ? id.substring(10) : id;
	}

	private static void show(Component c) { NucleusRouterClient.showLocal(c); }

	private static void kv(String key, String value) {
		show(Component.literal("  " + key + ": ").withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(ChatFormatting.WHITE)));
	}
}

package dev.nucleusrouter.platform.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.nucleusrouter.game.Coverage;
import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.game.StructureMap;
import dev.nucleusrouter.platform.location.LocationTracker;
import dev.nucleusrouter.platform.structure.ExplorationTracker;
import dev.nucleusrouter.platform.structure.StructureTracker;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** {@code /nr explored [map]} (roadmap 2.4). */
final class ExploreCommands {
	private static final int MAP_SIZE = 20;
	private static final String CELL = "█";

	private ExploreCommands() {}

	static LiteralArgumentBuilder<FabricClientCommandSource> node() {
		return literal("explored").executes(ctx -> summary(ctx, false))
				.then(literal("map").executes(ctx -> summary(ctx, true)));
	}

	private static int summary(CommandContext<FabricClientCommandSource> ctx, boolean map) {
		FabricClientCommandSource src = ctx.getSource();
		Coverage c = ExplorationTracker.coverage();
		var p = src.getPlayer();
		src.sendFeedback(Component.literal("[NR] ").withStyle(ChatFormatting.DARK_AQUA)
				.append(Component.literal("Exploration").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
		if (!LocationTracker.state().inHollows()) {
			src.sendFeedback(Component.literal("  Not in the Crystal Hollows.").withStyle(ChatFormatting.GRAY));
			return 1;
		}
		kv(src, "Visited (close enough to see name tags)", String.format("%.0f%% of the Hollows", c.visitedFraction() * 100));
		kv(src, "Terrain loaded", String.format("%.0f%%", c.loadedFraction() * 100));
		double[] next = c.nearestUnexplored(p.getX(), p.getZ(), 3);
		if (next != null) {
			double dx = next[0] - p.getX(), dz = next[1] - p.getZ();
			kv(src, "Nearest unexplored stretch", String.format("%d blocks %s (around %d %d)", Math.round(Math.hypot(dx, dz)), direction(dx, dz), Math.round(next[0]), Math.round(next[1])));
		} else {
			kv(src, "Nearest unexplored stretch", "none left that is bigger than a few blocks");
		}
		if (map) drawMap(src, c, p.getX(), p.getZ());
		else src.sendFeedback(Component.literal("  /nr explored map shows a map").withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	private static void drawMap(FabricClientCommandSource src, Coverage c, double px, double pz) {
		char[][] m = c.miniMap(MAP_SIZE);
		int[] me = c.miniMapCell(px, pz, MAP_SIZE);
		Character[][] marks = new Character[MAP_SIZE][MAP_SIZE];
		StructureMap sm = StructureTracker.map();
		for (Structure s : Structure.values()) {
			StructureMap.Location l = sm.locate(s);
			if (l == null || l.source() == StructureMap.Source.AREA) continue;
			int[] cell = c.miniMapCell(l.pos().x(), l.pos().z(), MAP_SIZE);
			marks[cell[1]][cell[0]] = 'S';
		}
		src.sendFeedback(Component.literal("  North is up. ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(CELL).withStyle(ChatFormatting.AQUA)).append(Component.literal(" you  ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(CELL).withStyle(ChatFormatting.LIGHT_PURPLE)).append(Component.literal(" structure  ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(CELL).withStyle(ChatFormatting.GREEN)).append(Component.literal(" visited  ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(CELL).withStyle(ChatFormatting.YELLOW)).append(Component.literal(" partly  ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(CELL).withStyle(ChatFormatting.DARK_GRAY)).append(Component.literal(" not yet").withStyle(ChatFormatting.GRAY)));
		for (int y = 0; y < MAP_SIZE; y++) {
			MutableComponent row = Component.literal("  ");
			for (int x = 0; x < MAP_SIZE; x++) {
				ChatFormatting col;
				if (me[0] == x && me[1] == y) col = ChatFormatting.AQUA;
				else if (marks[y][x] != null) col = ChatFormatting.LIGHT_PURPLE;
				else col = switch (m[y][x]) {
					case '#' -> ChatFormatting.GREEN;
					case '+' -> ChatFormatting.YELLOW;
					default -> ChatFormatting.DARK_GRAY;
				};
				row.append(Component.literal(CELL).withStyle(col));
			}
			src.sendFeedback(row);
		}
	}

	/** Compass direction for a horizontal offset (Minecraft: -z is north, +x is east). */
	static String direction(double dx, double dz) {
		double deg = Math.toDegrees(Math.atan2(dx, -dz));
		if (deg < 0) deg += 360;
		String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
		return names[(int) Math.round(deg / 45) % 8];
	}

	private static void kv(FabricClientCommandSource src, String k, String v) {
		src.sendFeedback(Component.literal("  " + k + ": ").withStyle(ChatFormatting.GRAY).append(Component.literal(v).withStyle(ChatFormatting.WHITE)));
	}
}

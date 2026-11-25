package dev.nucleusrouter.platform.structure;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.game.KeeperLayout;
import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.game.StructureMap;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Mines of Divan keeper layout (user 2026-11-25: having to visit all 4 keepers is a chore). The layout is
 * learned once (all 4 keepers seen in one lobby, live or in a saved lobby file) and stored in
 * {@code nucleusrouter/learned/divan-keepers.json}; after that, any one keeper places the centre.
 */
public final class KeeperLayouts {
	private static final Gson GSON = new Gson();
	private static volatile KeeperLayout layout;
	private static boolean announcedThisLobby;
	private static String lobby;
	private static int ticks;

	private KeeperLayouts() {}

	public static void init() {
		CompletableFuture.runAsync(KeeperLayouts::loadOrBootstrap);
		ClientTickEvents.END_CLIENT_TICK.register(c -> { if (++ticks % 20 == 0) tick(); });
	}

	public static KeeperLayout layout() { return layout; }

	private static Path file() { return CrystalScanner.dir().resolve("divan-keepers.json"); }

	/** Loads the stored layout, or learns it from saved lobbies that saw all 4 keepers. Background thread. */
	private static void loadOrBootstrap() {
		try {
			if (Files.exists(file())) {
				layout = GSON.fromJson(Files.readString(file()), KeeperLayout.class);
				return;
			}
			Path dir = FabricLoader.getInstance().getGameDir().resolve("nucleusrouter").resolve("lobbies");
			if (!Files.isDirectory(dir)) return;
			try (Stream<Path> files = Files.list(dir)) {
				for (Path f : files.filter(p -> p.toString().endsWith(".structures.json"))
						.sorted(Comparator.comparingLong(KeeperLayouts::mtime).reversed()).toList()) {
					StructureMap m = readMap(f);
					KeeperLayout l = m == null ? null : KeeperLayout.learn(m.npcs(Structure.MINES_OF_DIVAN));
					if (l != null) {
						save(l);
						NucleusRouterClient.LOG.info("[Nucleus Router] Learned the Mines of Divan keeper layout from saved lobby {}", f.getFileName());
						return;
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			NucleusRouterClient.LOG.warn("[Nucleus Router] Keeper layout: {}", e.toString());
		}
	}

	/** Reads the map out of a saved {@code <server>.structures.json}. */
	static StructureMap readMap(Path f) {
		try {
			JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			return o.has("map") ? GSON.fromJson(o.get("map"), StructureMap.class) : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static long mtime(Path p) {
		try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
	}

	private static void save(KeeperLayout l) throws IOException {
		layout = l;
		Files.createDirectories(file().getParent());
		Files.writeString(file(), GSON.toJson(l));
	}

	/** Client thread: learn from a live lobby, or place the centre from the keepers seen so far. */
	private static void tick() {
		if (!LocationTracker.state().inHollows()) return;
		String now = StructureTracker.lobby();
		if (now != null && !now.equals(lobby)) { lobby = now; announcedThisLobby = false; }
		StructureMap map = StructureTracker.map();
		List<StructureMap.Npc> keepers = map.npcs(Structure.MINES_OF_DIVAN);
		if (keepers.isEmpty()) return;
		KeeperLayout l = layout;
		if (l == null) {
			KeeperLayout learned = KeeperLayout.learn(keepers);
			if (learned != null) {
				CompletableFuture.runAsync(() -> { try { save(learned); } catch (IOException e) { NucleusRouterClient.LOG.warn("[Nucleus Router] Could not save keeper layout: {}", e.toString()); } });
				layout = learned;
				if (ConfigManager.get().general.chatNotices)
					NucleusRouterClient.showLocal(Component.literal("Learned the Mines of Divan keeper layout: from now on one keeper is enough to find its centre.").withStyle(ChatFormatting.AQUA));
			}
			return;
		}
		if (keepers.size() >= KeeperLayout.KEEPERS) return;
		Vec3 centre = l.centreFrom(keepers);
		if (centre == null) return;
		map.setDerived(Structure.MINES_OF_DIVAN, centre);
		if (!announcedThisLobby && ConfigManager.get().general.chatNotices) {
			announcedThisLobby = true;
			NucleusRouterClient.showLocal(Component.literal("Mines of Divan centre placed from " + keepers.size() + " keeper" + (keepers.size() > 1 ? "s" : "")
					+ " and the learned layout: " + StructureTracker.fmt(centre.x(), centre.y(), centre.z())).withStyle(ChatFormatting.GREEN));
		}
	}
}

package dev.nucleusrouter.platform.structure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.location.LocationState;
import dev.nucleusrouter.core.world.LobbyIdentity;
import dev.nucleusrouter.game.AnchorMatcher;
import dev.nucleusrouter.game.BarrierBoxes;
import dev.nucleusrouter.platform.world.WorldCapture;
import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.game.StructureMap;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import dev.nucleusrouter.platform.render.WorldDraw;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds the run's structures in the current lobby (SPEC §3.5, §4, roadmap 2.1):
 * <ul>
 *   <li>every {@code structures.scanEveryTicks} ticks, looks at the name tags of loaded entities
 *       (NPCs, mobs, armour-stand labels) and matches them against the configured names;</li>
 *   <li>remembers where you stood while the scoreboard showed a structure's area (rough hint);</li>
 *   <li>keeps what the user set by hand with {@code /nr structure set}.</li>
 * </ul>
 * Read-only: it only reads entities the client already has. Results are kept per lobby and saved
 * to {@code nucleusrouter/lobbies/<server>.structures.json}.
 */
public final class StructureTracker {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long SAVE_EVERY_MS = 30_000;
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "NucleusRouter-StructureIO");
		t.setDaemon(true);
		return t;
	});

	/** What is written to disk. */
	private record Saved(String lobby, long clockTicks, long savedAtMs, StructureMap map) {}

	private static StructureMap map = new StructureMap();
	private static String lobby;
	private static long savedVersion;
	private static long lastSaveMs;
	private static AnchorMatcher matcher;
	private static int ticks;

	private StructureTracker() {}

	public static void init() {
		matcher = new AnchorMatcher(ConfigManager.get().structures);
		ConfigManager.onChange(() -> matcher = new AnchorMatcher(ConfigManager.get().structures));
		dev.nucleusrouter.platform.debug.Perf.onTick(StructureTracker::onTick);
		ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> saveIfDirty());
		ClientLifecycleEvents.CLIENT_STOPPING.register(c -> saveIfDirty());
		WorldDraw.addLayerWhile(() -> ConfigManager.get().structures.showMarkers && LocationTracker.state().inHollows(), StructureTracker::draw);
	}

	public static StructureMap map() { return map; }
	public static String lobby() { return lobby; }

	// ------------------------------------------------------------------ client thread

	private static void onTick(Minecraft client) {
		NrConfig.Structures cfg = ConfigManager.get().structures;
		if (++ticks % cfg.scanEveryTicks != 0) return;
		LocationState loc = LocationTracker.state();
		if (!loc.inHollows() || loc.serverId() == null || client.level == null || client.player == null) return;
		switchLobby(loc);

		long now = System.currentTimeMillis();
		NrConfig.Hollows h = ConfigManager.get().hollows;
		for (Entity e : client.level.entitiesForRendering()) {
			if (e instanceof Player || !e.hasCustomName()) continue;
			if (e.getX() < h.minX - 1 || e.getX() > h.maxX + 2 || e.getZ() < h.minZ - 1 || e.getZ() > h.maxZ + 2) continue;
			Component name = e.getCustomName();
			if (name == null) continue;
			String text = name.getString();
			Structure s = matcher.match(text);
			if (s == null) continue;
			String label = dev.nucleusrouter.core.location.LocationParser.clean(text);
			// The name is often a floating label (armour stand); anchor on the NPC body standing under it.
			Entity body = (e instanceof LivingEntity && !(e instanceof ArmorStand)) ? e : bodyUnder(client, e);
			Entity at = body != null ? body : e;
			if (map.observeNametag(s, label, at.getX(), at.getY(), at.getZ(), now, body != null)) {
				NucleusRouterClient.LOG.info("[Nucleus Router] Structures: found {} from name tag '{}' ({} at {} {} {}), body: {}", s.displayName, label,
						e.getType().toShortString(), e.getX(), e.getY(), e.getZ(),
						body == null ? "none" : body.getType().toShortString() + " at " + body.getX() + " " + body.getY() + " " + body.getZ());
				notice(Component.literal("Found " + s.displayName + " (" + label + ") at " + fmt(at.getX(), at.getY(), at.getZ())).withStyle(ChatFormatting.GREEN));
			}
		}

		if (cfg.useAreaHints) {
			Structure area = Structure.byArea(loc.scoreboardArea());
			if (area != null) {
				boolean hadPosition = map.locate(area) != null;
				if (map.observeArea(area, client.player.getX(), client.player.getY(), client.player.getZ()) && !hadPosition) {
					NucleusRouterClient.LOG.info("[Nucleus Router] Structures: in the {} area at {}", area.displayName, client.player.position());
					notice(Component.literal("You're in the " + area.displayName + " area. Rough position noted; it becomes exact once the "
							+ area.anchorNoun + " name tag is seen, or use /nr structure set " + area.key).withStyle(ChatFormatting.YELLOW));
				}
			}
		}

		if (cfg.useSkyblocker && ticks % SKYBLOCKER_EVERY_TICKS < cfg.scanEveryTicks) pollSkyblocker();
		if (ticks % CRYSTAL_SEARCH_EVERY_TICKS < cfg.scanEveryTicks) searchCrystals(client, loc);

		if (map.version() != savedVersion && now - lastSaveMs > SAVE_EVERY_MS) saveIfDirty();
	}

	private static final int SKYBLOCKER_EVERY_TICKS = 40, CRYSTAL_SEARCH_EVERY_TICKS = 100;
	/** Barrier boxes are looked for within this many blocks of the search centre. */
	private static final int CRYSTAL_SEARCH_RADIUS = 48;
	private static boolean crystalSearchRunning;

	private static void pollSkyblocker() {
		Map<Structure, SkyblockerBridge.Waypoint> wps = SkyblockerBridge.read();
		for (Structure s : Structure.values()) {
			SkyblockerBridge.Waypoint w = wps.get(s);
			boolean had = map.locate(s) != null;
			if (map.setSkyblocker(s, w == null ? null : new double[]{w.x(), w.y(), w.z()}, w != null && w.verified()) && w != null && !had) {
				NucleusRouterClient.LOG.info("[Nucleus Router] Structures: Skyblocker waypoint for {} at {} {} {}", s.displayName, w.x(), w.y(), w.z());
				notice(Component.literal("Skyblocker knows " + s.displayName + ": " + fmt(w.x(), w.y(), w.z()) + " (approximate until its name tag or crystal is seen)").withStyle(ChatFormatting.YELLOW));
			}
		}
	}

	/**
	 * For structures marked at their crystal: when you are in their area, near their NPC or near
	 * Skyblocker's waypoint, look for the barrier box around the crystal in the remembered terrain
	 * (background thread).
	 */
	private static void searchCrystals(Minecraft client, LocationState loc) {
		if (crystalSearchRunning) return;
		Vec3 player = new Vec3(client.player.getX(), client.player.getY(), client.player.getZ());
		Structure target = null;
		Vec3 centre = null;
		for (Structure s : Structure.values()) {
			if (!s.markCrystal || map.crystalBox(s) != null) continue;
			Vec3 c = null;
			if (s.areaName != null && s.areaName.equalsIgnoreCase(loc.scoreboardArea() == null ? "" : loc.scoreboardArea().trim())) c = player;
			for (StructureMap.Npc n : map.npcs(s)) if (c == null && n.pos().sub(player).length() < 96) c = n.pos();
			StructureMap.Location l = map.locate(s);
			if (c == null && l != null && l.source() == StructureMap.Source.SKYBLOCKER && l.pos().sub(player).length() < 96) c = l.pos();
			if (c != null) { target = s; centre = c; break; }
		}
		if (target == null) return;
		crystalSearchRunning = true;
		Structure s = target;
		Vec3 c = centre;
		int minCount = ConfigManager.get().structures.crystalBoxMinBarriers;
		String lobbyAtStart = lobby;
		int r = CRYSTAL_SEARCH_RADIUS;
		WorldCapture.snapshot().thenApplyAsync(snap -> {
			List<int[]> barriers = snap.findBlocks("minecraft:barrier", (int) c.x() - r, (int) c.y() - r, (int) c.z() - r, (int) c.x() + r, (int) c.y() + r, (int) c.z() + r);
			List<BarrierBoxes.Box> boxes = BarrierBoxes.group(barriers);
			return BarrierBoxes.pickCrystalBox(boxes, minCount, c.x(), c.y(), c.z());
		}).whenComplete((box, err) -> client.execute(() -> {
			crystalSearchRunning = false;
			if (err != null) { NucleusRouterClient.LOG.warn("[Nucleus Router] Structures: crystal search failed: {}", err.toString()); return; }
			if (box == null || !java.util.Objects.equals(lobbyAtStart, lobby)) return;
			if (map.setCrystal(s, box.min(), box.max())) {
				NucleusRouterClient.LOG.info("[Nucleus Router] Structures: {} crystal box {} .. {} ({} barriers)", s.displayName,
						java.util.Arrays.toString(box.min()), java.util.Arrays.toString(box.max()), box.count());
				notice(Component.literal("Found the " + s.crystal + " crystal of " + s.displayName + " at " + fmt(box.centreX(), box.centreY(), box.centreZ())).withStyle(ChatFormatting.GREEN));
			}
		}));
	}

	/** The living entity (not an armour stand, not you) standing right under a floating name tag, or null. */
	private static Entity bodyUnder(Minecraft client, Entity tag) {
		Entity best = null;
		double bestD = Double.MAX_VALUE;
		AABB box = new AABB(tag.getX() - 1.5, tag.getY() - 3.5, tag.getZ() - 1.5, tag.getX() + 1.5, tag.getY() + 0.5, tag.getZ() + 1.5);
		for (Entity o : client.level.getEntities(tag, box)) {
			if (!(o instanceof LivingEntity) || o instanceof ArmorStand || o == client.player) continue;
			double dx = o.getX() - tag.getX(), dz = o.getZ() - tag.getZ();
			double d = dx * dx + dz * dz;
			if (d < bestD) { bestD = d; best = o; }
		}
		return best;
	}

	private static void switchLobby(LocationState loc) {
		if (loc.serverId().equals(lobby)) return;
		saveIfDirty();
		lobby = loc.serverId();
		map = new StructureMap();
		savedVersion = 0;
		lastSaveMs = System.currentTimeMillis();
		if (!ConfigManager.get().general.saveLobbies) return;
		Path file = fileFor(lobby);
		if (!Files.exists(file)) return;
		try {
			Saved s = GSON.fromJson(Files.readString(file), Saved.class);
			if (s != null && s.map() != null && LobbyIdentity.isSameLobby(s.clockTicks(), s.savedAtMs(), loc.clockTicks(), System.currentTimeMillis())) {
				map = s.map();
				NucleusRouterClient.LOG.info("[Nucleus Router] Structures: loaded {} known structures for lobby {}.", map.located().size(), lobby);
			} else {
				Files.move(file, file.resolveSibling(file.getFileName() + ".old"), StandardCopyOption.REPLACE_EXISTING);
				NucleusRouterClient.LOG.info("[Nucleus Router] Structures: {} is a new lobby, old structure file moved aside.", lobby);
			}
		} catch (IOException | RuntimeException e) {
			NucleusRouterClient.LOG.error("[Nucleus Router] Structures: could not read {}: {}. Starting fresh.", file, e.toString());
		}
	}

	/** Serialises on the calling (client) thread, writes the file on a background thread. */
	private static void saveIfDirty() {
		if (lobby == null || map.version() == savedVersion || !ConfigManager.get().general.saveLobbies) return;
		String json = GSON.toJson(new Saved(lobby, LocationTracker.state().clockTicks(), System.currentTimeMillis(), map));
		Path file = fileFor(lobby);
		savedVersion = map.version();
		lastSaveMs = System.currentTimeMillis();
		IO.execute(() -> {
			try {
				Files.createDirectories(file.getParent());
				Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
				Files.writeString(tmp, json);
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				NucleusRouterClient.LOG.error("[Nucleus Router] Structures: could not save {}: {}", file, e.toString());
			}
		});
	}

	private static Path fileFor(String serverId) {
		String safe = serverId.replaceAll("[^A-Za-z0-9_-]", "_");
		return FabricLoader.getInstance().getGameDir().resolve("nucleusrouter").resolve("lobbies").resolve(safe + ".structures.json");
	}

	// ------------------------------------------------------------------ commands (client thread)

	/** Sets {@code s} to where the player stands. Returns false outside the Hollows. */
	public static boolean setHere(Structure s) {
		Minecraft client = Minecraft.getInstance();
		LocationState loc = LocationTracker.state();
		if (client.player == null || !loc.inHollows() || loc.serverId() == null) return false;
		switchLobby(loc);
		map.setManual(s, client.player.getX(), client.player.getY(), client.player.getZ());
		saveIfDirty();
		return true;
	}

	public static void clear(Structure s) {
		if (s == null) map.clearAll(); else map.clear(s);
		saveIfDirty();
	}

	// ------------------------------------------------------------------ drawing

	private static final int COLOR_MANUAL = 0x55FFFF, COLOR_NPC = 0x55FF55, COLOR_SKYBLOCKER = 0xFFFF55;

	private static void draw(WorldDraw draw) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return;
		Vec3 eye = new Vec3(client.player.getX(), client.player.getEyeY(), client.player.getZ());
		for (StructureMap.Location l : map.located()) {
			if (l.source() == StructureMap.Source.AREA) continue; // rough positions were mostly wrong (user, 2026-11-24)
			int rgb = switch (l.source()) {
				case MANUAL -> COLOR_MANUAL;
				case SKYBLOCKER -> COLOR_SKYBLOCKER;
				default -> COLOR_NPC;
			};
			int[][] crystal = map.crystalBox(l.structure());
			if (crystal != null) {
				draw.outline(new AABB(crystal[0][0], crystal[0][1], crystal[0][2], crystal[1][0] + 1, crystal[1][1] + 1, crystal[1][2] + 1), WorldDraw.argb(255, COLOR_NPC), 2f, true);
			}
			Vec3 p = l.pos();
			double dist = p.sub(eye).length();
			// A tall beam so it can be found from far away, and a label above it
			draw.line(WorldDraw.mc(p), WorldDraw.mc(p.add(new Vec3(0, 30, 0))), WorldDraw.argb(200, rgb), 3f, true);
			draw.marker(WorldDraw.mc(p), 0.6, WorldDraw.argb(255, rgb), WorldDraw.argb(90, rgb), true);
			if (l.source() != StructureMap.Source.MANUAL) {
				for (StructureMap.Npc n : map.npcs(l.structure())) {
					draw.marker(WorldDraw.mc(n.pos()), 0.4, WorldDraw.argb(255, COLOR_NPC), WorldDraw.argb(60, COLOR_NPC), true);
				}
			}
			float scale = (float) (0.6 * Math.max(1.0, dist / 10.0));
			String label = l.structure().displayName + (l.source() == StructureMap.Source.SKYBLOCKER ? " (Skyblocker)" : "") + "  " + Math.round(dist) + "m";
			draw.text(label, WorldDraw.mc(p.add(new Vec3(0, 31 + scale, 0))), WorldDraw.argb(255, rgb), scale, true);
		}
	}

	// ------------------------------------------------------------------ helpers

	private static void notice(Component c) {
		if (ConfigManager.get().general.chatNotices) NucleusRouterClient.showLocal(c);
	}

	public static String fmt(double x, double y, double z) {
		return Math.round(x) + " " + Math.round(y) + " " + Math.round(z);
	}
}

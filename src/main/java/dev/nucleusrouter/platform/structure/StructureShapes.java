package dev.nucleusrouter.platform.structure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.world.WorldSnapshot;
import dev.nucleusrouter.game.Orientation;
import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.game.StructureMap;
import dev.nucleusrouter.game.StructureTemplate;
import dev.nucleusrouter.game.StructureZone;
import dev.nucleusrouter.game.TemplateMatcher;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import dev.nucleusrouter.platform.render.WorldDraw;
import dev.nucleusrouter.platform.world.WorldCapture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Structure shapes (roadmap 2.3, SPEC §4, §6, §2.4):
 * <ul>
 *   <li>{@code /nr capture <structure>} saves the blocks around a structure's anchor to
 *       {@code nucleusrouter/templates/<structure>-<lobby>-<time>.json.gz};</li>
 *   <li>optionally ({@code structures.detectOrientation}, off: structures don't rotate, confirmed
 *       2026-11-25), a capture from another lobby is compared to find how the structure is turned;</li>
 *   <li>each found structure's no-bomb / no-placing zone is drawn as a red box.</li>
 * </ul>
 * Heavy work (copying blocks, matching) runs off the client thread.
 */
public final class StructureShapes {
	private static final Gson GSON = new GsonBuilder().create();
	private static final int ORIENTATION_EVERY_TICKS = 200;
	private static final int MAX_ORIENTATION_TRIES = 6;

	/** File format of a capture. */
	private record Saved(String structure, String lobby, String time, int[] anchor, String anchorSource, int radius,
			List<String> palette, String data) {}

	private static final Map<Structure, Integer> tries = new EnumMap<>(Structure.class);
	private static String triesLobby;
	private static boolean matching;
	private static int ticks;

	private StructureShapes() {}

	public static void init() {
		dev.nucleusrouter.platform.debug.Perf.onTick(c -> { if (++ticks % ORIENTATION_EVERY_TICKS == 0) detectOrientations(); });
		WorldDraw.addLayerWhile(() -> ConfigManager.get().structures.showZones && LocationTracker.state().inHollows(), StructureShapes::drawZones);
	}

	public static Path dir() {
		return FabricLoader.getInstance().getGameDir().resolve("nucleusrouter").resolve("templates");
	}

	/** The zone for {@code s} from the config. */
	public static StructureZone zone(Structure s) {
		int[] v = ConfigManager.get().structures.zones.get(s.key);
		if (v == null || v.length != 6) v = NrConfig.Structures.defaultZones().get(s.key);
		return new StructureZone(new int[]{v[0], v[1], v[2]}, new int[]{v[3], v[4], v[5]});
	}

	// ------------------------------------------------------------------ capture

	/** Captures the cube around {@code s}'s anchor. Completes with a readable report (several lines). */
	public static CompletableFuture<List<String>> capture(Structure s, int radius) {
		StructureMap map = StructureTracker.map();
		int[] a = map.anchorBlock(s);
		if (a == null) return CompletableFuture.failedFuture(new IllegalStateException(
				s.displayName + " has no exact position yet (needs its name tag, crystal, or /nr structure set " + s.key + ")"));
		String lobby = StructureTracker.lobby();
		String source = map.locate(s).source().name();
		return WorldCapture.snapshot().thenApplyAsync(snap -> {
			StructureTemplate t = StructureTemplate.capture(s.key, a[0], a[1], a[2], radius, lookup(snap));
			String time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
			Path file = dir().resolve(s.key + "-" + safe(lobby) + "-" + time + ".json.gz");
			try {
				Files.createDirectories(file.getParent());
				try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
					GSON.toJson(new Saved(s.key, lobby, time, a, source, radius, t.palette, t.encodeData()), w);
				}
			} catch (IOException e) {
				throw new java.io.UncheckedIOException("could not write " + file, e);
			}
			List<String> report = new ArrayList<>();
			report.add(String.format("Saved %s around %d %d %d (radius %d, %.0f%% of it seen, %d block types) to %s",
					s.displayName, a[0], a[1], a[2], radius, t.seenFraction() * 100, t.palette.size() - 1, file.getFileName()));
			if (t.seenFraction() < 0.6) report.add("Less than 60% seen: walk around the structure (inside too) and capture again for a better template.");
			int[] b = t.distinctiveBounds();
			if (b != null) report.add(String.format("Structure-like blocks reach from %d %d %d to %d %d %d (relative to the anchor)", b[0], b[1], b[2], b[3], b[4], b[5]));
			// Compare with captures of the same structure from other lobbies: does it rotate?
			for (Loaded other : load(s)) {
				if (lobby != null && lobby.equals(other.lobby)) continue;
				List<TemplateMatcher.Match> ranked = TemplateMatcher.match(other.template, a[0], a[1], a[2], 2, lookup(snap));
				TemplateMatcher.Match m = ranked.get(0);
				report.add(String.format("Compared with lobby %s: %s (%.0f%% match over %d blocks; next best %.0f%%)%s",
						other.lobby, m.orientation().describe(), m.score() * 100, m.compared(), ranked.get(1).score() * 100,
						TemplateMatcher.confident(ranked) ? "" : " - not sure"));
				NucleusRouterClient.LOG.info("[Nucleus Router] Capture {} vs lobby {}: {}", s.key, other.lobby, ranked);
			}
			NucleusRouterClient.LOG.info("[Nucleus Router] Capture: {}", report);
			return report;
		});
	}

	private record Loaded(String lobby, StructureTemplate template) {}

	/** Every saved capture of {@code s}, oldest first. Unreadable files are skipped (and logged). */
	private static List<Loaded> load(Structure s) {
		List<Loaded> out = new ArrayList<>();
		Path dir = dir();
		if (!Files.isDirectory(dir)) return out;
		try (Stream<Path> files = Files.list(dir)) {
			for (Path f : files.filter(p -> p.getFileName().toString().startsWith(s.key + "-") && p.toString().endsWith(".json.gz")).sorted().toList()) {
				try (Reader r = new InputStreamReader(new GZIPInputStream(Files.newInputStream(f)), StandardCharsets.UTF_8)) {
					Saved sv = GSON.fromJson(r, Saved.class);
					int size = 2 * sv.radius() + 1;
					int[] data = StructureTemplate.decodeData(sv.data(), size * size * size);
					out.add(new Loaded(sv.lobby(), new StructureTemplate(sv.structure(), sv.anchor()[0], sv.anchor()[1], sv.anchor()[2], sv.radius(), sv.palette(), data)));
				} catch (IOException | RuntimeException e) {
					NucleusRouterClient.LOG.warn("[Nucleus Router] Could not read template {}: {}", f.getFileName(), e.toString());
				}
			}
		} catch (IOException e) {
			NucleusRouterClient.LOG.warn("[Nucleus Router] Could not list {}: {}", dir, e.toString());
		}
		return out;
	}

	/** Planner-relevant lookup: the original (sticky) block, so mined structure blocks still count. */
	private static StructureTemplate.BlockLookup lookup(WorldSnapshot snap) {
		return snap::originalBlock;
	}

	private static String safe(String s) {
		return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9_-]", "_");
	}

	// ------------------------------------------------------------------ orientation

	/** For found structures with a capture from another lobby: find how they are turned here. */
	private static void detectOrientations() {
		if (matching || !ConfigManager.get().structures.detectOrientation || !LocationTracker.state().inHollows()) return;
		String lobby = StructureTracker.lobby();
		if (lobby == null) return;
		if (!lobby.equals(triesLobby)) { tries.clear(); triesLobby = lobby; }
		StructureMap map = StructureTracker.map();
		for (Structure s : Structure.values()) {
			if (map.orientationKnown(s) || tries.getOrDefault(s, 0) >= MAX_ORIENTATION_TRIES) continue;
			int[] a = map.anchorBlock(s);
			if (a == null) continue;
			tries.merge(s, 1, Integer::sum);
			matching = true;
			WorldCapture.snapshot().thenApplyAsync(snap -> {
				List<Loaded> others = load(s).stream().filter(l -> !lobby.equals(l.lobby)).toList();
				if (others.isEmpty()) return null;
				Loaded latest = others.get(others.size() - 1);
				return TemplateMatcher.match(latest.template, a[0], a[1], a[2], 2, lookup(snap));
			}).whenComplete((ranked, err) -> Minecraft.getInstance().execute(() -> {
				matching = false;
				if (err != null) { NucleusRouterClient.LOG.warn("[Nucleus Router] Orientation check failed: {}", err.toString()); return; }
				if (ranked == null) { tries.put(s, MAX_ORIENTATION_TRIES); return; } // no capture to compare with
				if (!lobby.equals(StructureTracker.lobby())) return;
				NucleusRouterClient.LOG.info("[Nucleus Router] Orientation of {}: {}", s.key, ranked);
				if (TemplateMatcher.confident(ranked)) {
					Orientation o = ranked.get(0).orientation();
					StructureTracker.map().setOrientation(s, o);
					if (ConfigManager.get().general.chatNotices)
						NucleusRouterClient.showLocal(Component.literal(String.format("%s is %s compared with your capture (%.0f%% match). Its zone is turned to match.",
								s.displayName, o.describe(), ranked.get(0).score() * 100)).withStyle(ChatFormatting.GREEN));
				}
			}));
			return; // one at a time
		}
	}

	// ------------------------------------------------------------------ drawing

	private static void drawZones(WorldDraw draw) {
		StructureMap map = StructureTracker.map();
		for (Structure s : Structure.values()) {
			int[] b = map.zoneBox(s, zone(s));
			if (b == null) continue;
			draw.outline(new AABB(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1), WorldDraw.argb(170, 0xFF4040), 2f, false);
		}
	}
}

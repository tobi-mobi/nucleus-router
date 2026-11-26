package dev.nucleusrouter.platform.structure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.game.SetPoints;
import dev.nucleusrouter.game.StopPlan;
import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.game.StructureMap;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Your own stop points (roadmap 5.1), kept in {@code nucleusrouter/setpoints.json} (one file for every lobby).
 * Only ever reads your position; nothing is sent to the server.
 */
public final class SetPointStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static SetPoints points = new SetPoints();

	private SetPointStore() {}

	public static Path file() { return FabricLoader.getInstance().getGameDir().resolve("nucleusrouter").resolve("setpoints.json"); }

	public static void load() {
		try {
			if (Files.exists(file())) {
				SetPoints p = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), SetPoints.class);
				if (p != null && p.points != null) points = p;
				NucleusRouterClient.LOG.info("[Nucleus Router] Loaded {} stop point(s) of your own", points.points.size());
			}
		} catch (IOException | RuntimeException e) {
			NucleusRouterClient.LOG.warn("[Nucleus Router] Couldn't read {}: {} (starting with none)", file(), e.toString());
		}
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(points), StandardCharsets.UTF_8);
		} catch (IOException e) {
			NucleusRouterClient.LOG.warn("[Nucleus Router] Couldn't save {}: {}", file(), e.toString());
		}
	}

	/** A copy for planner threads. */
	public static synchronized SetPoints snapshot() { return GSON.fromJson(GSON.toJson(points), SetPoints.class); }

	/** Records where you stand as the stop point for {@code id}. Returns a message for chat. */
	public static String set(String id) {
		id = id.toLowerCase(Locale.ROOT);
		if (!SetPoints.isId(id)) return "Unknown stop '" + id + "'. Stops: " + String.join(", ", SetPoints.IDS);
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null || !LocationTracker.state().inHollows()) return "Only works in the Crystal Hollows.";
		StructureMap map = StructureTracker.map();
		Vec3 anchor = StopPlan.anchor(id, map);
		if (anchor == null) return switch (id) {
			case "divan" -> "The Jade crystal box in the Mines of Divan isn't found yet; walk near it first (/nr structures).";
			case "queen" -> "The Amber crystal box isn't found yet; get near it first (/nr structures).";
			case "khazad" -> "The Topaz crystal box isn't found yet; get near it first (/nr structures).";
			default -> StopPlan.structureOf(id).displayName + " isn't found exactly in this lobby yet (/nr structures).";
		};
		Vec3 feet = new Vec3(p.getX(), p.getY(), p.getZ());
		Structure s = StopPlan.structureOf(id);
		SetPoints.Point pt;
		synchronized (SetPointStore.class) { pt = points.record(id, anchor, feet, map.orientation(s), System.currentTimeMillis()); }
		save();
		StopsTracker.maybeRecompute(true);
		int[] fb = SetPoints.feetBlock(feet);
		double eyeDist = new Vec3(p.getX(), p.getEyeY(), p.getZ()).sub(anchor).length();
		String warn = p.onGround() ? "" : " Careful: you weren't standing on a block.";
		return String.format(Locale.ROOT, "Stop point for %s set: %d %d %d (%+d %+d %+d from the %s, %.1f blocks from your eyes). Used in every lobby.%s",
				label(id), fb[0], fb[1], fb[2], pt.dx(), pt.dy(), pt.dz(), anchorName(id), eyeDist, warn);
	}

	/** Sets the stop point for the nearest stop (within 40 blocks), for the key. */
	public static String setNearest() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null || !LocationTracker.state().inHollows()) return "Only works in the Crystal Hollows.";
		Vec3 me = new Vec3(p.getX(), p.getY(), p.getZ());
		String best = null;
		double bestD = Math.max(40, ConfigManager.get().run.arriveRadius);
		for (StopPlan.Stop s : StopsTracker.stops()) {
			if (!SetPoints.isId(s.id())) continue;
			double d = s.target().sub(me).length();
			if (d < bestD) { bestD = d; best = s.id(); }
		}
		return best == null ? "No stop within 40 blocks. Use /nr setpoint <stop> (" + String.join(", ", SetPoints.IDS) + ")." : set(best);
	}

	public static String clear(String id) {
		id = id.toLowerCase(Locale.ROOT);
		if (id.equals("all")) {
			int n = points.points.size();
			synchronized (SetPointStore.class) { points.points.clear(); }
			save();
			StopsTracker.maybeRecompute(true);
			return "Removed all " + n + " stop point(s); the automatic spots are used again.";
		}
		boolean removed;
		synchronized (SetPointStore.class) { removed = points.clear(id); }
		if (!removed) return "No stop point of yours for '" + id + "'.";
		save();
		StopsTracker.maybeRecompute(true);
		return "Removed your stop point for " + label(id) + "; the automatic spot is used again.";
	}

	/** One line per stop for {@code /nr setpoint list}. */
	public static java.util.List<String> list() {
		java.util.List<String> out = new java.util.ArrayList<>();
		StructureMap map = StructureTracker.map();
		for (String id : SetPoints.IDS) {
			SetPoints.Point pt = points.get(id);
			if (pt == null) { out.add(label(id) + ": automatic"); continue; }
			String here = "";
			Vec3 anchor = LocationTracker.state().inHollows() ? StopPlan.anchor(id, map) : null;
			if (anchor != null) {
				Vec3 f = points.feet(id, anchor, map.orientation(StopPlan.structureOf(id)));
				StopPlan.Stop used = StopsTracker.stops().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
				boolean inUse = used != null && used.standFeet() != null && used.standFeet().sub(f).length() < 0.01;
				here = String.format(Locale.ROOT, " -> here %d %d %d%s", (int) Math.floor(f.x()), (int) Math.floor(f.y()), (int) Math.floor(f.z()),
						inUse ? " (used)" : " (blocked or not loaded; automatic spot used)");
			}
			out.add(String.format(Locale.ROOT, "%s: %+d %+d %+d from the %s%s", label(id), pt.dx(), pt.dy(), pt.dz(), anchorName(id), here));
		}
		return out;
	}

	private static String label(String id) {
		return id.equals("temple.exit") ? "Front of the temple" : StopPlan.structureOf(id).displayName;
	}

	private static String anchorName(String id) {
		return switch (id) {
			case "divan" -> "Jade crystal box";
			case "precursor" -> "Professor Robot";
			case "king" -> "King Yolkar";
			case "queen" -> "Amber crystal box";
			case "temple", "temple.exit" -> "Door Guardian";
			case "khazad" -> "Topaz crystal box";
			default -> "anchor";
		};
	}
}

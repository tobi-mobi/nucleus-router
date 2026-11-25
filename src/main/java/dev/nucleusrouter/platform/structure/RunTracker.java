package dev.nucleusrouter.platform.structure;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.game.RunPath;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import dev.nucleusrouter.platform.render.WorldDraw;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Run mode (SPEC §5, roadmap 3.5): shows the planned route while you run it. The next warp targets are
 * numbered and fade with distance down the list; a thin line follows the route (dashed where you fly
 * yourself, broken at /warp nucleus and the temple teleport); the next instruction ("/warp nucleus")
 * floats in front of you. Moves forward as you land on planned spots. Never re-plans: off the route,
 * the nearest route spot is highlighted instead.
 */
public final class RunTracker {
	private static final int[] PALETTE = {0x4FC3F7, 0xFFB74D, 0xBA68C8, 0x81C784, 0xF06292, 0xFFF176, 0x4DD0E1};
	private static volatile RunPath path;
	private static volatile int index;
	private static boolean finishedSaid;
	/** Gametests only: draw even outside the Hollows. */
	public static volatile boolean forceShowForTest;

	private RunTracker() {}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(c -> tick(c));
		WorldDraw.addLayerWhile(() -> path != null && (forceShowForTest || LocationTracker.state().inHollows()), RunTracker::draw);
	}

	public static boolean active() { return path != null; }
	public static RunPath path() { return path; }
	public static int index() { return index; }

	/** Starts run mode on the last planned route. Returns a message for the player. */
	public static String start() {
		RouteTracker.Route r = RouteTracker.current();
		if (r == null || !r.plan().hasRoute()) return "Plan a route first: /nr route";
		if (!java.util.Objects.equals(r.lobby(), StructureTracker.lobby())) return "The planned route is for another lobby: /nr route again";
		RunPath p = RunPath.build(r.plan(), r.stops(), r.legs(), r.estimated().keySet());
		path = p;
		index = 0;
		finishedSaid = false;
		long warps = p.nodes().stream().filter(n -> n.kind() == RunPath.Kind.WARP).count();
		return "Run mode on: " + warps + " planned warps. Follow the numbered blocks; /nr run stop to stop.";
	}

	/** Gametests only: run a given path. */
	public static void startForTest(RunPath p) {
		path = p;
		index = 0;
		finishedSaid = false;
	}

	public static void stop() {
		path = null;
	}

	private static void tick(Minecraft mc) {
		RunPath p = path;
		if (p == null || mc.player == null) return;
		Vec3 feet = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
		int next = p.advance(index, feet, ConfigManager.get().run.arriveRadius);
		if (next != index) index = next;
		if (index >= p.size() && !finishedSaid) {
			finishedSaid = true;
			NucleusRouterClient.showLocal(Component.literal("Route finished. /nr run to go again, /nr run stop to hide it.").withStyle(ChatFormatting.GREEN));
		}
	}

	private static void draw(WorldDraw draw) {
		RunPath p = path;
		Minecraft mc = Minecraft.getInstance();
		if (p == null || mc.player == null) return;
		NrConfig.Run cfg = ConfigManager.get().run;
		List<RunPath.Node> nodes = p.nodes();
		int at = Math.min(index, nodes.size());
		Vec3 feet = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());

		// The route line from where you are on: solid between warps, dashed where you fly yourself, broken at teleports
		if (cfg.showLine) {
			Vec3 prev = at > 0 ? nodes.get(at - 1).pos() : null;
			for (int i = at; i < nodes.size(); i++) {
				RunPath.Node n = nodes.get(i);
				int rgb = PALETTE[n.leg() % PALETTE.length];
				boolean teleport = n.kind() == RunPath.Kind.WARP_NUCLEUS || n.kind() == RunPath.Kind.TEMPLE_TELEPORT;
				if (prev != null && !teleport) {
					Vec3 a = prev.add(new Vec3(0, 0.15, 0)), b = n.pos().add(new Vec3(0, 0.15, 0));
					if (n.kind() == RunPath.Kind.FLY) dashed(draw, a, b, WorldDraw.argb(170, rgb));
					else draw.line(WorldDraw.mc(a), WorldDraw.mc(b), WorldDraw.argb(170, rgb), 2f, true);
				}
				prev = n.pos();
			}
		}

		// The next moves, numbered, the first one strongest: etherwarp targets as blocks, teleport stretches as arrows
		List<Integer> next = p.nextWarps(at, cfg.targetsShown);
		for (int k = next.size() - 1; k >= 0; k--) {
			RunPath.Node n = nodes.get(next.get(k));
			int rgb = PALETTE[n.leg() % PALETTE.length];
			double fade = 1.0 - k / (double) Math.max(1, cfg.targetsShown);
			if (n.kind() == RunPath.Kind.TELEPORT) {
				int idx = next.get(k);
				Vec3 from = idx > 0 ? nodes.get(idx - 1).pos() : feet;
				Vec3 a = from.add(new Vec3(0, 1.0, 0)), b2 = n.pos().add(new Vec3(0, 1.0, 0));
				int argb = WorldDraw.argb((int) (120 + 135 * fade), rgb);
				draw.line(WorldDraw.mc(a), WorldDraw.mc(b2), argb, k == 0 ? 5f : 3f, true);
				draw.marker(WorldDraw.mc(b2), 0.5, argb, WorldDraw.argb((int) (30 + 70 * fade), rgb), true);
				Vec3 label = a.add(b2.sub(a).scale(0.25)).add(new Vec3(0, 0.6, 0));
				double dist = label.sub(feet).length();
				float scale = (float) (0.5 * Math.max(1.0, dist / 12.0));
				draw.text((k + 1) + ": teleport x" + n.clicks(), WorldDraw.mc(label), WorldDraw.argb((int) (140 + 115 * fade), 0xFFFFFF), scale, true);
				continue;
			}
			int[] b = n.block();
			draw.block(b[0], b[1], b[2], WorldDraw.argb((int) (90 + 165 * fade), rgb), k == 0 ? 3f : 2f, WorldDraw.argb((int) (20 + 80 * fade), rgb), true);
			double dist = n.pos().sub(feet).length();
			float scale = (float) (0.5 * Math.max(1.0, dist / 12.0));
			draw.text(String.valueOf(k + 1), WorldDraw.mc(new Vec3(b[0] + 0.5, b[1] + 1.6 + scale, b[2] + 0.5)), WorldDraw.argb((int) (120 + 135 * fade), 0xFFFFFF), scale, true);
			if (k == 0 && cfg.aimDot && n.aimPoint() != null)
				draw.marker(WorldDraw.mc(n.aimPoint()), 0.12, WorldDraw.argb(255, 0xFFFFFF), WorldDraw.argb(200, 0xFFFFFF), true);
		}

		// Structures ahead: their names where you arrive
		for (int i = at; i < nodes.size(); i++) {
			RunPath.Node n = nodes.get(i);
			if (n.kind() != RunPath.Kind.STOP) continue;
			double dist = n.pos().sub(feet).length();
			float scale = (float) (0.6 * Math.max(1.0, dist / 12.0));
			draw.text(n.label(), WorldDraw.mc(n.pos().add(new Vec3(0, 2 + scale, 0))), WorldDraw.argb(230, PALETTE[n.leg() % PALETTE.length]), scale, true);
		}

		// The next instruction if it comes before the next warp: float it in front of you
		RunPath.Node instr = p.nextInstruction(at);
		boolean instrFirst = instr != null && (next.isEmpty() || nodes.indexOf(instr) < next.get(0));
		Vec3 eye = new Vec3(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ());
		Vec3 look = Vec3.fromYawPitch(mc.player.getYRot(), Math.max(-30, Math.min(30, mc.player.getXRot())));
		if (instrFirst && instr.kind() != RunPath.Kind.STOP) {
			String text = switch (instr.kind()) {
				case WARP_NUCLEUS -> "Type /warp nucleus";
				case TEMPLE_TELEPORT -> "Take the teleport to the front of the temple";
				default -> instr.label();
			};
			draw.text(text, WorldDraw.mc(eye.add(look.scale(4)).add(new Vec3(0, 0.6, 0))), WorldDraw.argb(255, 0xFFEB3B), 0.6f, true);
		}

		// Off the route: show the nearest route spot
		if (!next.isEmpty() && nodes.get(next.get(0)).pos().sub(feet).length() > ConfigManager.get().etherwarp.range + 10 && !instrFirst) {
			int ni = p.nearest(feet);
			if (ni >= 0) {
				Vec3 np = nodes.get(ni).pos();
				draw.box(new AABB(np.x() - 0.6, np.y(), np.z() - 0.6, np.x() + 0.6, np.y() + 1.8, np.z() + 0.6), WorldDraw.argb(255, 0xFFEB3B), 2f, WorldDraw.argb(50, 0xFFEB3B), true);
				draw.text("route", WorldDraw.mc(np.add(new Vec3(0, 2.4, 0))), WorldDraw.argb(255, 0xFFEB3B), 0.8f, true);
			}
		}
	}

	private static void dashed(WorldDraw draw, Vec3 a, Vec3 b, int argb) {
		Vec3 d = b.sub(a);
		double len = d.length();
		if (len < 1e-6) return;
		Vec3 u = d.scale(1 / len);
		for (double t = 0; t < len; t += 2.0)
			draw.line(WorldDraw.mc(a.add(u.scale(t))), WorldDraw.mc(a.add(u.scale(Math.min(len, t + 1.0)))), argb, 2f, true);
	}
}

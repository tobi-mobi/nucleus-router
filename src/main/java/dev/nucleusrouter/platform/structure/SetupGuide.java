package dev.nucleusrouter.platform.structure;

import dev.nucleusrouter.core.NucleusRouter;
import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.setup.BombPlanner;
import dev.nucleusrouter.core.setup.PlacementPlanner;
import dev.nucleusrouter.core.setup.SetupWorld;
import dev.nucleusrouter.game.LobbySetup;
import dev.nucleusrouter.game.SetupRepair;
import dev.nucleusrouter.game.SetupSteps;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import dev.nucleusrouter.platform.render.WorldDraw;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guided setup (SPEC §5 setup mode, roadmap 4.6): walks you through the lobby plan from {@code /nr setup}, one step
 * at a time. The current step is drawn strongly, the next few faintly:
 * <ul>
 *   <li><b>Throw</b> (B numbers, cyan): where to stand, the block to aim at, and the blast as a wireframe sphere.</li>
 *   <li><b>Mine</b> (M numbers, red): the blocks still to break.</li>
 *   <li><b>Place</b> (P numbers, yellow/orange): the block, where to stand and the face to click; pillars and
 *       "edge" placements say so.</li>
 * </ul>
 * Steps tick themselves off as the blocks change (checked every few ticks against the loaded world; see
 * {@link SetupSteps}); the "confirm / skip setup step" key (default ]) does it by hand.
 * <p>
 * When it doesn't go as planned (roadmap 4.7, setup mode only): a Bob-omb thrown during a throw step is followed for
 * {@link #JUDGE_AFTER_MS}; if its blocks aren't all gone by then it fizzled, missed or left blocks
 * ({@link SetupRepair#judge}), and that leg's remaining bomb work is planned again from the terrain as it is now.
 * Once every bomb is done, blocks still in a lane's way become mining steps, and then the placing is planned again
 * on the real terrain. {@code /nr setup replan} does the same by hand. Render-only: nothing is done for you.
 */
public final class SetupGuide {
	private static final int CHECK_EVERY_TICKS = 5;
	private static final int CHECK_WINDOW = 60;
	private static final int SHOW_AHEAD = 3;
	/** A throw is judged this long after it: flight (up to ~1.3 s for 32 blocks) + fuse (~2.3 s, measured) + margin. */
	static final long JUDGE_AFTER_MS = 4500;

	/** A Bob-omb thrown during a throw step, being followed. */
	private record Pending(SetupSteps.Step step, Vec3 eye, long thrownMs, List<int[]> broken) {}

	private static volatile Pending pending;
	/** Where bombs fizzled this setup: no blast is planned near them again. */
	private static final List<Vec3> fizzled = new ArrayList<>();
	private static boolean placingReplanned;
	private static volatile boolean replanning;

	private static volatile SetupSteps steps;
	private static int ticks;
	private static boolean finishedSaid;
	/** Gametests only: show and check even outside the Hollows. */
	public static volatile boolean forceShowForTest;

	private SetupGuide() {}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(SetupGuide::tick);
		WorldDraw.addLayerWhile(() -> steps != null && (forceShowForTest || LocationTracker.state().inHollows()), SetupGuide::draw);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(NucleusRouter.MOD_ID, "setup_guide"), SetupGuide::drawHud);
		UseItemCallback.EVENT.register((player, level, hand) -> { onUse(player); return InteractionResult.PASS; });
	}

	public static boolean active() { return steps != null; }

	/** Starts guiding through the last {@code /nr setup} plan. Returns a message for the player. */
	public static String start() {
		LobbySetup.Result plan = SetupPreview.lobbyPlan();
		if (plan == null) return "Plan the setup first: /nr setup";
		if (plan.setups().isEmpty()) return "The last plan needs no setup (every leg is fine on the terrain as it is).";
		SetupSteps s = SetupSteps.of(plan, ConfigManager.get().bombs.blastRadius);
		steps = s;
		finishedSaid = false;
		placingReplanned = false;
		pending = null;
		fizzled.clear();
		SetupPreview.clear();
		check(Minecraft.getInstance());
		return String.format("Guided setup on: %d steps (%d bombs, %d mining, %d placing). Bombs first, then placing. Steps tick themselves off; press the \"confirm / skip setup step\" key (default ]) to do it by hand. /nr setup back undoes one, /nr setup stop ends it.",
				s.size(), s.count(SetupSteps.Kind.THROW), s.count(SetupSteps.Kind.MINE), s.count(SetupSteps.Kind.PLACE));
	}

	public static void stop() { steps = null; pending = null; }

	/** Gametests only: guide through given steps. */
	public static void startForTest(SetupSteps s) {
		steps = s;
		finishedSaid = false;
	}

	/** The keybind and /nr setup skip. Returns a message, or null if not guiding. */
	public static String confirm() {
		SetupSteps s = steps;
		if (s == null) return null;
		SetupSteps.Step done = s.confirmCurrent();
		if (done == null) return "The setup is already finished.";
		NucleusRouterClient.LOG.info("[Nucleus Router] Setup: {} confirmed by hand", done.number());
		return done.number() + " marked done." + (s.finished() ? "" : " Next: " + s.currentStep().number() + ".");
	}

	/** /nr setup back. */
	public static String back() {
		SetupSteps s = steps;
		if (s == null) return "Guided setup is off: /nr setup start";
		SetupSteps.Step undone = s.back();
		return undone == null ? "Nothing to undo." : undone.number() + " is open again.";
	}

	private static void tick(Minecraft mc) {
		if (steps == null || mc.level == null || mc.player == null) return;
		Pending p = pending;
		if (p != null && System.currentTimeMillis() - p.thrownMs() > JUDGE_AFTER_MS) {
			pending = null;
			judge(mc, p);
		}
		if (++ticks % CHECK_EVERY_TICKS != 0) return;
		check(mc);
	}

	// ------------------------------------------------------------------ 4.7: following throws, re-planning

	private static void onUse(net.minecraft.world.entity.player.Player player) {
		Minecraft mc = Minecraft.getInstance();
		SetupSteps s = steps;
		if (s == null || player != mc.player || s.finished()) return;
		if (!player.getMainHandItem().getHoverName().getString().toLowerCase(Locale.ROOT).contains("bob-omb")) return;
		SetupSteps.Step st = s.currentStep();
		if (st.kind() != SetupSteps.Kind.THROW) return;
		pending = new Pending(st, new Vec3(player.getX(), player.getEyeY(), player.getZ()), System.currentTimeMillis(), new ArrayList<>());
	}

	/** From WorldCapture: every block change on the client thread. Collects what breaks after a throw. */
	public static void onBlockChanged(BlockPos pos, BlockState state, boolean byPlayer) {
		Pending p = pending;
		if (p == null || byPlayer || !state.isAir()) return;
		Vec3 c = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		// Near the planned blast or anywhere the bomb could have gone (it flies at most ~32 blocks)
		if (c.sub(p.step().bomb().centre()).length() > 12 && c.sub(p.eye()).length() > 45) return;
		p.broken().add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
	}

	private static void judge(Minecraft mc, Pending p) {
		SetupSteps s = steps;
		if (s == null || mc.level == null) return;
		int idx = s.steps().indexOf(p.step());
		if (idx < 0 || s.done(idx)) return; // ticked off by itself: as planned
		SetupRepair.Judgement j = SetupRepair.judge(p.step(), p.broken(), liveSolidPassThrough(mc.level));
		NucleusRouterClient.LOG.info("[Nucleus Router] Setup: {} judged {} (broke {}, left {}, off by {} blocks, centre {})", p.step().number(), j.outcome(),
				j.broken(), j.leftover(), String.format(Locale.ROOT, "%.1f", j.offBy()), j.centre());
		String what = switch (j.outcome()) {
			case DONE -> null;
			case FIZZLED -> {
				Vec3 c = p.step().bomb().centre();
				fizzled.add(c);
				yield String.format(Locale.ROOT, "%s did nothing (fizzled? Bombs centred inside a structure's zone do nothing). No bomb will be planned within %.0f blocks of %.0f %.0f %.0f again.",
						p.step().number(), SetupRepair.FIZZLE_RADIUS, c.x(), c.y(), c.z());
			}
			case MISSED -> String.format(Locale.ROOT, "%s went off %.0f blocks from the plan.", p.step().number(), j.offBy());
			case PARTIAL -> String.format(Locale.ROOT, "%s left %d of its blocks standing.", p.step().number(), j.leftover());
		};
		if (what == null) return;
		NucleusRouterClient.showLocal(Component.literal(what + " Re-planning this leg's remaining bombs from the terrain as it is now...").withStyle(ChatFormatting.GOLD));
		replanLeg(p.step().leg());
	}

	/** Re-plans one leg's open bomb work on a fresh snapshot (worker thread), then swaps the steps in. */
	private static void replanLeg(int leg) {
		SetupSteps s = steps;
		if (s == null || replanning || leg >= s.designs().size()) return;
		replanning = true;
		SetupPreview.Inputs in = SetupPreview.inputs();
		List<Vec3> noBomb = List.copyOf(fizzled);
		int[] numbers = {s.lastNumber(SetupSteps.Kind.THROW), s.lastNumber(SetupSteps.Kind.MINE)};
		var design = s.designs().get(leg);
		dev.nucleusrouter.platform.world.WorldCapture.snapshot().thenApplyAsync(snap ->
				SetupRepair.replanBombs(leg, design, SetupWorld.of(snap, in.zones(), in.explored()), noBomb, in.cfg(), numbers), WORKER)
				.whenComplete((fresh, err) -> Minecraft.getInstance().execute(() -> {
					replanning = false;
					if (err != null) { NucleusRouterClient.reportAsyncError(err); return; }
					if (steps != s) return; // guide restarted meanwhile
					s.replaceOpenBombWork(leg, fresh);
					long t = fresh.stream().filter(x -> x.kind() == SetupSteps.Kind.THROW).count();
					NucleusRouterClient.showLocal(Component.literal(String.format("Re-planned: %d throw(s) and %d mining step(s) for this leg.%s", t, fresh.size() - t,
							s.finished() ? "" : " Next: " + s.currentStep().number() + ".")).withStyle(ChatFormatting.AQUA));
				}));
	}

	/**
	 * Every bomb is done: blocks still in a lane's way become mining; once there are none, the placing is planned
	 * again on the real terrain (worker thread).
	 */
	private static void replanPlacing(Minecraft mc) {
		SetupSteps s = steps;
		if (s == null || replanning || mc.level == null) return;
		List<SetupSteps.Step> left = SetupRepair.leftovers(s.designs(), liveSolidPassThrough(mc.level), s.lastNumber(SetupSteps.Kind.MINE));
		if (!left.isEmpty()) {
			int blocks = left.stream().mapToInt(x -> x.blocks().size()).sum();
			s.insertBeforePlacing(left);
			NucleusRouterClient.showLocal(Component.literal(blocks + " block(s) the tunnels need gone are still there: mine them first (" + left.get(0).number()
					+ (left.size() > 1 ? " to " + left.get(left.size() - 1).number() : "") + ").").withStyle(ChatFormatting.GOLD));
			return;
		}
		placingReplanned = true;
		replanning = true;
		SetupPreview.Inputs in = SetupPreview.inputs();
		var designs = s.designs();
		int doneP = s.doneCount(SetupSteps.Kind.PLACE);
		int lastP = doneP == 0 ? 0 : s.lastNumber(SetupSteps.Kind.PLACE);
		int before = s.count(SetupSteps.Kind.PLACE) - doneP;
		dev.nucleusrouter.platform.world.WorldCapture.snapshot().thenApplyAsync(snap ->
				LobbySetup.placeAll(designs, SetupWorld.of(snap, in.zones(), in.explored()), java.util.Set.of(), in.cfg(), in.ping(), i -> {}), WORKER)
				.whenComplete((plans, err) -> Minecraft.getInstance().execute(() -> {
					replanning = false;
					if (err != null) { NucleusRouterClient.reportAsyncError(err); return; }
					if (steps != s) return;
					List<SetupSteps.Step> fresh = SetupSteps.placeSteps(plans, lastP);
					s.replaceOpenPlacements(fresh);
					int impossible = plans.stream().mapToInt(p -> p.impossible().size()).sum();
					NucleusRouterClient.showLocal(Component.literal(String.format("All bombs done. Placing planned again on the real terrain: %d block(s) to place (the first plan had %d).%s",
							fresh.size(), before, impossible > 0 ? " " + impossible + " target block(s) can't be placed: /nr verify shows which warps that affects." : ""))
							.withStyle(impossible > 0 ? ChatFormatting.GOLD : ChatFormatting.AQUA));
				}));
	}

	/** /nr setup replan: plan the current step's leg again now (or the placing, once the bombs are done). */
	public static String replanNow() {
		SetupSteps s = steps;
		if (s == null) return "Guided setup is off: /nr setup start";
		if (replanning) return "Already re-planning, wait a moment.";
		if (s.finished()) return "The setup is finished.";
		if (s.bombWorkDone()) {
			placingReplanned = false;
			replanPlacing(Minecraft.getInstance());
			return "Re-planning the placing on the terrain as it is now...";
		}
		replanLeg(s.currentStep().leg());
		return "Re-planning this leg's remaining bombs on the terrain as it is now...";
	}

	private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "nucleusrouter-setup-repair");
		t.setDaemon(true);
		return t;
	});

	private static SolidQuery liveSolidPassThrough(ClientLevel level) {
		Set<String> passThrough = new HashSet<>(ConfigManager.get().etherwarp.rayPassesThrough);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		return (x, y, z) -> {
			BlockState st = level.getBlockState(pos.set(x, y, z));
			return !st.isAir() && !passThrough.contains(BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
		};
	}

	private static void check(Minecraft mc) {
		SetupSteps s = steps;
		if (s == null || mc.level == null || !(forceShowForTest || LocationTracker.state().inHollows())) return;
		ClientLevel level = mc.level;
		SolidQuery live = liveSolidPassThrough(level);
		SetupSteps.Loaded loaded = (x, y, z) -> level.hasChunkAt(x, z);
		int newly = s.update(live, loaded, CHECK_WINDOW);
		if (newly > 0) NucleusRouterClient.LOG.info("[Nucleus Router] Setup: {} step(s) done by themselves, now at {}", newly,
				s.finished() ? "the end" : s.currentStep().number());
		if (s.finished() && !finishedSaid) {
			finishedSaid = true;
			NucleusRouterClient.showLocal(Component.literal("Setup finished! Plan the run again (/nr route) and try it (/nr run). /nr setup stop hides the guide.")
					.withStyle(ChatFormatting.GREEN));
		} else if (!s.finished() && s.bombWorkDone() && !placingReplanned && !replanning && !s.designs().isEmpty()) {
			// Every bomb done: leftovers to mine, or the placing planned again on the real terrain
			replanPlacing(mc);
		}
	}

	// ------------------------------------------------------------------ drawing

	private static void draw(WorldDraw draw) {
		SetupSteps s = steps;
		Minecraft mc = Minecraft.getInstance();
		if (s == null || mc.player == null) return;
		NrConfig cfg = ConfigManager.get();
		int shown = 0;
		for (int i = s.current(); i < s.size() && shown <= SHOW_AHEAD; i++) {
			if (s.done(i)) continue;
			drawStep(draw, s.steps().get(i), shown == 0, cfg);
			shown++;
		}
	}

	private static void drawStep(WorldDraw draw, SetupSteps.Step st, boolean current, NrConfig cfg) {
		int a = current ? 255 : 110, fillA = current ? 70 : 25;
		switch (st.kind()) {
			case THROW -> {
				BombPlanner.Throw t = st.bomb();
				int cyan = 0x00E5FF;
				draw.marker(WorldDraw.mc(t.standFeet().add(new Vec3(0, 0.1, 0))), current ? 0.4 : 0.25, WorldDraw.argb(a, cyan), WorldDraw.argb(fillA, cyan), true);
				draw.text(st.number() + (current ? " stand here" : ""), WorldDraw.mc(t.standFeet().add(new Vec3(0, 2.3, 0))), WorldDraw.argb(a, cyan), current ? 0.45f : 0.35f, true);
				if (!current) return;
				int[] ib = t.impactBlock();
				if (ib != null) draw.outline(new AABB(ib[0], ib[1], ib[2], ib[0] + 1, ib[1] + 1, ib[2] + 1), WorldDraw.argb(255, 0xFFFFFF), 3f, true);
				draw.marker(WorldDraw.mc(t.aimPoint()), 0.15, WorldDraw.argb(255, 0xFFFFFF), WorldDraw.argb(200, 0xFFFFFF), true);
				draw.text(t.dropped() ? "aim here (it drops at the end of its flight)" : "aim here", WorldDraw.mc(t.aimPoint().add(new Vec3(0, 0.8, 0))),
						WorldDraw.argb(255, 0xFFFFFF), 0.4f, true);
				draw.line(WorldDraw.mc(t.standFeet().add(new Vec3(0, cfg.etherwarp.eyeHeight, 0))), WorldDraw.mc(t.aimPoint()), WorldDraw.argb(160, cyan), 1.5f, true);
				sphere(draw, t.centre(), cfg.bombs.blastRadius, WorldDraw.argb(170, cyan));
			}
			case MINE -> {
				int red = 0xFF3030;
				SolidQuery live = liveSolid();
				for (int[] q : st.blocks()) {
					if (live != null && !live.solid(q[0], q[1], q[2])) continue;
					draw.outline(new AABB(q[0], q[1], q[2], q[0] + 1, q[1] + 1, q[2] + 1), WorldDraw.argb(a, red), current ? 3f : 1.5f, true);
				}
				int[] f = st.blocks().get(0);
				draw.text(st.number() + (current ? " mine these" : ""), WorldDraw.mc(new Vec3(f[0] + 0.5, f[1] + 1.6, f[2] + 0.5)), WorldDraw.argb(a, red), current ? 0.45f : 0.35f, true);
			}
			case PLACE -> {
				PlacementPlanner.Step p = st.place();
				int[] b = p.block();
				int rgb = p.helper() ? 0xFFEB3B : 0xFF9800;
				draw.block(b[0], b[1], b[2], WorldDraw.argb(a, rgb), current ? 3f : 1.5f, WorldDraw.argb(fillA, rgb), true);
				String what = p.pillar() ? " pillar: jump, place under you" : p.edge() ? " sneak to the edge, click the face" : "";
				draw.text(st.number() + (current ? what : ""), WorldDraw.mc(new Vec3(b[0] + 0.5, b[1] + 1.4, b[2] + 0.5)), WorldDraw.argb(a, 0xFFEB3B), current ? 0.45f : 0.35f, true);
				if (!current || p.pillar()) return;
				draw.marker(WorldDraw.mc(p.standFeet().add(new Vec3(0, 0.05, 0))), 0.3, WorldDraw.argb(255, 0xFFEB3B), WorldDraw.argb(80, 0xFFEB3B), true);
				Vec3 c = p.clickPoint();
				double e = 0.3, t = 0.02;
				AABB face = switch (p.face()) {
					case UP, DOWN -> new AABB(c.x() - e, c.y() - t, c.z() - e, c.x() + e, c.y() + t, c.z() + e);
					case NORTH, SOUTH -> new AABB(c.x() - e, c.y() - e, c.z() - t, c.x() + e, c.y() + e, c.z() + t);
					case WEST, EAST -> new AABB(c.x() - t, c.y() - e, c.z() - e, c.x() + t, c.y() + e, c.z() + e);
				};
				draw.box(face, WorldDraw.argb(255, 0xFFEB3B), 2f, WorldDraw.argb(140, 0xFFEB3B), true);
			}
		}
	}

	/** A wireframe sphere: 3 rings through the centre and 2 more above and below. */
	static void sphere(WorldDraw draw, Vec3 c, double r, int argb) {
		int seg = 32;
		double[][] rings = {{0, 1}, {Math.sin(Math.toRadians(45)), Math.cos(Math.toRadians(45))}, {-Math.sin(Math.toRadians(45)), Math.cos(Math.toRadians(45))}};
		for (double[] ring : rings) { // horizontal rings at height h * r with radius k * r
			double h = ring[0] * r, rr = ring[1] * r;
			for (int i = 0; i < seg; i++) {
				double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
				draw.line(WorldDraw.mc(c.add(new Vec3(Math.cos(a0) * rr, h, Math.sin(a0) * rr))), WorldDraw.mc(c.add(new Vec3(Math.cos(a1) * rr, h, Math.sin(a1) * rr))), argb, 1.5f, true);
			}
		}
		for (int v = 0; v < 2; v++) { // two vertical rings
			for (int i = 0; i < seg; i++) {
				double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
				Vec3 p0 = v == 0 ? new Vec3(Math.cos(a0) * r, Math.sin(a0) * r, 0) : new Vec3(0, Math.sin(a0) * r, Math.cos(a0) * r);
				Vec3 p1 = v == 0 ? new Vec3(Math.cos(a1) * r, Math.sin(a1) * r, 0) : new Vec3(0, Math.sin(a1) * r, Math.cos(a1) * r);
				draw.line(WorldDraw.mc(c.add(p0)), WorldDraw.mc(c.add(p1)), argb, 1.5f, true);
			}
		}
	}

	private static SolidQuery liveSolid() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return null;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		return (x, y, z) -> !level.getBlockState(pos.set(x, y, z)).isAir();
	}

	// ------------------------------------------------------------------ HUD

	private static void drawHud(GuiGraphicsExtractor g, DeltaTracker delta) {
		SetupSteps s = steps;
		Minecraft mc = Minecraft.getInstance();
		if (s == null || mc.player == null || !(forceShowForTest || LocationTracker.state().inHollows())) return;
		if (mc.getDebugOverlay().showDebugScreen()) return;
		List<String> lines = new ArrayList<>();
		lines.add(String.format(Locale.ROOT, "Setup %d/%d  bombs %d/%d  mining %d/%d  placing %d/%d", s.doneCount(), s.size(),
				s.doneCount(SetupSteps.Kind.THROW), s.count(SetupSteps.Kind.THROW), s.doneCount(SetupSteps.Kind.MINE), s.count(SetupSteps.Kind.MINE),
				s.doneCount(SetupSteps.Kind.PLACE), s.count(SetupSteps.Kind.PLACE)));
		SetupSteps.Step st = s.currentStep();
		if (st == null) {
			lines.add("Setup finished: /nr route, then /nr run");
		} else {
			lines.add(instruction(st));
			double d = st.where().sub(new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ())).length();
			lines.add(String.format(Locale.ROOT, "%.0f m away   (%s = done / skip)", d, dev.nucleusrouter.platform.Keys.setupConfirmName()));
		}
		int w = 0;
		for (String l : lines) w = Math.max(w, mc.font.width(l));
		int x = g.guiWidth() / 2 - w / 2, y = 16;
		g.fill(x - 3, y - 2, x + w + 3, y + lines.size() * (mc.font.lineHeight + 1) + 1, 0x90000000);
		int ty = y;
		for (int i = 0; i < lines.size(); i++) {
			g.text(mc.font, lines.get(i), x, ty, i == 1 ? 0xFFFFEB3B : 0xFFFFFFFF, true);
			ty += mc.font.lineHeight + 1;
		}
	}

	static String instruction(SetupSteps.Step st) {
		return switch (st.kind()) {
			case THROW -> st.number() + ": stand on the cyan spot, aim at the white block, throw a Bob-omb"
					+ (st.bomb().dropped() ? " (it flies its full range, then drops)" : "");
			case MINE -> st.number() + ": mine the " + st.blocks().size() + " red block" + (st.blocks().size() == 1 ? "" : "s");
			case PLACE -> {
				PlacementPlanner.Step p = st.place();
				if (p.pillar()) yield st.number() + ": pillar up here: jump and place a block under you" + (p.helper() ? "" : " (this one is the target)");
				if (p.edge()) yield st.number() + ": on the pillar top, sneak to the edge and click the yellow face";
				yield st.number() + ": stand on the yellow spot, click the yellow face" + (p.helper() ? " (helper block)" : "");
			}
		};
	}
}

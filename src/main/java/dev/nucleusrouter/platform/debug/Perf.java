package dev.nucleusrouter.platform.debug;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Performance pass (roadmap 5.3): times every world overlay, HUD panel and per-tick job of the mod on the client
 * thread, for {@code /nr perf}. Budget: under 0.5 ms per frame for the whole mod's drawing. Measuring costs two
 * {@code nanoTime} calls per item.
 */
public final class Perf {
	public enum Kind { OVERLAY, HUD, TICK }

	/** Running stats for one item: exponential average plus the worst time since the last reset. */
	private static final class Stat {
		final Kind kind;
		final String name;
		volatile double avgMicros;
		volatile double maxMicros;
		volatile long calls;

		Stat(Kind kind, String name) { this.kind = kind; this.name = name; }

		void add(double us) {
			avgMicros = calls == 0 ? us : avgMicros * 0.97 + us * 0.03;
			if (us > maxMicros) maxMicros = us;
			calls++;
		}
	}

	private static final Map<String, Stat> STATS = new ConcurrentHashMap<>();

	private Perf() {}

	/** Name of the class that called into the mod's registration helper (the overlay's or job's owner). */
	public static String caller() {
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(s -> s
				.map(f -> f.getDeclaringClass())
				.filter(c -> c != Perf.class && !c.getName().startsWith("dev.nucleusrouter.platform.render.WorldDraw"))
				.findFirst().map(Class::getSimpleName).orElse("?"));
	}

	public static void record(Kind kind, String name, long startNanos) {
		double us = (System.nanoTime() - startNanos) / 1000.0;
		STATS.computeIfAbsent(kind + ":" + name, k -> new Stat(kind, name)).add(us);
	}

	/** Registers an end-of-tick job, timed under its owner's class name. Use instead of {@code END_CLIENT_TICK.register}. */
	public static void onTick(ClientTickEvents.EndTick job) {
		String name = caller();
		ClientTickEvents.END_CLIENT_TICK.register(c -> {
			long t = System.nanoTime();
			try {
				job.onEndTick(c);
			} finally {
				record(Kind.TICK, name, t);
			}
		});
	}

	/** Wraps a HUD element so it's timed under {@code name}. */
	public static net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement hud(String name, net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement e) {
		return (g, delta) -> {
			long t = System.nanoTime();
			try {
				e.extractRenderState(g, delta);
			} finally {
				record(Kind.HUD, name, t);
			}
		};
	}

	/** Forgets the worst times (averages keep running). */
	public static void resetMax() {
		for (Stat s : STATS.values()) s.maxMicros = 0;
	}

	/** Lines for chat, slowest first per kind. */
	public static List<String> report() {
		List<String> out = new ArrayList<>();
		for (Kind k : Kind.values()) {
			List<Stat> list = STATS.values().stream().filter(s -> s.kind == k && s.calls > 0)
					.sorted((a, b) -> Double.compare(b.avgMicros, a.avgMicros)).toList();
			double sum = list.stream().mapToDouble(s -> s.avgMicros).sum();
			String what = switch (k) { case OVERLAY -> "World overlays (per frame)"; case HUD -> "HUD panels (per frame)"; case TICK -> "Tick jobs (20 per second)"; };
			out.add(String.format(Locale.ROOT, "%s: %.3f ms total", what, sum / 1000));
			for (Stat s : list)
				if (s.avgMicros >= 1 || s.maxMicros >= 100)
					out.add(String.format(Locale.ROOT, "  %s: avg %.3f ms, worst %.2f ms", s.name, s.avgMicros / 1000, s.maxMicros / 1000));
		}
		return out;
	}

	/** Average per-frame drawing time of the whole mod (overlays + HUD), in milliseconds. */
	public static double frameMillis() {
		return STATS.values().stream().filter(s -> s.kind != Kind.TICK).mapToDouble(s -> s.avgMicros).sum() / 1000;
	}
}

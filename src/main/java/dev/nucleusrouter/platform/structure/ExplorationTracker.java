package dev.nucleusrouter.platform.structure;

import com.google.gson.Gson;
import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.location.LocationState;
import dev.nucleusrouter.core.world.LobbyIdentity;
import dev.nucleusrouter.game.Coverage;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exploration coverage (SPEC §4, roadmap 2.4): once a second, marks the Hollows columns within
 * {@code exploration.visitRadius} of you as visited; every chunk your game receives counts as loaded.
 * Kept per lobby and saved to {@code nucleusrouter/lobbies/<server>.coverage.json}. Read-only.
 */
public final class ExplorationTracker {
	private static final Gson GSON = new Gson();
	private static final int EVERY_TICKS = 20;
	private static final long SAVE_EVERY_MS = 60_000;
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "NucleusRouter-CoverageIO");
		t.setDaemon(true);
		return t;
	});

	private record Saved(String lobby, long clockTicks, long savedAtMs, Coverage.Saved coverage) {}

	private static Coverage coverage = fresh();
	private static String lobby;
	private static long savedVersion, lastSaveMs;
	private static int ticks;

	private ExplorationTracker() {}

	public static void init() {
		dev.nucleusrouter.platform.debug.Perf.onTick(c -> { if (++ticks % EVERY_TICKS == 0) tick(c); });
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			if (lobby != null && LocationTracker.state().inHollows())
				coverage.loadChunk(chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ());
		});
		ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> save());
		ClientLifecycleEvents.CLIENT_STOPPING.register(c -> save());
	}

	public static Coverage coverage() { return coverage; }

	private static Coverage fresh() {
		NrConfig.Hollows h = ConfigManager.get().hollows;
		return new Coverage(h.minX, h.maxX, h.minZ, h.maxZ);
	}

	private static void tick(Minecraft client) {
		LocationState loc = LocationTracker.state();
		if (!loc.inHollows() || loc.serverId() == null || client.player == null) return;
		switchLobby(loc);
		coverage.visit(client.player.getX(), client.player.getZ(), ConfigManager.get().exploration.visitRadius);
		if (coverage.version() != savedVersion && System.currentTimeMillis() - lastSaveMs > SAVE_EVERY_MS) save();
	}

	private static void switchLobby(LocationState loc) {
		if (loc.serverId().equals(lobby)) return;
		save();
		lobby = loc.serverId();
		coverage = fresh();
		savedVersion = coverage.version();
		lastSaveMs = System.currentTimeMillis();
		if (!ConfigManager.get().general.saveLobbies) return;
		Path file = fileFor(lobby);
		if (!Files.exists(file)) return;
		try {
			Saved s = GSON.fromJson(Files.readString(file), Saved.class);
			NrConfig.Hollows h = ConfigManager.get().hollows;
			if (s != null && LobbyIdentity.isSameLobby(s.clockTicks(), s.savedAtMs(), loc.clockTicks(), System.currentTimeMillis())) {
				coverage = Coverage.restore(s.coverage(), h.minX, h.maxX, h.minZ, h.maxZ);
				savedVersion = coverage.version();
			} else {
				Files.move(file, file.resolveSibling(file.getFileName() + ".old"), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException e) {
			NucleusRouterClient.LOG.warn("[Nucleus Router] Could not read {}: {}. Starting fresh.", file, e.toString());
		}
	}

	private static void save() {
		if (lobby == null || coverage.version() == savedVersion || !ConfigManager.get().general.saveLobbies) return;
		String json = GSON.toJson(new Saved(lobby, LocationTracker.state().clockTicks(), System.currentTimeMillis(), coverage.save()));
		Path file = fileFor(lobby);
		savedVersion = coverage.version();
		lastSaveMs = System.currentTimeMillis();
		IO.execute(() -> {
			try {
				Files.createDirectories(file.getParent());
				Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
				Files.writeString(tmp, json);
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				NucleusRouterClient.LOG.warn("[Nucleus Router] Could not save {}: {}", file, e.toString());
			}
		});
	}

	private static Path fileFor(String serverId) {
		return FabricLoader.getInstance().getGameDir().resolve("nucleusrouter").resolve("lobbies")
				.resolve(serverId.replaceAll("[^A-Za-z0-9_-]", "_") + ".coverage.json");
	}
}

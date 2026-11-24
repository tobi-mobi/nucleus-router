package dev.nucleusrouter.platform.world;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.location.LocationState;
import dev.nucleusrouter.core.world.BlockClassifier;
import dev.nucleusrouter.core.world.LobbyIdentity;
import dev.nucleusrouter.core.world.WorldModel;
import dev.nucleusrouter.core.world.WorldSnapshot;
import dev.nucleusrouter.core.world.WorldStore;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Feeds the {@link WorldModel} from chunk loads and block changes, and saves it per lobby.
 *
 * Threading: the client thread only copies chunk sections (cheap) and queues work.
 * All conversion, merging, loading and saving runs on one background "world" thread that
 * owns the model, so the game never waits on it (CLAUDE.md hard rule).
 */
public final class WorldCapture {
	private static final long AUTOSAVE_MS = 120_000;

	private static final ExecutorService WORLD = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "NucleusRouter-World");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});

	// ---- client thread only ----
	private static ClientLevel trackedLevel;
	private static String requestedLobby;
	private static String stableCandidate;
	private static int stableTicks;
	private static final int ATTACH_AFTER_TICKS = 100;

	// ---- world thread only ----
	private static WorldModel model;
	private static final Map<BlockState, Integer> idCache = new IdentityHashMap<>();
	private static String lobby;          // server id the model is attached to, or null
	private static String lobbyNote = "not in the Crystal Hollows yet";
	private static long savedVersion = -1;
	private static long lastSaveMs = System.currentTimeMillis();

	// ---- stats, any thread ----
	private static final AtomicLong chunksSeen = new AtomicLong();
	private static final AtomicLong blockChanges = new AtomicLong();
	private static volatile String status = "idle";

	private WorldCapture() {}

	public static void init() {
		WORLD.execute(() -> model = new WorldModel(classifier()));

		ClientChunkEvents.CHUNK_LOAD.register(WorldCapture::onChunkLoad);
		ClientTickEvents.END_CLIENT_TICK.register(WorldCapture::onTick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			trackedLevel = null;
			requestedLobby = null;
			WORLD.execute(() -> { saveIfDirty("disconnect"); detachAndReset(); });
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			WORLD.execute(() -> saveIfDirty("game closing"));
			WORLD.shutdown();
			try { WORLD.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
		});
		ConfigManager.onChange(() -> {
			BlockClassifier c = classifier();
			WORLD.execute(() -> { if (model != null) model.setClassifier(c); });
		});
	}

	private static BlockClassifier classifier() {
		return BlockClassifier.fromConfig(ConfigManager.get());
	}

	// ------------------------------------------------------------------ client thread

	private static void ensureLevel(ClientLevel level) {
		if (level == trackedLevel) return;
		trackedLevel = level;
		requestedLobby = null;
		stableCandidate = null;
		stableTicks = 0;
		// New world (warp, server switch): save the old lobby, start an empty model.
		WORLD.execute(() -> { saveIfDirty("world change"); detachAndReset(); });
	}

	private static boolean inBounds(NrConfig.Hollows h, int minX, int maxX, int minZ, int maxZ) {
		return maxX >= h.minX && minX <= h.maxX && maxZ >= h.minZ && minZ <= h.maxZ;
	}

	private static void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		ensureLevel(level);
		NrConfig.Hollows h = ConfigManager.get().hollows;
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		if (!inBounds(h, bx, bx + 15, bz, bz + 15)) return;
		int sx = bx >> 4, sz = bz >> 4;
		LevelChunkSection[] sections = chunk.getSections();
		for (int idx = 0; idx < sections.length; idx++) {
			int sy = level.getSectionYFromSectionIndex(idx);
			if ((sy << 4) + 15 < h.minY || (sy << 4) > h.maxY) continue;
			LevelChunkSection section = sections[idx];
			if (section == null) continue;
			PalettedContainer<BlockState> copy = section.getStates().copy(); // cheap; the rest happens off-thread
			int fsy = sy;
			WORLD.execute(() -> convertSection(sx, fsy, sz, copy));
		}
		chunksSeen.incrementAndGet();
	}

	/** Called by {@code ClientLevelMixin} after any block change the client applies. */
	public static void onBlockChanged(ClientLevel level, BlockPos pos, BlockState state) {
		ensureLevel(level);
		NrConfig.Hollows h = ConfigManager.get().hollows;
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		if (x < h.minX || x > h.maxX || y < h.minY || y > h.maxY || z < h.minZ || z > h.maxZ) return;
		blockChanges.incrementAndGet();
		WORLD.execute(() -> { if (model != null) model.observe(x, y, z, idOf(state)); });
	}

	private static void onTick(Minecraft client) {
		if (client.level == null) return;
		ensureLevel(client.level);
		LocationState loc = LocationTracker.state();
		String candidate = loc.inHollows() ? loc.serverId() : null;
		if (!java.util.Objects.equals(candidate, stableCandidate)) {
			stableCandidate = candidate;
			stableTicks = 0;
		} else {
			stableTicks++;
		}
		// Wait until the lobby id has been the same for 5 s in this world, so a stale id from the
		// previous lobby (location sources update a moment after a warp) can't attach the wrong save.
		if (candidate != null && stableTicks >= ATTACH_AFTER_TICKS && !candidate.equals(requestedLobby)) {
			requestedLobby = candidate;
			String server = loc.serverId();
			long ticks = loc.clockTicks();
			WORLD.execute(() -> attach(server, ticks));
		}
		if (System.currentTimeMillis() - lastSaveMs > AUTOSAVE_MS) {
			lastSaveMs = System.currentTimeMillis();
			WORLD.execute(() -> saveIfDirty("autosave"));
		}
	}

	// ------------------------------------------------------------------ world thread

	private static int idOf(BlockState state) {
		Integer id = idCache.get(state);
		if (id == null) {
			id = model.blockId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
			idCache.put(state, id);
		}
		return id;
	}

	private static void convertSection(int sx, int sy, int sz, PalettedContainer<BlockState> states) {
		if (model == null) return;
		int[] ids = new int[4096];
		int i = 0;
		for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) ids[i++] = idOf(states.get(x, y, z));
		model.observeSection(sx, sy, sz, ids);
	}

	private static void detachAndReset() {
		model = new WorldModel(classifier());
		idCache.clear();
		lobby = null;
		lobbyNote = "not in the Crystal Hollows yet";
		savedVersion = -1;
		status = "idle";
	}

	/** Links the model to a lobby and merges what an earlier visit saved, if it's the same lobby. */
	private static void attach(String serverId, long clockTicks) {
		if (serverId.equals(lobby)) return;
		lobby = serverId;
		Path file = fileFor(serverId);
		if (!Files.exists(file)) {
			lobbyNote = "new lobby (no save file yet)";
			status = "capturing " + serverId;
			NucleusRouterClient.LOG.info("[Nucleus Router] World: lobby {} has no save yet, starting fresh.", serverId);
			return;
		}
		try (InputStream in = Files.newInputStream(file)) {
			WorldStore.Loaded loaded = WorldStore.read(in, classifier());
			WorldStore.Meta m = loaded.meta();
			if (LobbyIdentity.isSameLobby(m.clockTicks(), m.savedAtMillis(), clockTicks, System.currentTimeMillis())) {
				model.mergeOlder(loaded.model());
				lobbyNote = "loaded save (" + loaded.model().sectionCount() + " sections)";
				NucleusRouterClient.LOG.info("[Nucleus Router] World: loaded {} sections for lobby {}.", loaded.model().sectionCount(), serverId);
			} else {
				Path old = file.resolveSibling(file.getFileName() + ".old");
				Files.move(file, old, StandardCopyOption.REPLACE_EXISTING);
				lobbyNote = "server name reused by a new lobby; old save moved to " + old.getFileName();
				NucleusRouterClient.LOG.info("[Nucleus Router] World: {} is a new lobby (clock {} vs saved {}), old save moved aside.", serverId, clockTicks, m.clockTicks());
			}
		} catch (IOException | RuntimeException e) {
			lobbyNote = "save file unreadable (" + e.getMessage() + "), starting fresh";
			NucleusRouterClient.LOG.error("[Nucleus Router] World: could not read {}: {}. Starting fresh.", file, e.toString());
		}
		status = "capturing " + serverId;
	}

	private static void saveIfDirty(String reason) {
		if (model == null || lobby == null || model.version() == savedVersion) return;
		long start = System.nanoTime();
		WorldSnapshot snap = model.snapshot();
		Path file = fileFor(lobby);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			try (OutputStream out = Files.newOutputStream(tmp)) {
				WorldStore.write(snap, new WorldStore.Meta(lobby, LocationTracker.state().clockTicks(), System.currentTimeMillis()), out);
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			savedVersion = snap.version();
			NucleusRouterClient.LOG.info("[Nucleus Router] World: saved lobby {} ({} sections, {} KB, {} ms, {}).",
					lobby, snap.sectionCount(), Files.size(file) / 1024, (System.nanoTime() - start) / 1_000_000, reason);
		} catch (IOException e) {
			NucleusRouterClient.LOG.error("[Nucleus Router] World: could not save {}: {}", file, e.toString());
		}
	}

	private static Path fileFor(String serverId) {
		String safe = serverId.replaceAll("[^A-Za-z0-9_-]", "_");
		return FabricLoader.getInstance().getGameDir().resolve("nucleusrouter").resolve("lobbies").resolve(safe + ".nrw");
	}

	// ------------------------------------------------------------------ for commands

	/** A snapshot taken on the world thread. Never call {@code join()} on the client thread. */
	public static CompletableFuture<WorldSnapshot> snapshot() {
		return CompletableFuture.supplyAsync(() -> model.snapshot(), WORLD);
	}

	public static CompletableFuture<String> saveNow() {
		return CompletableFuture.supplyAsync(() -> {
			if (lobby == null) return "Not attached to a lobby yet (enter the Crystal Hollows first).";
			savedVersion = -1;
			saveIfDirty("manual");
			return "Saved lobby " + lobby + " to " + fileFor(lobby);
		}, WORLD);
	}

	public static CompletableFuture<String> describeLobby() {
		return CompletableFuture.supplyAsync(() -> (lobby != null ? lobby : "none") + " - " + lobbyNote, WORLD);
	}

	public static long chunksSeen() { return chunksSeen.get(); }
	public static long blockChanges() { return blockChanges.get(); }
	public static String status() { return status; }
}

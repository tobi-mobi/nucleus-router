package dev.nucleusrouter.platform.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.platform.NucleusRouterClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Loads and saves {@code config/nucleusrouter.json}.
 * A broken file is never fatal: it's renamed to {@code nucleusrouter.json.broken} and defaults are used.
 */
public final class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
	private static volatile NrConfig current = new NrConfig();

	private ConfigManager() {}

	public static NrConfig get() { return current; }

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("nucleusrouter.json");
	}

	public static void load() {
		Path file = path();
		NrConfig loaded = null;
		if (Files.exists(file)) {
			try {
				loaded = GSON.fromJson(Files.readString(file), NrConfig.class);
			} catch (IOException | JsonParseException e) {
				Path broken = file.resolveSibling("nucleusrouter.json.broken");
				NucleusRouterClient.LOG.error("[Nucleus Router] Config file is unreadable ({}). Moved it to {} and using defaults.", e.getMessage(), broken.getFileName());
				try { Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
			}
		}
		if (loaded == null) loaded = new NrConfig();
		List<String> fixes = loaded.validate();
		for (String fix : fixes) NucleusRouterClient.LOG.warn("[Nucleus Router] Config: {}", fix);
		current = loaded;
		save(); // writes defaults for new fields and applies any fixes
		NucleusRouterClient.LOG.info("[Nucleus Router] Config loaded from {}", file);
	}

	public static void save() {
		NrConfig cfg = current;
		for (String fix : cfg.validate()) NucleusRouterClient.LOG.warn("[Nucleus Router] Config: {}", fix);
		Path file = path();
		Path tmp = file.resolveSibling("nucleusrouter.json.tmp");
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(tmp, GSON.toJson(cfg));
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			NucleusRouterClient.LOG.error("[Nucleus Router] Could not save config to {}: {}", file, e.getMessage());
		}
	}
}

package dev.nucleusrouter.platform.structure;

import com.google.gson.Gson;
import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.world.WorldSnapshot;
import dev.nucleusrouter.game.BarrierBoxes;
import dev.nucleusrouter.game.CrystalSignature;
import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.game.StructureMap;
import dev.nucleusrouter.game.StructureTemplate;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import dev.nucleusrouter.platform.world.WorldCapture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Finds structures from their crystal boxes (user idea, 2026-11-25). Every 10 s the remembered terrain of
 * the whole Hollows is searched for barrier boxes (background thread). Each box is compared with what was
 * <b>learned</b> in earlier lobbies (box size + the blocks around it); a match places the structure and,
 * through the learned box-to-NPC offset, its NPC, without visiting it. Structures don't rotate, so the
 * offset is the same in every lobby.
 * <p>
 * Learning happens by itself: the first time a structure is found the normal way (NPC name tag, or the
 * crystal for the Queen's den and Khazad-dum), the nearest barrier box and the offset are saved to
 * {@code nucleusrouter/learned/<structure>.json.gz}. If a later NPC sighting disagrees with the learned
 * offset by more than 3 blocks, it is learned again. King Yolkar has no crystal of his own, so his offset is
 * learned from the Queen's den crystal box ([VERIFY] that it is constant).
 */
public final class CrystalScanner {
	private static final int EVERY_TICKS = 200;
	/** A structure's own crystal box is looked for within this distance of its NPC. */
	private static final double LEARN_SEARCH_DISTANCE = 96;
	/** A learned offset that misses a seen NPC by more than this is learned again. */
	private static final double RELEARN_DISTANCE = 3;
	private static final Gson GSON = new Gson();

	private record Saved(String structure, int[] boxSize, double[] anchorOffset, String reference, String lobby,
			int[] templateAnchor, int radius, List<String> palette, String data) {}

	/** Something to learn: the structure, its anchor (null for crystal-marked ones), and a known box if any. */
	private record Candidate(Structure structure, Vec3 anchor, int[][] knownBox, boolean relearn) {}

	private record Found(Structure structure, BarrierBoxes.Box box) {}

	private record Result(List<Found> found, List<CrystalSignature> learned, int boxes) {}

	private static final List<CrystalSignature> learned = new CopyOnWriteArrayList<>();
	private static boolean loaded, running;
	private static int ticks;

	private CrystalScanner() {}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(c -> { if (++ticks % EVERY_TICKS == 0) scan(); });
		java.util.concurrent.CompletableFuture.runAsync(CrystalScanner::bootstrapFromSaves);
	}

	/**
	 * Learns structures that aren't learned yet from saved lobbies (newest first) that have what's needed:
	 * the crystal box for the Queen's den / Khazad-dum, all 4 keepers for the Mines, an NPC for the others,
	 * and the terrain file. Background thread; each lobby file is read at most once.
	 */
	private static void bootstrapFromSaves() {
		if (!ConfigManager.get().structures.learnFromCrystals) return;
		Path dir = FabricLoader.getInstance().getGameDir().resolve("nucleusrouter").resolve("lobbies");
		if (!Files.isDirectory(dir)) return;
		List<Path> files;
		try (Stream<Path> s = Files.list(dir)) {
			files = s.filter(p -> p.toString().endsWith(".structures.json"))
					.sorted(java.util.Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed()).toList();
		} catch (IOException e) {
			return;
		}
		NrConfig cfg = ConfigManager.get();
		for (Path f : files) {
			StructureMap map = KeeperLayouts.readMap(f);
			if (map == null) continue;
			List<Candidate> cands = new ArrayList<>();
			for (Structure s : Structure.values()) {
				if (signature(s) != null) continue;
				StructureMap.Location l = map.locate(s);
				if (s.markCrystal) {
					int[][] box = map.crystalBox(s);
					if (box != null) cands.add(new Candidate(s, null, box, false));
				} else if (l != null && l.source() == StructureMap.Source.NPC && l.uncertainty() == 0
						// Older saves could hold label-only positions (~2 blocks off); only learn from positions on the NPC's body
						&& (s.expectedAnchors > 1 || map.npcs(s).stream().anyMatch(n -> n.onBody))) {
					if (s == Structure.KING_YOLKAR) {
						int[][] queen = map.crystalBox(Structure.GOBLIN_QUEENS_DEN);
						if (queen != null) cands.add(new Candidate(s, l.pos(), queen, false));
					} else {
						cands.add(new Candidate(s, l.pos(), null, false));
					}
				}
			}
			if (cands.isEmpty()) continue;
			String name = f.getFileName().toString().replace(".structures.json", "");
			Path terrain = f.resolveSibling(name + ".nrw");
			if (!Files.exists(terrain)) continue;
			try (java.io.InputStream in = Files.newInputStream(terrain)) {
				WorldSnapshot snap = dev.nucleusrouter.core.world.WorldStore.read(in, dev.nucleusrouter.platform.world.BlockRules.classifier()).model().snapshot();
				Result r = work(snap, cfg.hollows, cfg.structures.crystalBoxMinBarriers, List.of(), List.of(), cands, name);
				for (CrystalSignature sig : r.learned()) {
					save(sig);
					NucleusRouterClient.LOG.info("[Nucleus Router] Learned {}'s crystal from saved lobby {} (box size {}, offset {})",
							sig.structure(), name, Arrays.toString(sig.boxSize()), Arrays.toString(sig.anchorOffset()));
				}
			} catch (IOException | RuntimeException e) {
				NucleusRouterClient.LOG.warn("[Nucleus Router] Could not learn from {}: {}", terrain.getFileName(), e.toString());
			}
		}
	}

	public static Path dir() {
		return FabricLoader.getInstance().getGameDir().resolve("nucleusrouter").resolve("learned");
	}

	public static List<CrystalSignature> learned() {
		if (!loaded) load();
		return List.copyOf(learned);
	}

	private static CrystalSignature signature(Structure s) {
		for (CrystalSignature sig : learned()) if (sig.structure().equals(s.key)) return sig;
		return null;
	}

	// ------------------------------------------------------------------ scan (client thread -> worker -> client)

	private static void scan() {
		if (running || !ConfigManager.get().structures.learnFromCrystals || !LocationTracker.state().inHollows()) return;
		String lobby = StructureTracker.lobby();
		if (lobby == null) return;
		StructureMap map = StructureTracker.map();
		List<CrystalSignature> sigs = learned();

		// Boxes already assigned to a structure, and what to learn this round
		List<int[][]> known = new ArrayList<>();
		for (Structure s : Structure.values()) { int[][] b = map.crystalBox(s); if (b != null) known.add(b); }
		List<Candidate> candidates = new ArrayList<>();
		for (Structure s : Structure.values()) {
			CrystalSignature sig = signature(s);
			StructureMap.Location l = map.locate(s);
			if (s.markCrystal) {
				int[][] box = map.crystalBox(s);
				if (sig == null && box != null) candidates.add(new Candidate(s, null, box, false));
				continue;
			}
			boolean layoutCentre = s == Structure.MINES_OF_DIVAN && sig == null && l != null && l.source() == StructureMap.Source.LEARNED;
			if (l == null || (l.source() != StructureMap.Source.NPC && !layoutCentre) || l.uncertainty() > 0) continue;
			if (s.expectedAnchors > 1 && map.npcs(s).size() < s.expectedAnchors && !layoutCentre) continue; // all 4 keepers (or the layout) for a stable centre
			boolean mismatch = false;
			if (sig != null) {
				Vec3 derived = derivedFor(s, sig, map);
				mismatch = derived != null && derived.sub(l.pos()).length() > RELEARN_DISTANCE;
				if (!mismatch) continue;
			}
			if (s == Structure.KING_YOLKAR) {
				int[][] queen = map.crystalBox(Structure.GOBLIN_QUEENS_DEN);
				if (queen == null) continue;
				candidates.add(new Candidate(s, l.pos(), queen, mismatch));
			} else {
				candidates.add(new Candidate(s, l.pos(), null, mismatch));
			}
		}

		NrConfig cfg = ConfigManager.get();
		int minBarriers = cfg.structures.crystalBoxMinBarriers;
		NrConfig.Hollows h = cfg.hollows;
		running = true;
		WorldCapture.snapshot().thenApplyAsync(snap -> work(snap, h, minBarriers, known, sigs, candidates, lobby))
				.whenComplete((res, err) -> Minecraft.getInstance().execute(() -> {
					running = false;
					if (err != null) { NucleusRouterClient.LOG.warn("[Nucleus Router] Crystal scan failed: {}", err.toString()); return; }
					if (lobby.equals(StructureTracker.lobby())) apply(res, lobby);
				}));
	}

	private static Result work(WorldSnapshot snap, NrConfig.Hollows h, int minBarriers, List<int[][]> known,
			List<CrystalSignature> sigs, List<Candidate> candidates, String lobby) {
		List<int[]> barriers = snap.findBlocks("minecraft:barrier", h.minX, h.minY, h.minZ, h.maxX, h.maxY, h.maxZ);
		List<BarrierBoxes.Box> boxes = new ArrayList<>();
		for (BarrierBoxes.Box b : BarrierBoxes.group(barriers)) if (b.count() >= minBarriers) boxes.add(b);
		StructureTemplate.BlockLookup world = snap::originalBlock;

		List<Found> found = new ArrayList<>();
		for (BarrierBoxes.Box b : boxes) {
			if (isKnown(b, known)) continue;
			CrystalSignature.Identified id = CrystalSignature.identify(b, sigs, world);
			if (id != null) found.add(new Found(id.signature().structureEnum(), b));
		}

		List<CrystalSignature> learnedNow = new ArrayList<>();
		for (Candidate c : candidates) {
			int[] min, size;
			if (c.knownBox() != null) {
				min = c.knownBox()[0];
				size = new int[]{c.knownBox()[1][0] - min[0] + 1, c.knownBox()[1][1] - min[1] + 1, c.knownBox()[1][2] - min[2] + 1};
			} else {
				BarrierBoxes.Box nearest = null;
				double best = LEARN_SEARCH_DISTANCE;
				for (BarrierBoxes.Box b : boxes) {
					double d = new Vec3(b.centreX(), b.centreY(), b.centreZ()).sub(c.anchor()).length();
					if (d < best) { best = d; nearest = b; }
				}
				if (nearest == null) continue;
				min = nearest.min();
				size = CrystalSignature.sizeOf(nearest);
			}
			boolean reference = c.structure() == Structure.KING_YOLKAR;
			StructureTemplate around = reference ? null
					: StructureTemplate.capture(c.structure().key, min[0], min[1], min[2], CrystalSignature.SURROUNDINGS_RADIUS, world);
			double[] offset = c.anchor() == null ? null
					: new double[]{c.anchor().x() - min[0], c.anchor().y() - min[1], c.anchor().z() - min[2]};
			learnedNow.add(new CrystalSignature(c.structure().key, size, around, offset, reference ? Structure.GOBLIN_QUEENS_DEN.key : null, lobby));
		}
		return new Result(found, learnedNow, boxes.size());
	}

	private static boolean isKnown(BarrierBoxes.Box b, List<int[][]> known) {
		for (int[][] k : known) if (Arrays.equals(k[0], b.min()) && Arrays.equals(k[1], b.max())) return true;
		return false;
	}

	/** Where the learned offset puts {@code s} in this lobby, or null if its box isn't known here. */
	private static Vec3 derivedFor(Structure s, CrystalSignature sig, StructureMap map) {
		Structure boxOf = sig.reference() != null ? Structure.byKey(sig.reference()) : s;
		int[][] box = boxOf == null ? null : map.crystalBox(boxOf);
		return box == null ? null : sig.anchorFrom(box[0]);
	}

	private static void apply(Result r, String lobby) {
		StructureMap map = StructureTracker.map();
		boolean notices = ConfigManager.get().general.chatNotices;
		for (Found f : r.found()) {
			Structure s = f.structure();
			boolean had = StructureMap.exact(map.locate(s));
			map.setCrystal(s, f.box().min(), f.box().max());
			CrystalSignature sig = signature(s);
			if (sig != null && sig.anchorOffset() != null && !s.markCrystal) map.setDerived(s, sig.anchorFrom(f.box().min()));
			NucleusRouterClient.LOG.info("[Nucleus Router] Crystal scan: recognised {} at box {}..{}", s.key, Arrays.toString(f.box().min()), Arrays.toString(f.box().max()));
			if (!had && notices) {
				Vec3 p = map.locate(s).pos();
				NucleusRouterClient.showLocal(Component.literal("Found " + s.displayName + " from its crystal at " + StructureTracker.fmt(p.x(), p.y(), p.z())).withStyle(ChatFormatting.GREEN));
			}
		}
		// Structures measured from another structure's box (King Yolkar from the Queen's den)
		for (CrystalSignature sig : learned()) {
			Structure s = sig.structureEnum();
			if (s == null || sig.reference() == null || map.locate(s) != null && map.locate(s).source() == StructureMap.Source.NPC) continue;
			Vec3 d = derivedFor(s, sig, map);
			if (d == null) continue;
			boolean had = StructureMap.exact(map.locate(s));
			map.setDerived(s, d);
			if (!had && notices)
				NucleusRouterClient.showLocal(Component.literal("Found " + s.displayName + " from the Queen's den crystal at " + StructureTracker.fmt(d.x(), d.y(), d.z())).withStyle(ChatFormatting.GREEN));
		}
		for (CrystalSignature sig : r.learned()) {
			Structure s = sig.structureEnum();
			boolean relearn = signature(s) != null;
			save(sig);
			NucleusRouterClient.LOG.info("[Nucleus Router] Crystal scan: {} {} (box size {}, offset {})", relearn ? "re-learned" : "learned", sig.structure(),
					Arrays.toString(sig.boxSize()), Arrays.toString(sig.anchorOffset()));
			if (notices) NucleusRouterClient.showLocal(Component.literal((relearn ? "Re-learned " : "Learned ") + s.displayName
					+ "'s crystal. From now on it is found from the crystal alone in every lobby.").withStyle(ChatFormatting.AQUA));
		}
	}

	// ------------------------------------------------------------------ files

	private static synchronized void load() {
		loaded = true;
		learned.clear();
		Path dir = dir();
		if (!Files.isDirectory(dir)) return;
		try (Stream<Path> files = Files.list(dir)) {
			for (Path f : files.filter(p -> p.toString().endsWith(".json.gz")).toList()) {
				try (Reader r = new InputStreamReader(new GZIPInputStream(Files.newInputStream(f)), StandardCharsets.UTF_8)) {
					Saved sv = GSON.fromJson(r, Saved.class);
					StructureTemplate t = null;
					if (sv.data() != null) {
						int size = 2 * sv.radius() + 1;
						t = new StructureTemplate(sv.structure(), sv.templateAnchor()[0], sv.templateAnchor()[1], sv.templateAnchor()[2], sv.radius(),
								sv.palette(), StructureTemplate.decodeData(sv.data(), size * size * size));
					}
					if (Structure.byKey(sv.structure()) != null)
						learned.add(new CrystalSignature(sv.structure(), sv.boxSize(), t, sv.anchorOffset(), sv.reference(), sv.lobby()));
				} catch (IOException | RuntimeException e) {
					NucleusRouterClient.LOG.warn("[Nucleus Router] Could not read learned crystal {}: {}", f.getFileName(), e.toString());
				}
			}
		} catch (IOException e) {
			NucleusRouterClient.LOG.warn("[Nucleus Router] Could not list {}: {}", dir, e.toString());
		}
	}

	private static void save(CrystalSignature sig) {
		learned.removeIf(x -> x.structure().equals(sig.structure()));
		learned.add(sig);
		StructureTemplate t = sig.surroundings();
		Saved sv = new Saved(sig.structure(), sig.boxSize(), sig.anchorOffset(), sig.reference(), sig.lobby(),
				t == null ? null : new int[]{t.anchorX, t.anchorY, t.anchorZ}, t == null ? 0 : t.radius,
				t == null ? null : t.palette, t == null ? null : t.encodeData());
		Path file = dir().resolve(sig.structure() + ".json.gz");
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
				GSON.toJson(sv, w);
			}
		} catch (IOException e) {
			NucleusRouterClient.LOG.warn("[Nucleus Router] Could not save {}: {}", file, e.toString());
		}
	}

	/** {@code /nr structures} summary line. */
	public static String summary() {
		List<String> names = new ArrayList<>();
		for (CrystalSignature s : learned()) { Structure st = s.structureEnum(); if (st != null) names.add(st.key); }
		return names.isEmpty() ? "none learned yet (find structures normally once)" : String.join(", ", names);
	}
}

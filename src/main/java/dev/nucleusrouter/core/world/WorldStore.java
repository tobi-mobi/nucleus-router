package dev.nucleusrouter.core.world;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Saves and loads a lobby's world model as a small gzip file (SPEC §3.1: per-lobby persistence).
 * Format version 1:
 * <pre>
 * int magic 'NRW1', int version,
 * UTF serverId, long clockTicks, long savedAtMillis,
 * int nameCount, UTF[nameCount] block names,
 * int sectionCount, then per section:
 *   long key, int paletteSize, int[paletteSize-1] global ids (slot 0 is implicit), byte[4096] orig, byte[4096] cur
 * </pre>
 */
public final class WorldStore {
	private static final int MAGIC = 0x4E525731; // "NRW1"
	private static final int VERSION = 1;

	/** Facts about when the file was written, used to tell an old lobby from a new one on the same server. */
	public record Meta(String serverId, long clockTicks, long savedAtMillis) {}

	public record Loaded(Meta meta, WorldModel model) {}

	private WorldStore() {}

	public static void write(WorldSnapshot snap, Meta meta, OutputStream raw) throws IOException {
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(raw), 1 << 16))) {
			out.writeInt(MAGIC);
			out.writeInt(VERSION);
			out.writeUTF(meta.serverId());
			out.writeLong(meta.clockTicks());
			out.writeLong(meta.savedAtMillis());
			out.writeInt(snap.names().size());
			for (String n : snap.names()) out.writeUTF(n);
			out.writeInt(snap.sections().size());
			for (Map.Entry<Long, Section> e : snap.sections().entrySet()) {
				Section s = e.getValue();
				out.writeLong(e.getKey());
				out.writeInt(s.paletteSize);
				for (int i = 1; i < s.paletteSize; i++) out.writeInt(s.palette[i]);
				out.write(s.orig);
				out.write(s.cur);
			}
		}
	}

	public static Loaded read(InputStream raw, BlockClassifier classifier) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw), 1 << 16))) {
			if (in.readInt() != MAGIC) throw new IOException("not a Nucleus Router world file");
			int version = in.readInt();
			if (version != VERSION) throw new IOException("unsupported world file version " + version);
			Meta meta = new Meta(in.readUTF(), in.readLong(), in.readLong());

			WorldModel model = new WorldModel(classifier);
			int nameCount = in.readInt();
			int[] remap = new int[nameCount];
			for (int i = 0; i < nameCount; i++) remap[i] = model.blockId(in.readUTF());

			int sectionCount = in.readInt();
			for (int n = 0; n < sectionCount; n++) {
				long key = in.readLong();
				int paletteSize = in.readInt();
				if (paletteSize < 1 || paletteSize > Section.MAX_PALETTE) throw new IOException("bad palette size " + paletteSize);
				Section s = new Section();
				s.palette = new int[Math.max(8, paletteSize)];
				s.palette[0] = -1;
				for (int i = 1; i < paletteSize; i++) {
					int g = in.readInt();
					if (g < 0 || g >= nameCount) throw new IOException("bad block id " + g);
					s.palette[i] = remap[g];
				}
				s.paletteSize = paletteSize;
				in.readFully(s.orig);
				in.readFully(s.cur);
				model.putSection(key, s);
			}
			return new Loaded(meta, model);
		}
	}
}

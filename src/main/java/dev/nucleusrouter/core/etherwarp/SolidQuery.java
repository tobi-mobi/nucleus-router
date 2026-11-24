package dev.nucleusrouter.core.etherwarp;

/**
 * "Does this block stop the etherwarp ray / can it be stood on?" Every block counts as a full cube
 * (SPEC §2.3), so a yes/no per position is all the ray needs.
 *
 * Typical sources: {@code snapshot::plannedSolid} for planning (respawning blocks stay solid even
 * when mined), {@code snapshot::currentlySolid} for predicting a warp right now.
 */
@FunctionalInterface
public interface SolidQuery {
	boolean solid(int x, int y, int z);
}

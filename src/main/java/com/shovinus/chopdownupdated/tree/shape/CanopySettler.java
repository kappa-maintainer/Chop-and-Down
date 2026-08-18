package com.shovinus.chopdownupdated.tree.shape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * Settles a fallen canopy by collapsing it onto the trunk.
 *
 * The canopy keeps its ninety-degree rotation horizontally, but the thin shell
 * of a jungle or oak canopy does not pile when each leaf keeps its own unique
 * rotated column: every leaf ends up alone in its (x, z) column, one block
 * above the support, and the whole canopy reads as a flat single layer
 * instead of a pile. Real leaves collapse toward the trunk that carried them
 * and stack up, so that is what this settler does.
 *
 * Leaves are grouped by their perpendicular axis (the axis the rotation leaves
 * untouched: x when falling along z, z when falling along x). Every leaf in
 * one such group stood in the same vertical slab of the tree, so after the
 * rotation they all lie along the same line parallel to the fallen trunk.
 * Their fall-axis positions are snapped to the nearest trunk station, so
 * leaves that were spread along the trunk collapse onto it and stack
 * vertically above the support, forming a mound that is taller where the
 * canopy was thicker and flat at the edges.
 *
 * Terrain and trunk support are only ever read through the probes, so the
 * settle runs unchanged in the offline shape tests.
 */
public final class CanopySettler {

	public static final class Decision {
		private final ShapePos source;
		private final ShapePos target;
		private final boolean dropped;

		private Decision(ShapePos source, ShapePos target, boolean dropped) {
			this.source = source;
			this.target = target;
			this.dropped = dropped;
		}

		public ShapePos source() {
			return source;
		}

		/* Settled landing cell, stacked above the trunk station. */
		public ShapePos target() {
			return target;
		}

		/*
		 * True when the settled cell lands on a placed trunk cell, or when the
		 * column has no support at all. The trunk is planned first and owns that
		 * space, so the canopy drops as an item there instead of overwriting it.
		 */
		public boolean dropped() {
			return dropped;
		}
	}

	private CanopySettler() {
	}

	/*
	 * sources and rotated are parallel arrays: rotated[i] is the ninety-degree
	 * rotation of sources[i]. fallAxisIsX is true when the trunk fell along x,
	 * false when along z. The perpendicular axis is the other one.
	 *
	 * Leaves are grouped by their perpendicular axis coordinate. Within each
	 * group, the fall-axis positions are snapped to the nearest value in
	 * snapStations (the trunk's fall-axis positions), so leaves that were spread
	 * along the trunk collapse onto it. Each snapped (perp, fall) column then
	 * stacks tightly from the support face.
	 */
	public static List<Decision> settle(List<ShapePos> sources, List<ShapePos> rotated,
			boolean fallAxisIsX, List<Integer> snapStations,
			TrunkSolver.SupportProbe probe, TrunkPlanner.BlockedProbe blocked) {
		int n = sources.size();
		List<Integer> sortedStations = new ArrayList<>(snapStations);
		Collections.sort(sortedStations);

		// Group by perpendicular axis, then by snapped fall-axis station.
		Map<Long, List<Integer>> columns = new HashMap<>();
		for (int i = 0; i < n; i++) {
			ShapePos r = rotated.get(i);
			int perp = fallAxisIsX ? r.z() : r.x();
			int fall = fallAxisIsX ? r.x() : r.z();
			int snapped = nearestStation(sortedStations, fall);
			long key = ((long) perp << 32) ^ (snapped & 0xffffffffL);
			columns.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
		}

		List<Decision> decisions = new ArrayList<>(n);
		for (List<Integer> column : columns.values()) {
			int i0 = column.get(0);
			ShapePos r0 = rotated.get(i0);
			int perp = fallAxisIsX ? r0.z() : r0.x();
			int fall = fallAxisIsX ? r0.x() : r0.z();
			int snapped = nearestStation(sortedStations, fall);
			int x = fallAxisIsX ? snapped : perp;
			int z = fallAxisIsX ? perp : snapped;
			int support = probe.supportHeightAt(x, z);
			// Stack from the bottom up: lowest rotated leaf first so the pile
			// deforms rather than staying rigid.
			column.sort(Comparator.comparingInt(i -> rotated.get(i).y()));
			int stack = 0;
			for (int i : column) {
				ShapePos source = sources.get(i);
				ShapePos target = new ShapePos(x, support + 1 + stack, z);
				decisions.add(new Decision(source, target, blocked.isBlocked(target)));
				stack++;
			}
		}
		return decisions;
	}

	private static int nearestStation(List<Integer> sorted, int value) {
		if (sorted.isEmpty()) {
			return value;
		}
		int idx = Collections.binarySearch(sorted, value);
		if (idx >= 0) {
			return sorted.get(idx);
		}
		int insertion = -idx - 1;
		if (insertion == 0) {
			return sorted.get(0);
		}
		if (insertion >= sorted.size()) {
			return sorted.get(sorted.size() - 1);
		}
		int below = sorted.get(insertion - 1);
		int above = sorted.get(insertion);
		return (value - below) <= (above - value) ? below : above;
	}
}

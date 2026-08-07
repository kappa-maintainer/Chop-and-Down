package com.shovinus.chopdownupdated.tree.shape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * Turns a solved trunk attitude into one decision per log: place it as part of
 * the rigid beam, drop it at the point the beam actually leans against, or let
 * it go as severed material past a break.
 *
 * Obstacles are only ever read through the BlockedProbe, so the whole decision
 * pass runs unchanged in the offline shape tests.
 */
public final class TrunkPlanner {

	/*
	 * Fraction of a cross section that has to be driven into an obstacle before the
	 * whole cross section counts as blocked. A single fence post must not break a
	 * redwood, but a hillside or a wall face does.
	 */
	public static final double SNAP_BLOCKED_FRACTION = 0.25;

	/* Whether a planned target cell cannot hold a trunk log. */
	public interface BlockedProbe {
		boolean isBlocked(ShapePos target);
	}

	public enum Placement {
		/* Part of the surviving rigid beam. */
		PLACE,
		/*
		 * Never got past an obstacle, so it comes down at the contact point instead of
		 * being placed on the far side of it.
		 */
		DROP_AS_ITEM,
		/* Past a break: no longer held by the beam. */
		SEVERED
	}

	public static final class Decision {
		private final TrunkShape.Cell cell;
		private final ShapePos target;
		private final Placement placement;
		private final ShapePos dropAt;

		private Decision(TrunkShape.Cell cell, ShapePos target, Placement placement,
				ShapePos dropAt) {
			this.cell = cell;
			this.target = target;
			this.placement = placement;
			this.dropAt = dropAt;
		}

		public TrunkShape.Cell cell() {
			return cell;
		}

		public ShapePos source() {
			return cell.source();
		}

		/* Where the rigid beam would carry this log. */
		public ShapePos target() {
			return target;
		}

		public Placement placement() {
			return placement;
		}

		/* Contact point for DROP_AS_ITEM, null otherwise. */
		public ShapePos dropAt() {
			return dropAt;
		}
	}

	public static final class Plan {
		private final TrunkSolver.Solution solution;
		private final List<Decision> decisions;
		private final int snapStep;
		private final int blockedSnap;
		private final int placed;
		private final int items;
		private final int severed;
		private final int fibres;
		private final double severedPitch;
		private final int severedDatum;
		private final ShapePos duplicateTarget;

		private Plan(TrunkSolver.Solution solution, List<Decision> decisions, int snapStep,
				int blockedSnap, int placed, int items, int severed, int fibres,
				double severedPitch, int severedDatum, ShapePos duplicateTarget) {
			this.solution = solution;
			this.decisions = Collections.unmodifiableList(decisions);
			this.snapStep = snapStep;
			this.blockedSnap = blockedSnap;
			this.placed = placed;
			this.items = items;
			this.severed = severed;
			this.fibres = fibres;
			this.severedPitch = severedPitch;
			this.severedDatum = severedDatum;
			this.duplicateTarget = duplicateTarget;
		}

		public TrunkSolver.Solution solution() {
			return solution;
		}

		public List<Decision> decisions() {
			return decisions;
		}

		/* Step the trunk breaks at, or -1 when it stays in one piece. */
		public int snapStep() {
			return snapStep;
		}

		public int blockedSnap() {
			return blockedSnap;
		}

		public int placed() {
			return placed;
		}

		public int items() {
			return items;
		}

		public int severed() {
			return severed;
		}

		public int fibres() {
			return fibres;
		}

		/* Attitude of the severed segment, or the main beam's when nothing broke. */
		public double severedPitch() {
			return severedPitch;
		}

		public int severedDatum() {
			return severedDatum;
		}

		/*
		 * Two logs mapped onto the same cell. The rotation is a lattice permutation so
		 * this must never happen; it is reported instead of silently dropping a log.
		 */
		public ShapePos duplicateTarget() {
			return duplicateTarget;
		}

		public boolean hasDuplicateTarget() {
			return duplicateTarget != null;
		}
	}

	private TrunkPlanner() {
	}

	public static Plan plan(TrunkSolver.Solution solution, BlockedProbe probe) {
		TrunkShape shape = solution.shape();
		int maxStep = shape.maxStep();

		// One blocked test per log, shared by the census and the fibre walk below.
		Set<ShapePos> blocked = new HashSet<>();
		int[] blockedPerStep = new int[maxStep + 1];
		for (TrunkShape.Cell cell : shape.cells()) {
			if (probe.isBlocked(solution.target(cell))) {
				blocked.add(cell.source());
				blockedPerStep[cell.step()]++;
			}
		}

		// Cross section census: once a large enough part of one cross section is
		// driven into an obstacle, the trunk breaks there rather than continuing
		// beyond it.
		int blockedSnap = -1;
		for (int k = 0; k <= maxStep; k++) {
			int count = solution.count(k);
			if (count == 0) {
				continue;
			}
			int threshold = Math.max(1, (int) Math.ceil(count * SNAP_BLOCKED_FRACTION));
			if (blockedPerStep[k] >= threshold) {
				blockedSnap = k;
				break;
			}
		}

		// The trunk breaks at whichever failure comes first along the beam.
		int snapStep = -1;
		int[] candidates = { solution.snapAtStep(), solution.overhangSnap(), blockedSnap };
		for (int candidate : candidates) {
			if (candidate >= 0 && (snapStep < 0 || candidate < snapStep)) {
				snapStep = candidate;
			}
		}

		// The severed segment (steps >= snapStep) is re-attituded as a free beam on
		// the terrain below the break, so it lands as one rigid body instead of each
		// log falling loose and scattering. It keeps the original cross section and
		// columns; only the centre line's height and lean are recomputed.
		double severedPitch = 0.0;
		int severedDatum = solution.datum();
		if (snapStep >= 0) {
			severedPitch = solveSegmentPitch(solution, snapStep);
			severedDatum = solution.required(snapStep)
					- (int) Math.round(severedPitch * snapStep);
		}

		// Occlusion along the beam. A fibre that runs into something stops there, so
		// no section is placed on the far side of an obstacle it never passed
		// through, and everything past the stop comes down where the fibre actually
		// ended.
		Map<Long, List<TrunkShape.Cell>> fibres = new HashMap<>();
		for (TrunkShape.Cell cell : shape.cells()) {
			fibres.computeIfAbsent(cell.fibreKey(), key -> new ArrayList<>()).add(cell);
		}
		Set<ShapePos> occluded = new HashSet<>();
		Map<ShapePos, ShapePos> contactCell = new HashMap<>();
		for (List<TrunkShape.Cell> fibre : fibres.values()) {
			fibre.sort(Comparator.comparingInt(TrunkShape.Cell::step));
			ShapePos lastFree = null;
			boolean stopped = false;
			for (TrunkShape.Cell cell : fibre) {
				ShapePos target = solution.target(cell);
				if (!stopped && blocked.contains(cell.source())) {
					stopped = true;
				}
				if (stopped) {
					occluded.add(cell.source());
					contactCell.put(cell.source(), lastFree != null ? lastFree : target);
				} else {
					lastFree = target;
				}
			}
		}

		List<Decision> decisions = new ArrayList<>(shape.cells().size());
		Map<ShapePos, ShapePos> seen = new LinkedHashMap<>();
		ShapePos duplicate = null;
		int placed = 0;
		int items = 0;
		int severed = 0;
		for (TrunkShape.Cell cell : shape.cells()) {
			ShapePos target = solution.target(cell);
			ShapePos previous;
			Placement placement;
			ShapePos dropAt = null;
			if (occluded.contains(cell.source())) {
				// Occlusion wins over the break. A section that never got past the
				// obstacle cannot fall from a beam position on its far side.
				placement = Placement.DROP_AS_ITEM;
				dropAt = contactCell.get(cell.source());
				items++;
				// Occluded cells are not placed, so they are not checked for duplicates.
				previous = null;
			} else if (snapStep >= 0 && cell.step() >= snapStep) {
				placement = Placement.SEVERED;
				// Re-attitude: the severed segment keeps its cross section but lands on
				// the terrain below the break as one rigid body.
				target = segmentTarget(cell, severedDatum, severedPitch);
				previous = seen.put(target, cell.source());
				severed++;
			} else {
				placement = Placement.PLACE;
				previous = seen.put(target, cell.source());
				placed++;
			}
			if (previous != null && duplicate == null) {
				duplicate = target;
			}
			decisions.add(new Decision(cell, target, placement, dropAt));
		}
		return new Plan(solution, decisions, snapStep, blockedSnap, placed, items, severed,
				fibres.size(), severedPitch, severedDatum, duplicate);
	}

	/*
	 * Steepest lean the severed segment can take while resting on the terrain past
	 * the break, without digging into it. Mirrors the solver's planted pitch but is
	 * solved over the sub range from the break onward.
	 */
	private static double solveSegmentPitch(TrunkSolver.Solution solution, int pivot) {
		TrunkShape shape = solution.shape();
		double best = 0.0;
		boolean any = false;
		for (int k = pivot + 1; k <= shape.maxStep(); k++) {
			if (solution.count(k) == 0) {
				continue;
			}
			double req = (double) (solution.required(k) - solution.required(pivot)) / (k - pivot);
			if (!any || req > best) {
				best = req;
				any = true;
			}
		}
		if (!any) {
			return 0.0;
		}
		return Math.max(TrunkSolver.PITCH_MIN, Math.min(TrunkSolver.PITCH_MAX, best));
	}

	private static ShapePos segmentTarget(TrunkShape.Cell cell, int datum, double pitch) {
		return new ShapePos(cell.columnX(),
				datum + (int) Math.round(pitch * cell.step()) + cell.verticalOffset(),
				cell.columnZ());
	}
}

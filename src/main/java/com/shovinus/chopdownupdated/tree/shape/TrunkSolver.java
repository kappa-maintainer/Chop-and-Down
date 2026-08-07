package com.shovinus.chopdownupdated.tree.shape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Resting attitude of one fallen trunk, solved once so no per block step can
 * drift away from the plan.
 *
 * The trunk stays a single straight rigid beam: every log keeps its place in
 * the rotated lattice and only two numbers are solved for the whole body, the
 * height of the beam at the root (datum) and its lean along the fall direction
 * (pitch). Terrain is only ever read through the SupportProbe, so this solver
 * runs unchanged in the offline shape tests.
 */
public final class TrunkSolver {

	/* Upward lean limit. Past this the beam would read as standing back up. */
	public static final double PITCH_MAX = 1.0;
	/* Downward limit while the foot is still planted on the stump side. */
	public static final double PITCH_MIN = -1.0;
	/* Downward limit once the beam has tipped over a fulcrum and is falling. */
	public static final double TIP_PITCH_MIN = -2.0;
	/*
	 * How far the butt may rise off its own support while the beam tips over a
	 * fulcrum. The trunk was just cut and is still sitting against its own stump,
	 * so a small shift reads as the trunk rotating off the cut. Deliberately NOT
	 * scaled with trunk thickness: a thick trunk floating its butt eight blocks up
	 * looks just as wrong as a thin one. Past this the trunk breaks at the fulcrum.
	 */
	public static final int ROOT_LIFT_ALLOWANCE = 2;
	/* Each round moves the fulcrum strictly outward, so this is a safety bound. */
	public static final int SETTLE_ROUNDS = 8;
	/*
	 * Minimum overhang clearance before the cantilever past the outermost contact
	 * is treated as a break. Thin trunks would otherwise snap on every small bump,
	 * so the real limit is max(this, trunk thickness).
	 */
	public static final int MIN_OVERHANG_CLEARANCE = 4;
	/*
	 * Fraction of a cross section's columns whose terrain may be ignored when the
	 * resting height of that cross section is solved.
	 *
	 * The beam is a single rigid body and its lean is the largest slope over every
	 * step, so without this one narrow thing under the path - a leftover branch, a
	 * fence post, a single pillar - is enough to stand a fifteen block wide trunk
	 * up at the pitch cap. Kept in step with TrunkPlanner.SNAP_BLOCKED_FRACTION:
	 * terrain that carries less than this share of a cross section does not decide
	 * the attitude, and the logs that then cannot go where the beam wants are
	 * handled as blocked cells instead.
	 */
	public static final double OBSTRUCTION_FRACTION = 0.25;
	/*
	 * How deep a log may be driven into terrain that is allowed to give way. A
	 * falling redwood is heavy enough to bed itself into soil, but it must not
	 * bury itself, so this stays small.
	 */
	public static final int EMBED_DEPTH_MAX = 2;
	/* Total terrain blocks one tree may displace. */
	public static final int EMBED_BUDGET = 256;
	/*
	 * Only trunks of roughly redwood size bed themselves in. A normal tree just
	 * lies on the surface, which is also what players expect to see.
	 */
	public static final int EMBED_MIN_TRUNK_CELLS = 500;

	/*
	 * Whether the terrain block at this position may be displaced so a trunk log
	 * can bed into it. Tree supplies the real whitelist (soil and plain stone,
	 * never ore, bedrock, containers or built blocks); the offline tests supply
	 * their own. Defaults to refusing everything, which turns the whole embedding
	 * refinement into a no-op.
	 */
	public interface EmbedProbe {
		boolean canEmbed(int x, int y, int z);

		EmbedProbe NONE = (x, y, z) -> false;
	}

	/* Terrain support height at an absolute column, as seen by the falling trunk. */
	public interface SupportProbe {
		int supportHeightAt(int x, int z);
	}

	public static final class Solution {
		private final TrunkShape shape;
		/* required[k] = lowest absolute height the beam may sit at, at step k. */
		private final int[] required;
		private final int[] count;
		private final int rootSupport;
		private final double centreOfMass;
		/* Columns whose terrain was ignored as a narrow obstruction. */
		private final int clampedColumns;
		private int datum;
		private double pitch;
		private int outermostContact = -1;
		private int rootLift;
		private int tipRounds;
		/* Set when tipping would levitate the butt: the trunk breaks here instead. */
		private int snapAtStep = -1;
		/* First overhanging step floating further up than the trunk is thick. */
		private int overhangSnap = -1;
		/* Terrain cells the chosen attitude displaces, in placement order. */
		private List<ShapePos> embedded = Collections.emptyList();
		/* Cross section columns whose keel rests directly on its support. */
		private int contact;

		private Solution(TrunkShape shape, int[] required, int[] count, int rootSupport,
				double centreOfMass, int clampedColumns) {
			this.shape = shape;
			this.required = required;
			this.count = count;
			this.rootSupport = rootSupport;
			this.centreOfMass = centreOfMass;
			this.clampedColumns = clampedColumns;
		}

		public TrunkShape shape() {
			return shape;
		}

		public int datum() {
			return datum;
		}

		public double pitch() {
			return pitch;
		}

		public int rootSupport() {
			return rootSupport;
		}

		public double centreOfMass() {
			return centreOfMass;
		}

		/*
		 * How many cross section columns had their terrain ignored as a narrow
		 * obstruction. Non zero means something under the path was too narrow to
		 * decide the attitude of the whole trunk.
		 */
		public int clampedColumns() {
			return clampedColumns;
		}

		public int outermostContact() {
			return outermostContact;
		}

		public int rootLift() {
			return rootLift;
		}

		public int tipRounds() {
			return tipRounds;
		}

		public int snapAtStep() {
			return snapAtStep;
		}

		public int overhangSnap() {
			return overhangSnap;
		}

		/*
		 * Terrain blocks this attitude displaces. Empty unless an EmbedProbe allowed
		 * the trunk to bed in.
		 */
		public List<ShapePos> embedded() {
			return embedded;
		}

		/*
		 * How many cross section columns actually carry the trunk. This is the
		 * contact area the attitude search maximises.
		 */
		public int contact() {
			return contact;
		}

		public int required(int step) {
			return required[step];
		}

		public int count(int step) {
			return count[step];
		}

		public int targetY(TrunkShape.Cell cell) {
			return shape.targetY(cell, datum, pitch);
		}

		public ShapePos target(TrunkShape.Cell cell) {
			return shape.target(cell, datum, pitch);
		}

		/*
		 * How far the bottom of step k floats above the support under it. Zero means
		 * resting, negative means driven into terrain.
		 */
		public int slackAt(int step) {
			return datum + (int) Math.round(pitch * step) - required[step];
		}

		/*
		 * Steepest lean the rigid beam can take while resting on step pivot. Only
		 * terrain beyond the fulcrum can hold it up, so only those steps constrain it.
		 */
		private double solvePitch(int pivot, double minPitch) {
			double best = 0.0;
			boolean any = false;
			for (int k = pivot + 1; k <= shape.maxStep(); k++) {
				if (count[k] == 0) {
					continue;
				}
				double req = (double) (required[k] - required[pivot]) / (k - pivot);
				if (!any || req > best) {
					best = req;
					any = true;
				}
			}
			if (!any) {
				return 0.0;
			}
			return Math.max(minPitch, Math.min(PITCH_MAX, best));
		}

		/* Outermost step whose bottom actually rests on its support. */
		private int outermostContactStep() {
			int outer = -1;
			for (int k = 0; k <= shape.maxStep(); k++) {
				if (count[k] == 0) {
					continue;
				}
				if (datum + (int) Math.round(pitch * k) == required[k]) {
					outer = k;
				}
			}
			return outer;
		}
	}

	private TrunkSolver() {
	}

	public static Solution solve(TrunkShape shape, SupportProbe probe) {
		return solve(shape, probe, EmbedProbe.NONE);
	}

	public static Solution solve(TrunkShape shape, SupportProbe probe, EmbedProbe embedProbe) {
		int maxStep = shape.maxStep();
		int[] required = new int[maxStep + 1];
		int[] count = new int[maxStep + 1];
		for (int k = 0; k <= maxStep; k++) {
			required[k] = Integer.MIN_VALUE;
		}
		long moment = 0;
		long mass = 0;
		for (TrunkShape.Cell cell : shape.cells()) {
			count[cell.step()]++;
			moment += cell.step();
			mass++;
		}

		// Per step tables. A cross section is judged one rotation axis column at a
		// time: each column reports the height its own lowest log needs, and the beam
		// only has to clear the columns that actually carry it.
		//
		// The highest few columns are clamped down to the rest of the section, so a
		// narrow obstruction cannot decide the attitude of the whole trunk. Material
		// that then cannot go where the beam wants is handled by the planner as
		// blocked cells: dropped at the contact point, or a break once enough of one
		// cross section is obstructed.
		Map<Long, Integer> supportCache = new HashMap<>();
		int clampedColumns = 0;
		// The lowest log of every rotation axis column, with the support the solve
		// actually respected under it. These are the only logs that can carry the
		// trunk or bed into the ground, and reusing the clamped value keeps the
		// contact search consistent with the attitude solve: a column ignored as a
		// narrow obstruction is ignored here too.
		List<TrunkShape.Cell> keels = new ArrayList<>();
		List<Integer> keelSupport = new ArrayList<>();
		for (int k = 0; k <= maxStep; k++) {
			TrunkShape.Section section = shape.section(k);
			if (section.isEmpty()) {
				continue;
			}
			Map<Integer, TrunkShape.Cell> keelByColumn = new LinkedHashMap<>();
			for (TrunkShape.Cell cell : section.cells()) {
				TrunkShape.Cell current = keelByColumn.get(cell.perpendicular());
				if (current == null || cell.verticalOffset() < current.verticalOffset()) {
					keelByColumn.put(cell.perpendicular(), cell);
				}
			}
			int n = keelByColumn.size();
			TrunkShape.Cell[] columnKeel = new TrunkShape.Cell[n];
			int[] support = new int[n];
			int index = 0;
			for (TrunkShape.Cell cell : keelByColumn.values()) {
				columnKeel[index] = cell;
				support[index] = supportAt(probe, supportCache, cell.columnX(), cell.columnZ());
				index++;
			}
			int[] sorted = support.clone();
			Arrays.sort(sorted);
			int ignorable = (int) Math.floor(n * OBSTRUCTION_FRACTION);
			int robust = sorted[n - 1 - ignorable];
			int lower = Integer.MIN_VALUE;
			for (int j = 0; j < n; j++) {
				int clamped = Math.min(support[j], robust);
				if (clamped != support[j]) {
					clampedColumns++;
				}
				lower = Math.max(lower, clamped + 1 - columnKeel[j].verticalOffset());
				keels.add(columnKeel[j]);
				keelSupport.add(clamped);
			}
			required[k] = lower;
		}

		int rootSupport = 0;
		for (TrunkShape.Cell cell : shape.section(0).cells()) {
			rootSupport = Math.max(rootSupport,
					supportAt(probe, supportCache, cell.columnX(), cell.columnZ()) + 1);
		}
		double centreOfMass = mass == 0 ? 0.0 : (double) moment / mass;
		Solution solution = new Solution(shape, required, count, rootSupport, centreOfMass,
				clampedColumns);

		// Step 1: foot planted on the stump side. The beam leans just enough that no
		// cross section digs into terrain further along the path. A hill, a wall or
		// the far bank of a ravine raises the far end; a drop lets it lean down;
		// equal supports at both ends leave it horizontal.
		solution.pitch = solution.solvePitch(0, PITCH_MIN);
		solution.datum = required[0];

		// Step 2: moment settle. A rigid beam whose centre of mass hangs past its
		// outermost contact is not in equilibrium: it tips about that contact. The
		// butt may only lift a little, because the trunk was just cut and is still
		// sitting against its own stump. Anything more would be a levitating butt,
		// so the trunk breaks at the fulcrum instead.
		for (int round = 0; round < SETTLE_ROUNDS; round++) {
			int outer = solution.outermostContactStep();
			solution.outermostContact = outer;
			if (outer < 0 || solution.centreOfMass <= outer) {
				break;
			}
			double tipPitch = solution.solvePitch(outer, TIP_PITCH_MIN);
			if (tipPitch >= solution.pitch) {
				// Already as low as this fulcrum allows.
				break;
			}
			int tipDatum = required[outer] - (int) Math.round(tipPitch * outer);
			if (tipDatum - required[0] > ROOT_LIFT_ALLOWANCE) {
				solution.snapAtStep = outer;
				break;
			}
			solution.pitch = tipPitch;
			solution.datum = tipDatum;
			solution.tipRounds = round + 1;
		}
		solution.rootLift = solution.datum - required[0];

		// Step 3: overhang break. Past the outermost contact the rigid beam is a
		// cantilever, and a straight beam cannot come back down to follow terrain
		// that drops away. A short overhang reads as a trunk tip poking over an
		// edge; once the underside floats further up than the trunk is thick it
		// reads as a log hanging in mid air, which is where a real trunk would have
		// snapped.
		//
		// Only for a beam that is level or falling away. A beam with a positive
		// pitch was pushed up by something ahead of it, so it is leaning against
		// that thing: the gap underneath is the natural gap under a leaning plank
		// and grows one block per step no matter what, which would otherwise break
		// every leaning trunk a fixed number of steps from its root. The material
		// that cannot get past the obstacle is handled by occlusion instead.
		int clearanceLimit = Math.max(MIN_OVERHANG_CLEARANCE, shape.thickness());
		if (solution.pitch <= 0 && solution.outermostContact >= 0) {
			for (int k = solution.outermostContact + 1; k <= maxStep; k++) {
				if (count[k] == 0) {
					continue;
				}
				if (solution.slackAt(k) > clearanceLimit) {
					solution.overhangSnap = k;
					break;
				}
			}
		}
		// Step 4: bed the trunk in. A straight rigid beam whose root cross section is
		// the widest has its height pinned by that root, so everything narrower
		// further along floats: the taper of a redwood turns into a trunk lying on
		// air. A trunk that heavy does not rest on top of soil, it beds into it, so
		// the attitude is re-searched with a bounded amount of terrain allowed to
		// give way and the largest carrying contact area wins.
		refineForContact(solution, embedProbe, keels, keelSupport);

		return solution;
	}

	/*
	 * Re-searches datum and pitch to maximise the contact area the trunk actually
	 * rests on, allowing at most EMBED_DEPTH_MAX blocks of whitelisted terrain to
	 * give way and at most EMBED_BUDGET blocks in total.
	 *
	 * Deliberately conservative:
	 *
	 *   - only for trunks of roughly redwood size, a normal tree keeps lying on the
	 *     surface
	 *   - only when the baseline is a settled, level or downhill beam, so a trunk
	 *     that is leaning on something or already breaking is left alone
	 *   - the root is never lifted above where the planted solve put it
	 *   - a candidate has to beat the baseline on contact area to be taken at all
	 *
	 * With the default EmbedProbe.NONE nothing is embeddable, so no candidate can
	 * improve on the baseline and this is a no-op.
	 */
	private static void refineForContact(Solution solution, EmbedProbe embedProbe,
			List<TrunkShape.Cell> keels, List<Integer> keelSupport) {
		TrunkShape shape = solution.shape;
		int maxStep = shape.maxStep();
		if (shape.cells().size() < EMBED_MIN_TRUNK_CELLS) {
			return;
		}
		if (solution.snapAtStep >= 0 || solution.overhangSnap >= 0 || solution.pitch > 0
				|| solution.rootLift != 0) {
			return;
		}

		int baselineDatum = solution.datum;
		double baselinePitch = solution.pitch;
		int[] baseline = score(keels, keelSupport, baselineDatum, baselinePitch, embedProbe, null);
		if (baseline == null) {
			// Rounding of the centre line already puts a log marginally into terrain
			// that may not give way. There is nothing to compare against, so the
			// planted attitude stands.
			return;
		}
		int bestContact = baseline[0];
		int bestEmbedded = baseline[1];
		int bestFloat = baseline[2];
		int bestDatum = baselineDatum;
		double bestPitch = baselinePitch;
		boolean improved = false;

		// Bounded, rational pitch candidates: level, whatever the planted solve
		// found, and one step of drop per block of trunk thickness over the whole
		// length. Rational slopes keep the rounding of the centre line stable.
		List<Double> pitches = new ArrayList<>();
		pitches.add(0.0);
		if (baselinePitch != 0.0) {
			pitches.add(baselinePitch);
		}
		if (maxStep > 0) {
			int drops = Math.min(shape.thickness() + EMBED_DEPTH_MAX, maxStep);
			for (int n = 1; n <= drops; n++) {
				double pitch = -(double) n / maxStep;
				if (pitch >= PITCH_MIN) {
					pitches.add(pitch);
				}
			}
		}

		for (double pitch : pitches) {
			// The root stays where the planted solve put it: bedding in may lower the
			// beam into the ground, never lift its butt into the air.
			for (int drop = 0; drop <= EMBED_DEPTH_MAX; drop++) {
				int datum = baselineDatum - drop;
				int[] candidate = score(keels, keelSupport, datum, pitch, embedProbe, null);
				if (candidate == null) {
					continue;
				}
				// Contact area first, then the least terrain displaced, then the
				// smallest remaining gap, then the gentlest attitude.
				if (candidate[0] > bestContact
						|| (candidate[0] == bestContact && candidate[1] < bestEmbedded)
						|| (candidate[0] == bestContact && candidate[1] == bestEmbedded
								&& candidate[2] < bestFloat)
						|| (candidate[0] == bestContact && candidate[1] == bestEmbedded
								&& candidate[2] == bestFloat
								&& Math.abs(pitch) < Math.abs(bestPitch))) {
					bestContact = candidate[0];
					bestEmbedded = candidate[1];
					bestFloat = candidate[2];
					bestDatum = datum;
					bestPitch = pitch;
					improved = true;
				}
			}
		}

		if (!improved || bestContact <= baseline[0]) {
			return;
		}
		List<ShapePos> embedded = new ArrayList<>();
		if (score(keels, keelSupport, bestDatum, bestPitch, embedProbe, embedded) == null) {
			return;
		}
		solution.datum = bestDatum;
		solution.pitch = bestPitch;
		solution.embedded = Collections.unmodifiableList(embedded);
		solution.contact = bestContact;
		// Negative once the trunk beds in: the butt settled into the ground rather
		// than lifting off it.
		solution.rootLift = bestDatum - solution.required[0];
		// outermostContact is deliberately left at its planted value. It marks the
		// fulcrum the moment settle used, and a bedded beam never satisfies the exact
		// resting equality, so recomputing it here would just erase it.
	}

	/*
	 * { carrying columns, terrain blocks displaced, largest remaining gap } for one
	 * attitude, or null when the attitude is not allowed: a log would go deeper
	 * than EMBED_DEPTH_MAX, into terrain that may not give way, or past
	 * EMBED_BUDGET. When sink is non null the displaced cells are collected into
	 * it.
	 */
	private static int[] score(List<TrunkShape.Cell> keels, List<Integer> keelSupport, int datum,
			double pitch, EmbedProbe embedProbe, List<ShapePos> sink) {
		int contact = 0;
		int embedded = 0;
		int largestGap = 0;
		for (int i = 0; i < keels.size(); i++) {
			TrunkShape.Cell cell = keels.get(i);
			int support = keelSupport.get(i);
			int y = datum + (int) Math.round(pitch * cell.step()) + cell.verticalOffset();
			if (y <= support) {
				// Bedded in: every block from here up to the surface has to give way.
				int depth = support - y + 1;
				if (depth > EMBED_DEPTH_MAX) {
					return null;
				}
				for (int by = y; by <= support; by++) {
					if (!embedProbe.canEmbed(cell.columnX(), by, cell.columnZ())) {
						return null;
					}
					embedded++;
					if (embedded > EMBED_BUDGET) {
						return null;
					}
					if (sink != null) {
						sink.add(new ShapePos(cell.columnX(), by, cell.columnZ()));
					}
				}
				contact++;
			} else if (y == support + 1) {
				contact++;
			} else {
				largestGap = Math.max(largestGap, y - support - 1);
			}
		}
		return new int[] { contact, embedded, largestGap };
	}

	/* One probe call per column, shared across the whole solve. */
	private static int supportAt(SupportProbe probe, Map<Long, Integer> cache, int x, int z) {
		long key = ((long) x << 32) ^ (z & 0xffffffffL);
		Integer cached = cache.get(key);
		if (cached != null) {
			return cached;
		}
		int value = probe.supportHeightAt(x, z);
		cache.put(key, value);
		return value;
	}
}

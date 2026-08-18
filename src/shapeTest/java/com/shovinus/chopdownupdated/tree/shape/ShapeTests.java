package com.shovinus.chopdownupdated.tree.shape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntBinaryOperator;

/*
 * Offline tests for the rigid trunk shape model.
 *
 * These exercise the real production classes (TrunkShape, TrunkSolver,
 * TrunkPlanner), not a copy of them, which is only possible because the shape
 * package never touches BlockPos or World. Terrain is injected through the same
 * SupportProbe and BlockedProbe interfaces Tree uses at runtime.
 *
 * Deliberately not JUnit and deliberately not wired into build/check: run with
 *
 *   ./gradlew shapeTest
 *
 * Many expectations below are characterization values: they record what the
 * current solver does so a later change to the pose solver or the break rules
 * shows up as an explicit, reviewable diff instead of silently changing how
 * trees land.
 */
public final class ShapeTests {

	// ---------------------------------------------------------------- harness

	private static int checks;
	private static int failures;
	private static String group = "";

	private static void group(String name) {
		group = name;
		System.out.println();
		System.out.println("=== " + name + " ===");
	}

	private static void check(boolean ok, String what) {
		checks++;
		if (!ok) {
			failures++;
			System.out.println("  FAIL [" + group + "] " + what);
		}
	}

	private static void eqi(int actual, int expected, String what) {
		check(actual == expected, what + ": expected " + expected + " but was " + actual);
	}

	private static void eqd(double actual, double expected, String what) {
		check(Math.abs(actual - expected) < 1e-3,
				what + ": expected " + expected + " but was " + actual);
	}

	// ---------------------------------------------------------------- terrain

	/* Test stand-in for the world: a support height plus solidity per cell. */
	private interface Terrain {
		int support(int x, int z);

		boolean solid(int x, int y, int z);

		default boolean water(int x, int y, int z) {
			return false;
		}

		/*
		 * Whether this cell may be displaced so a trunk log can bed into it. Defaults
		 * to refusing, so every fixture that does not opt in keeps the trunk on the
		 * surface and the embedding refinement is a no-op for it.
		 */
		default boolean embeddable(int x, int y, int z) {
			return false;
		}
	}

	/* Dry land: everything at or below the support height is solid. */
	private static Terrain dry(IntBinaryOperator support) {
		return new Terrain() {
			@Override
			public int support(int x, int z) {
				return support.applyAsInt(x, z);
			}

			@Override
			public boolean solid(int x, int y, int z) {
				return y <= support.applyAsInt(x, z);
			}
		};
	}

	/* A wall standing on flat ground at y=63. */
	private static Terrain wall(int x0, int x1, int top) {
		return new Terrain() {
			@Override
			public int support(int x, int z) {
				return x >= x0 && x <= x1 ? top : 63;
			}

			@Override
			public boolean solid(int x, int y, int z) {
				if (y <= 63) {
					return true;
				}
				return x >= x0 && x <= x1 && y <= top;
			}
		};
	}

	// ---------------------------------------------------------------- trunks

	private static List<ShapePos> pillar(int cx, int cz, int baseY, int height) {
		List<ShapePos> out = new ArrayList<>();
		for (int i = 1; i <= height; i++) {
			out.add(new ShapePos(cx, baseY + i, cz));
		}
		return out;
	}

	private static List<ShapePos> box(int cx, int cz, int baseY, int height, int size) {
		List<ShapePos> out = new ArrayList<>();
		for (int y = 1; y <= height; y++) {
			for (int dx = 0; dx < size; dx++) {
				for (int dz = 0; dz < size; dz++) {
					out.add(new ShapePos(cx + dx, baseY + y, cz + dz));
				}
			}
		}
		return out;
	}

	/* Straight cylinder: the same disc at every height. */
	private static List<ShapePos> cylinder(int cx, int cz, int baseY, int height, int radius) {
		List<ShapePos> out = new ArrayList<>();
		for (int y = 1; y <= height; y++) {
			addDisc(out, cx, cz, baseY + y, radius);
		}
		return out;
	}

	/*
	 * Redwood-like trunk: wide at the root, narrowing further up. This is the
	 * profile the actual-section model exists for.
	 */
	private static List<ShapePos> taperedRedwood(int cx, int cz, int baseY) {
		List<ShapePos> out = new ArrayList<>();
		for (int y = 1; y <= 30; y++) {
			int radius;
			if (y <= 15) {
				radius = 7;
			} else if (y <= 22) {
				radius = 5;
			} else if (y <= 27) {
				radius = 3;
			} else {
				radius = 1;
			}
			addDisc(out, cx, cz, baseY + y, radius);
		}
		return out;
	}

	private static void addDisc(List<ShapePos> out, int cx, int cz, int y, int radius) {
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (dx * dx + dz * dz <= radius * radius) {
					out.add(new ShapePos(cx + dx, y, cz + dz));
				}
			}
		}
	}

	// ---------------------------------------------------------------- runner

	private static final class Result {
		TrunkShape shape;
		TrunkSolver.Solution solution;
		TrunkPlanner.Plan plan;
		Terrain terrain;
		int fallX;
		int fallZ;
		int baseY;
		List<ShapePos> sources;
	}

	private static Result run(List<ShapePos> trunk, int fallX, int fallZ, int baseY,
			Terrain terrain) {
		Result r = new Result();
		r.terrain = terrain;
		r.fallX = fallX;
		r.fallZ = fallZ;
		r.baseY = baseY;
		r.sources = trunk;
		r.shape = TrunkShape.build(trunk, fallX, fallZ, baseY);
		r.solution = TrunkSolver.solve(r.shape, terrain::support, terrain::embeddable);
		// The tree's own standing logs move away, so they never block it: this is
		// what Tree.isTrunkTargetBlocked does with movingTreeBlocks. Cells the trunk
		// beds into are carved before it is placed, so they do not block it either.
		Set<ShapePos> own = new HashSet<>(trunk);
		Set<ShapePos> carved = new HashSet<>(r.solution.embedded());
		r.plan = TrunkPlanner.plan(r.solution, target -> {
			if (target.y() <= 0 || target.y() > 255) {
				return true;
			}
			if (own.contains(target) || carved.contains(target)) {
				return false;
			}
			return terrain.solid(target.x(), target.y(), target.z());
		});
		return r;
	}

	/* Models Tree.ManuallyDrop walking a loose block down onto its rest cell. */
	private static int descendTo(Terrain t, int x, int y, int z) {
		int cur = y;
		while (cur > 1) {
			int below = cur - 1;
			if (t.water(x, below, z) || t.solid(x, below, z)) {
				break;
			}
			cur = below;
		}
		return cur;
	}

	// ------------------------------------------------------------ invariants

	/*
	 * The rotation must stay a lattice permutation of the surviving beam: one cell
	 * per log, the rotation axis untouched, and one single rigid body with one
	 * single straight centre line.
	 */
	private static void assertInvariants(String name, Result r) {
		TrunkShape shape = r.shape;
		int sign = r.fallX != 0 ? r.fallX : r.fallZ;
		// Cells the trunk is allowed to bed into are carved before placement, so a
		// log sitting in one of them is intended, not buried.
		Set<ShapePos> carved = new HashSet<>(r.solution.embedded());
		Set<ShapePos> seen = new HashSet<>();
		Set<Integer> rigid = new TreeSet<>();
		Set<Integer> affine = new TreeSet<>();
		int drift = 0;
		int duplicates = 0;
		int buried = 0;
		for (TrunkPlanner.Decision d : r.plan.decisions()) {
			if (d.placement() != TrunkPlanner.Placement.PLACE) {
				continue;
			}
			ShapePos from = d.source();
			ShapePos to = d.target();
			if (!seen.add(to)) {
				duplicates++;
			}
			int fromPerp = r.fallX != 0 ? from.z() : from.x();
			int toPerp = r.fallX != 0 ? to.z() : to.x();
			if (fromPerp != toPerp) {
				drift++;
			}
			int fallAxis = r.fallX != 0 ? from.x() - shape.anchorX() : from.z() - shape.anchorZ();
			rigid.add(d.cell().verticalOffset() + sign * fallAxis);
			int toFall = r.fallX != 0 ? (to.x() - shape.anchorX()) * r.fallX
					: (to.z() - shape.anchorZ()) * r.fallZ;
			affine.add(toFall - (from.y() - r.baseY));
			if (r.terrain.solid(to.x(), to.y(), to.z()) && !carved.contains(to)) {
				buried++;
			}
		}
		eqi(duplicates, 0, name + " duplicate targets");
		eqi(drift, 0, name + " rotation axis drift");
		eqi(rigid.size(), 1, name + " single rigid body (constants " + rigid + ")");
		eqi(affine.size(), 1, name + " single straight centre line (constants " + affine + ")");
		eqi(buried, 0, name + " surviving logs buried in terrain");
		// Every log gets exactly one decision.
		eqi(r.plan.decisions().size(), r.sources.size(), name + " one decision per log");
		eqi(r.plan.placed() + r.plan.items() + r.plan.severed(), r.sources.size(),
				name + " decisions add up");
		check(!r.plan.hasDuplicateTarget(), name + " planner reported no duplicate target");
	}

	/* Absolute Y the root cross section ends up at. */
	private static int footY(Result r) {
		int y = Integer.MAX_VALUE;
		for (TrunkPlanner.Decision d : r.plan.decisions()) {
			if (d.cell().step() == 0) {
				y = Math.min(y, d.target().y());
			}
		}
		return y;
	}

	private static void report(String name, Result r) {
		System.out.printf("  %-34s pitch=%+.3f datum=%d snap=%-3d place=%-5d item=%-5d sever=%-5d%n",
				name, r.solution.pitch(), r.solution.datum(), r.plan.snapStep(), r.plan.placed(),
				r.plan.items(), r.plan.severed());
	}

	// ----------------------------------------------------------------- tests

	/*
	 * The actual-section model: sections come from the logs that are really there,
	 * so a tapered trunk keeps its real profile and a missing layer stays visible.
	 */
	private static void testActualSections() {
		group("actual cross sections");
		int base = 64;

		List<ShapePos> tapered = taperedRedwood(64, 0, base);
		TrunkShape shape = TrunkShape.build(tapered, 1, 0, base);
		eqi(shape.maxStep(), 29, "tapered redwood step count");
		eqi(shape.emptySteps(), 0, "tapered redwood has no gaps");
		int total = 0;
		for (int k = 0; k <= shape.maxStep(); k++) {
			total += shape.section(k).count();
		}
		eqi(total, tapered.size(), "sections hold every log");
		eqi(shape.cells().size(), tapered.size(), "one cell per log");

		// The root is wide and the tip is narrow: nothing is padded out to a
		// configured radius, which is the whole point of using real sections.
		eqi(shape.section(0).thickness(), 15, "root section thickness");
		eqi(shape.section(29).thickness(), 3, "tip section thickness");
		check(shape.section(0).count() > shape.section(29).count(),
				"root section holds more logs than the tip");
		Set<Integer> distinctThickness = new TreeSet<>();
		for (int k = 0; k <= shape.maxStep(); k++) {
			distinctThickness.add(shape.section(k).thickness());
		}
		eqi(distinctThickness.size(), 4, "tapered profile keeps its distinct widths");
		System.out.println("  tapered section thicknesses " + distinctThickness);

		// A hole inside a section stays a hole.
		List<ShapePos> holed = new ArrayList<>(cylinder(64, 0, base, 6, 2));
		ShapePos hole = new ShapePos(64, base + 3, 0);
		check(holed.remove(hole), "fixture removed the centre log");
		TrunkShape holedShape = TrunkShape.build(holed, 1, 0, base);
		eqi(holedShape.cells().size(), holed.size(), "hole is not filled in");
		eqi(holedShape.section(2).count(), holedShape.section(1).count() - 1,
				"holed section is one log short");

		// A missing layer stays visible instead of being papered over.
		List<ShapePos> gapped = new ArrayList<>();
		for (ShapePos pos : pillar(64, 0, base, 8)) {
			if (pos.y() != base + 4) {
				gapped.add(pos);
			}
		}
		TrunkShape gappedShape = TrunkShape.build(gapped, 1, 0, base);
		eqi(gappedShape.emptySteps(), 1, "missing layer reported as an empty section");
		eqi(gappedShape.section(3).count(), 0, "the gap section is empty");

		// Rotation is a pure permutation: the fall axis becomes height inside the
		// section, the perpendicular axis is carried over unchanged.
		Result flat = run(tapered, 1, 0, base, dry((x, z) -> 63));
		assertInvariants("tapered flat", flat);
		report("tapered redwood, flat", flat);
	}

	/*
	 * Symptom 3 baseline. A straight rigid beam whose root section is the widest
	 * has its height pinned by that root, so the narrower part further up floats.
	 * These numbers record today's behaviour; the actual-section pose solver is
	 * expected to reduce maxSlack, and this test is where that change shows up.
	 */
	private static void testTaperFloatBaseline() {
		group("taper float baseline (symptom 3)");
		int base = 64;
		Result r = run(taperedRedwood(64, 0, base), 1, 0, base, dry((x, z) -> 63));
		eqd(r.solution.pitch(), 0.0, "tapered trunk on flat ground stays level");
		eqi(r.solution.datum(), 71, "datum pinned by the widest root section");
		eqi(r.solution.rootLift(), 0, "root is not lifted");
		eqi(r.plan.snapStep(), -1, "no break on flat ground");

		int maxSlack = 0;
		int contacts = 0;
		for (int k = 0; k <= r.shape.maxStep(); k++) {
			if (r.solution.count(k) == 0) {
				continue;
			}
			int slack = r.solution.slackAt(k);
			maxSlack = Math.max(maxSlack, slack);
			if (slack == 0) {
				contacts++;
			}
		}
		eqi(maxSlack, 6, "largest float gap under the tapered trunk");
		eqi(contacts, 15, "sections actually resting on the ground");
		System.out.println("  maxSlack=" + maxSlack + " restingSections=" + contacts + " of "
				+ r.shape.sectionCount());
	}

	/* Flat ground must never tilt or break a trunk, in any direction. */
	private static void testFlatNoOp() {
		group("flat ground stays a no-op");
		int base = 64;
		Terrain flat = dry((x, z) -> 63);
		int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

		for (int[] d : dirs) {
			String tag = "1x1 flat " + d[0] + "," + d[1];
			Result r = run(pillar(64, 0, base, 6), d[0], d[1], base, flat);
			assertInvariants(tag, r);
			eqd(r.solution.pitch(), 0.0, tag + " pitch");
			eqi(r.plan.snapStep(), -1, tag + " no break");
			eqi(r.plan.placed(), 6, tag + " placed");
			eqi(footY(r), 64, tag + " foot rests on the ground");
		}
		for (int[] d : dirs) {
			String tag = "redwood flat " + d[0] + "," + d[1];
			Result r = run(cylinder(64, 0, base, 30, 7), d[0], d[1], base, flat);
			assertInvariants(tag, r);
			eqd(r.solution.pitch(), 0.0, tag + " pitch");
			eqi(r.plan.placed(), 4470, tag + " placed");
			eqi(r.plan.severed(), 0, tag + " nothing severed");
		}
		Result box = run(box(64, 0, base, 12, 2), 1, 0, base, flat);
		assertInvariants("2x2 flat", box);
		eqd(box.solution.pitch(), 0.0, "2x2 flat pitch");
		eqi(box.plan.placed(), 48, "2x2 flat placed");
	}

	/* Terrain that should lean the beam without breaking it. */
	private static void testTerrain() {
		group("terrain leans the beam without breaking it");
		int base = 64;

		Result ravine = run(pillar(64, 0, base, 30), 1, 0, base,
				dry((x, z) -> (x >= 75 && x <= 85) ? 40 : 63));
		assertInvariants("1x1 ravine", ravine);
		eqd(ravine.solution.pitch(), 0.0, "reachable far bank keeps the bridge level");
		eqi(ravine.plan.snapStep(), -1, "1x1 ravine no break");
		eqi(ravine.plan.placed(), 30, "1x1 ravine placed");

		Result redwoodRavine = run(cylinder(64, 0, base, 30, 7), 1, 0, base,
				dry((x, z) -> (x >= 75 && x <= 85) ? 40 : 63));
		assertInvariants("redwood ravine", redwoodRavine);
		eqd(redwoodRavine.solution.pitch(), 0.0, "redwood ravine pitch");
		eqi(redwoodRavine.plan.placed(), 4470, "redwood ravine placed");

		Terrain uphill = dry((x, z) -> x <= 72 ? 63 : Math.min(63 + (x - 72), 80));
		Result up = run(pillar(64, 0, base, 24), 1, 0, base, uphill);
		assertInvariants("1x1 uphill", up);
		eqd(up.solution.pitch(), 0.696, "1x1 uphill pitch");
		eqi(up.plan.snapStep(), -1, "1x1 uphill no break");
		eqi(footY(up), 64, "1x1 uphill foot rests on the ground");

		Result redwoodUp = run(cylinder(64, 0, base, 30, 7), 1, 0, base, uphill);
		assertInvariants("redwood uphill", redwoodUp);
		eqd(redwoodUp.solution.pitch(), 1.0, "redwood uphill pitch");
		eqi(redwoodUp.plan.placed(), 4470, "redwood uphill placed");

		// A small rise under the path must not break a redwood.
		Result bump = run(cylinder(64, 0, base, 30, 7), 1, 0, base,
				dry((x, z) -> (x >= 85 && x <= 88) ? 66 : 63));
		assertInvariants("redwood bump", bump);
		eqd(bump.solution.pitch(), 0.231, "redwood bump pitch");
		eqi(bump.plan.snapStep(), -1, "redwood bump no break");
		eqi(footY(bump), 64, "redwood bump foot rests on the ground");
	}

	/* Ground that falls away: the beam tips, or breaks at the fulcrum. */
	private static void testCliffs() {
		group("cliffs and chasms");
		int base = 64;

		Result far = run(pillar(64, 0, base, 24), 1, 0, base, dry((x, z) -> x <= 72 ? 63 : 45));
		assertInvariants("1x1 cliff far", far);
		eqi(far.plan.snapStep(), 7, "cliff far breaks at the fulcrum");
		eqi(far.plan.placed(), 7, "cliff far placed");
		eqi(far.plan.severed(), 17, "cliff far severed");
		eqi(footY(far), 64, "cliff far foot stays planted");
		report("1x1 cliff far", far);

		// Close to the edge the trunk can tip over it instead of breaking.
		Result near = run(pillar(64, 0, base, 24), 1, 0, base, dry((x, z) -> x <= 66 ? 63 : 45));
		assertInvariants("1x1 cliff near", near);
		eqd(near.solution.pitch(), -0.818, "cliff near tips down over the edge");
		eqi(near.plan.snapStep(), -1, "cliff near does not break");
		eqi(near.solution.rootLift(), 1, "cliff near butt lift stays within allowance");
		check(near.solution.rootLift() <= TrunkSolver.ROOT_LIFT_ALLOWANCE,
				"cliff near respects ROOT_LIFT_ALLOWANCE");
		report("1x1 cliff near", near);

		Result chasm = run(pillar(64, 0, base, 30), 1, 0, base, dry((x, z) -> x >= 80 ? 40 : 63));
		assertInvariants("1x1 chasm", chasm);
		eqi(chasm.plan.snapStep(), 14, "chasm breaks");
		eqi(chasm.plan.placed(), 14, "chasm placed");
		eqi(chasm.plan.severed(), 16, "chasm severed");

		Result redwood = run(cylinder(64, 0, base, 30, 7), 1, 0, base,
				dry((x, z) -> x <= 80 ? 63 : 45));
		assertInvariants("redwood cliff", redwood);
		eqi(redwood.plan.snapStep(), 8, "redwood cliff breaks");
		eqi(redwood.plan.placed(), 1192, "redwood cliff placed");
		eqi(redwood.plan.severed(), 3278, "redwood cliff severed");
		eqi(footY(redwood), 64, "redwood cliff foot stays planted");
		report("redwood cliff", redwood);
	}

	/*
	 * Walls. Nothing may be placed inside a wall or carried to its far side, and
	 * the material that cannot get past comes down at the contact point.
	 */
	private static void testWalls() {
		group("walls and leaning");
		int base = 64;

		// Situation 1: a jungle tree felled into a nearby redwood. The surviving
		// beam leans from the root up to the redwood face, everything else drops at
		// that contact point.
		Result jungle = run(pillar(64, 0, base, 20), 1, 0, base, wall(71, 85, 94));
		assertInvariants("jungle into redwood", jungle);
		eqd(jungle.solution.pitch(), 1.0, "jungle leans at the pitch cap");
		eqi(jungle.plan.snapStep(), 6, "jungle stops at the redwood face");
		eqi(jungle.plan.placed(), 6, "jungle placed");
		eqi(jungle.plan.items(), 14, "jungle drops the rest at the contact point");
		eqi(jungle.plan.severed(), 0, "occlusion wins over the break");
		assertNothingPast("jungle into redwood", jungle, 71, 85);
		report("jungle h=20 into redwood", jungle);

		// A propped beam whose tip sticks up past the wall is allowed to stay whole.
		Result propped = run(pillar(64, 0, base, 30), 1, 0, base, wall(80, 82, 76));
		assertInvariants("1x1 propped on wall", propped);
		eqd(propped.solution.pitch(), 0.867, "propped beam rests on the wall top");
		eqi(propped.plan.snapStep(), -1, "propped beam does not break");
		eqi(propped.plan.placed(), 30, "propped beam stays whole");
		assertNotBuried("1x1 propped on wall", propped, 80, 82, 76);
		report("1x1 h=30 propped on wall", propped);

		Result redwood = run(cylinder(64, 0, base, 30, 7), 1, 0, base, wall(84, 86, 76));
		assertInvariants("redwood into wall", redwood);
		eqi(redwood.plan.snapStep(), 13, "redwood breaks at the wall");
		eqi(redwood.plan.placed(), 1936, "redwood placed");
		eqi(redwood.plan.items(), 18, "redwood contact drops");
		eqi(redwood.plan.severed(), 2516, "redwood severed");
		assertNotBuried("redwood into wall", redwood, 84, 86, 76);
		report("redwood h=30 into wall", redwood);

		// A thick wall must not let anything through to its far side.
		Result thick = run(box(64, 0, base, 12, 2), 1, 0, base, wall(70, 78, 76));
		assertInvariants("2x2 into thick wall", thick);
		eqi(thick.plan.snapStep(), 4, "2x2 stops at the thick wall");
		eqi(thick.plan.placed(), 16, "2x2 placed");
		eqi(thick.plan.items(), 32, "2x2 contact drops");
		assertNothingPast("2x2 into thick wall", thick, 70, 78);
		report("2x2 h=12 into thick wall", thick);
	}

	private static void assertNotBuried(String name, Result r, int x0, int x1, int top) {
		int buried = 0;
		for (TrunkPlanner.Decision d : r.plan.decisions()) {
			if (d.placement() != TrunkPlanner.Placement.PLACE) {
				continue;
			}
			ShapePos to = d.target();
			if (to.x() >= x0 && to.x() <= x1 && to.y() <= top) {
				buried++;
			}
		}
		eqi(buried, 0, name + " logs placed inside the wall");
	}

	private static void assertNothingPast(String name, Result r, int x0, int x1) {
		assertNotBuried(name, r, x0, x1, Integer.MAX_VALUE);
		int placedPast = 0;
		int itemsPast = 0;
		for (TrunkPlanner.Decision d : r.plan.decisions()) {
			if (d.placement() == TrunkPlanner.Placement.PLACE && d.target().x() > x1) {
				placedPast++;
			}
			if (d.placement() == TrunkPlanner.Placement.DROP_AS_ITEM && d.dropAt().x() > x1) {
				itemsPast++;
			}
		}
		eqi(placedPast, 0, name + " logs placed past the wall");
		eqi(itemsPast, 0, name + " items dropped past the wall");
	}

	/*
	 * Symptom 1. The beam is one rigid body and its lean is the largest slope over
	 * every step, so terrain has to be judged per cross section column or one
	 * narrow thing under the path stands the whole trunk up at the pitch cap.
	 *
	 * These are the only fixtures whose terrain varies along the rotation axis, so
	 * they are the only ones that exercise the clamp at all.
	 */
	private static void testNarrowObstruction() {
		group("narrow obstruction cannot tilt the whole trunk (symptom 1)");
		int base = 64;

		// A leftover canopy branch left standing well above the fallen beam. The
		// support scan sees it, but it carries one column out of fifteen.
		//
		// Judged per log this would ask for required=97 at step 18, a slope of 1.44
		// from the root, so the trunk would stand up at the +1.0 cap: the reported
		// "45 degrees into the sky on flat ground".
		Terrain branch = new Terrain() {
			@Override
			public int support(int x, int z) {
				return (x == 90 && z == 3) ? 90 : 63;
			}

			@Override
			public boolean solid(int x, int y, int z) {
				if (y <= 63) {
					return true;
				}
				return x == 90 && z == 3 && y >= 80 && y <= 90;
			}
		};
		Result r = run(cylinder(64, 0, base, 30, 7), 1, 0, base, branch);
		assertInvariants("redwood with leftover branch", r);
		eqd(r.solution.pitch(), 0.0, "one narrow column does not tilt the trunk");
		eqi(r.solution.datum(), 71, "trunk still rests on the flat ground");
		eqi(r.plan.snapStep(), -1, "one narrow column does not break the trunk");
		eqi(r.plan.placed(), 4470, "whole trunk still placed");
		eqi(footY(r), 64, "foot rests on the ground");
		check(r.solution.clampedColumns() > 0, "the narrow column was ignored as an obstruction");
		report("redwood + leftover branch", r);

		// Same thing for a thin built pillar: narrow along the rotation axis, so it
		// does not decide the attitude either. The logs that cannot pass it are
		// handled as blocked cells instead.
		Terrain post = new Terrain() {
			@Override
			public int support(int x, int z) {
				return (x >= 84 && x <= 86 && z >= 0 && z <= 1) ? 76 : 63;
			}

			@Override
			public boolean solid(int x, int y, int z) {
				if (y <= 63) {
					return true;
				}
				return x >= 84 && x <= 86 && z >= 0 && z <= 1 && y <= 76;
			}
		};
		Result p = run(cylinder(64, 0, base, 30, 7), 1, 0, base, post);
		assertInvariants("redwood with narrow post", p);
		eqd(p.solution.pitch(), 0.0, "a two column post does not tilt the trunk");
		eqi(p.solution.datum(), 71, "trunk still rests on the flat ground");
		check(p.solution.clampedColumns() > 0, "the post columns were ignored as an obstruction");
		check(p.plan.items() > 0, "logs that cannot pass the post are dropped instead");
		report("redwood + narrow post", p);

		// A wall across the whole path is not narrow: every column reports it, so it
		// is respected and the trunk leans on it. Nothing is clamped.
		Result full = run(cylinder(64, 0, base, 30, 7), 1, 0, base, wall(84, 86, 76));
		eqi(full.solution.clampedColumns(), 0, "a full width wall is never ignored");
		eqd(full.solution.pitch(), 1.0, "a full width wall still lifts the trunk");

		// A thin trunk has a single column, so there is nothing to compare against
		// and every obstacle is respected.
		Result thin = run(pillar(64, 0, base, 30), 1, 0, base, wall(80, 82, 76));
		eqi(thin.solution.clampedColumns(), 0, "a one column trunk never ignores terrain");
		eqd(thin.solution.pitch(), 0.867, "a one column trunk still leans on the wall");
	}

	/*
	 * Symptom 3. A straight rigid beam whose root cross section is the widest has
	 * its height pinned by that root, so a tapered redwood ends up lying on air.
	 * A trunk that heavy beds into soil instead, so the attitude is re-searched
	 * with a bounded amount of terrain allowed to give way and the largest
	 * carrying contact area wins.
	 */
	private static void testEmbedding() {
		group("bedding into soil raises contact area (symptom 3)");
		int base = 64;

		// Flat soil: everything at or below the surface may give way.
		Terrain soil = new Terrain() {
			@Override
			public int support(int x, int z) {
				return 63;
			}

			@Override
			public boolean solid(int x, int y, int z) {
				return y <= 63;
			}

			@Override
			public boolean embeddable(int x, int y, int z) {
				return y <= 63;
			}
		};
		// Same ground, but nothing may be displaced: the reference case.
		Terrain rock = dry((x, z) -> 63);

		Result onRock = run(taperedRedwood(64, 0, base), 1, 0, base, rock);
		Result onSoil = run(taperedRedwood(64, 0, base), 1, 0, base, soil);
		assertInvariants("tapered redwood on soil", onSoil);

		eqi(onRock.solution.embedded().size(), 0, "nothing beds into rock");
		check(onSoil.solution.embedded().size() > 0, "the trunk beds into soil");
		check(onSoil.solution.embedded().size() <= TrunkSolver.EMBED_BUDGET,
				"bedding stays within the budget, was " + onSoil.solution.embedded().size());
		check(onSoil.solution.datum() < onRock.solution.datum(),
				"bedding lowers the trunk: " + onRock.solution.datum() + " -> "
						+ onSoil.solution.datum());
		check(onSoil.solution.contact() > 0, "bedded attitude reports its contact area");
		eqi(onSoil.plan.snapStep(), -1, "bedding does not break the trunk");
		eqi(onSoil.plan.items(), 0, "a bedded keel is placed, not dropped");
		eqi(onSoil.plan.placed(), onSoil.sources.size(), "every log still placed");

		// Float gap must actually shrink: this is the symptom being fixed.
		eqi(maxSlack(onRock), 6, "reference float gap on rock");
		check(maxSlack(onSoil) < maxSlack(onRock), "bedding shrinks the float gap: "
				+ maxSlack(onRock) + " -> " + maxSlack(onSoil));
		// Characterization: records what the contact search settles on, so a later
		// change to the attitude search shows up as a reviewable diff.
		eqi(onSoil.solution.datum(), 69, "bedded datum");
		eqi(maxSlack(onSoil), 3, "bedded float gap");
		eqi(onSoil.solution.embedded().size(), 127, "terrain blocks displaced");
		eqi(onSoil.solution.contact(), 184, "carrying columns after bedding");

		// Depth cap and whitelist are respected for every displaced cell.
		int deepest = 0;
		int offWhitelist = 0;
		for (ShapePos cell : onSoil.solution.embedded()) {
			int depth = soil.support(cell.x(), cell.z()) - cell.y() + 1;
			deepest = Math.max(deepest, depth);
			if (!soil.embeddable(cell.x(), cell.y(), cell.z())) {
				offWhitelist++;
			}
		}
		eqi(offWhitelist, 0, "every displaced cell is on the whitelist");
		check(deepest <= TrunkSolver.EMBED_DEPTH_MAX,
				"deepest bedding " + deepest + " within " + TrunkSolver.EMBED_DEPTH_MAX);
		System.out.printf("  rock: datum=%d maxSlack=%d    soil: datum=%d maxSlack=%d "
				+ "embedded=%d deepest=%d contact=%d%n", onRock.solution.datum(), maxSlack(onRock),
				onSoil.solution.datum(), maxSlack(onSoil), onSoil.solution.embedded().size(),
				deepest, onSoil.solution.contact());

		// Only a shallow skin may give way: bedding has to stop at depth 1.
		Terrain skin = new Terrain() {
			@Override
			public int support(int x, int z) {
				return 63;
			}

			@Override
			public boolean solid(int x, int y, int z) {
				return y <= 63;
			}

			@Override
			public boolean embeddable(int x, int y, int z) {
				return y == 63;
			}
		};
		Result onSkin = run(taperedRedwood(64, 0, base), 1, 0, base, skin);
		assertInvariants("tapered redwood on thin soil", onSkin);
		int skinDeepest = 0;
		for (ShapePos cell : onSkin.solution.embedded()) {
			skinDeepest = Math.max(skinDeepest, skin.support(cell.x(), cell.z()) - cell.y() + 1);
		}
		check(skinDeepest <= 1, "bedding stops at the layer that may give way, was " + skinDeepest);
		eqi(onSkin.solution.datum(), 70, "thin soil only lets the trunk down one block");

		// A normal tree keeps lying on the surface: bedding is for redwood sized
		// trunks only.
		Result small = run(pillar(64, 0, base, 20), 1, 0, base, soil);
		eqi(small.solution.embedded().size(), 0, "a thin tree does not bed in");
		eqi(small.solution.datum(), 64, "a thin tree still rests on the surface");
		Result mid = run(box(64, 0, base, 12, 2), 1, 0, base, soil);
		eqi(mid.solution.embedded().size(), 0, "a 2x2 tree does not bed in");

		// A trunk that is leaning on something is left alone.
		Terrain soilWall = new Terrain() {
			@Override
			public int support(int x, int z) {
				return (x >= 84 && x <= 86) ? 76 : 63;
			}

			@Override
			public boolean solid(int x, int y, int z) {
				if (y <= 63) {
					return true;
				}
				return x >= 84 && x <= 86 && y <= 76;
			}

			@Override
			public boolean embeddable(int x, int y, int z) {
				return y <= 63;
			}
		};
		Result leaning = run(cylinder(64, 0, base, 30, 7), 1, 0, base, soilWall);
		eqi(leaning.solution.embedded().size(), 0, "a leaning trunk does not bed in");
		eqd(leaning.solution.pitch(), 1.0, "a leaning trunk keeps its lean");
	}

	private static int maxSlack(Result r) {
		int worst = 0;
		for (int k = 0; k <= r.shape.maxStep(); k++) {
			if (r.solution.count(k) == 0) {
				continue;
			}
			worst = Math.max(worst, r.solution.slackAt(k));
		}
		return worst;
	}

	/*
	 * Symptom 2. The material past a break used to fall loose, one log at a time,
	 * and scattered. It is now re-attituded as one rigid segment that keeps its
	 * cross section and lands on the terrain below the break.
	 */
	private static void testSeveredSegmentShape() {
		group("severed segment keeps rigid shape (symptom 2)");
		int base = 64;

		Result cliff = run(pillar(64, 0, base, 24), 1, 0, base, dry((x, z) -> x <= 72 ? 63 : 45));
		assertSeveredRigid("1x1 cliff far", cliff);
		check(cliff.plan.severed() == 17, "cliff far severed count");
		check(severedMinClearance(cliff) <= 1, "severed segment rests on terrain, not in mid air");
		report("1x1 cliff far", cliff);

		Result redCliff = run(cylinder(64, 0, base, 30, 7), 1, 0, base,
				dry((x, z) -> x <= 80 ? 63 : 45));
		assertSeveredRigid("redwood cliff", redCliff);
		check(redCliff.plan.severed() == 3278, "redwood cliff severed count");
		check(severedMinClearance(redCliff) <= 1, "redwood severed segment rests on terrain");

		Result wall = run(cylinder(64, 0, base, 30, 7), 1, 0, base, wall(84, 86, 76));
		assertSeveredRigid("redwood wall", wall);
		check(wall.plan.severed() == 2516, "redwood wall severed count");
		// The wall case is different: the lowest fibres hit the wall and drop as
		// items, so the severed segment is the part that cleared the wall top. It
		// keeps its shape and is re-attituded, but its lowest cell is not the
		// section keel.
		check(wall.plan.severedPitch() != wall.solution.pitch(),
				"severed segment re-attituded away from the main lean");
		check(severedReAttituded(wall), "severed targets differ from the beam line");

		Result flat = run(pillar(64, 0, base, 6), 1, 0, base, dry((x, z) -> 63));
		eqi(flat.plan.severed(), 0, "flat ground: nothing severed");
	}

	private static void assertSeveredRigid(String name, Result r) {
		TrunkShape shape = r.shape;
		int sign = r.fallX != 0 ? r.fallX : r.fallZ;
		Set<ShapePos> seen = new HashSet<>();
		Set<Integer> rigid = new TreeSet<>();
		Set<Integer> affine = new TreeSet<>();
		int drift = 0;
		int duplicates = 0;
		int buried = 0;
		for (TrunkPlanner.Decision d : r.plan.decisions()) {
			if (d.placement() != TrunkPlanner.Placement.SEVERED) {
				continue;
			}
			ShapePos from = d.source();
			ShapePos to = d.target();
			if (!seen.add(to)) {
				duplicates++;
			}
			int fromPerp = r.fallX != 0 ? from.z() : from.x();
			int toPerp = r.fallX != 0 ? to.z() : to.x();
			if (fromPerp != toPerp) {
				drift++;
			}
			int fallAxis = r.fallX != 0 ? from.x() - shape.anchorX() : from.z() - shape.anchorZ();
			rigid.add(d.cell().verticalOffset() + sign * fallAxis);
			int toFall = r.fallX != 0 ? (to.x() - shape.anchorX()) * r.fallX
					: (to.z() - shape.anchorZ()) * r.fallZ;
			affine.add(toFall - (from.y() - r.baseY));
			if (r.terrain.solid(to.x(), to.y(), to.z())) {
				buried++;
			}
		}
		eqi(duplicates, 0, name + " severed duplicate targets");
		eqi(drift, 0, name + " severed rotation axis drift");
		eqi(rigid.size(), 1, name + " severed single rigid body");
		eqi(affine.size(), 1, name + " severed single straight centre line");
		eqi(buried, 0, name + " severed logs buried in terrain");
	}

	private static boolean severedReAttituded(Result r) {
		for (TrunkPlanner.Decision d : r.plan.decisions()) {
			if (d.placement() != TrunkPlanner.Placement.SEVERED) {
				continue;
			}
			ShapePos beam = r.solution.target(d.cell());
			if (d.target().y() != beam.y()) {
				return true;
			}
		}
		return false;
	}

	private static int severedMinClearance(Result r) {
		int minClearance = Integer.MAX_VALUE;
		for (TrunkPlanner.Decision d : r.plan.decisions()) {
			if (d.placement() != TrunkPlanner.Placement.SEVERED) {
				continue;
			}
			if (d.cell().verticalOffset() != r.shape.section(d.cell().step()).verticalMin()) {
				continue;
			}
			int clearance = d.target().y() - r.terrain.support(d.target().x(), d.target().z()) - 1;
			minClearance = Math.min(minClearance, clearance);
		}
		return minClearance;
	}

	/* Wood floats: the water surface is a support face, not something to sink through. */
	private static void testWater() {
		group("water");
		int base = 64;
		final int seaFloor = 45;
		final int waterTop = 62;
		final int platformEdge = 71;
		final int beamX = 87;

		Terrain ocean = new Terrain() {
			@Override
			public int support(int x, int z) {
				if (x <= platformEdge || x == beamX) {
					return 63;
				}
				return waterTop;
			}

			@Override
			public boolean solid(int x, int y, int z) {
				if (x <= platformEdge) {
					return y <= 63;
				}
				if (x == beamX && y == 63) {
					return true;
				}
				return y <= seaFloor;
			}

			@Override
			public boolean water(int x, int y, int z) {
				if (x <= platformEdge || (x == beamX && y == 63)) {
					return false;
				}
				return y > seaFloor && y <= waterTop;
			}
		};

		// Situation 2: a redwood felled out over the sea, held up mid span by a
		// one block crossbeam at root level. The trunk must stay continuous.
		Result r = run(cylinder(64, 0, base, 30, 7), 1, 0, base, ocean);
		assertInvariants("redwood over ocean", r);
		eqi(r.plan.snapStep(), -1, "trunk over the sea stays whole");
		eqi(r.plan.severed(), 0, "nothing severed over the sea");
		eqi(r.plan.placed(), 4470, "every log placed");
		eqi(r.solution.outermostContact(), 15, "crossbeam is the outermost contact");
		int submerged = 0;
		Set<Integer> steps = new TreeSet<>();
		for (TrunkPlanner.Decision d : r.plan.decisions()) {
			steps.add(d.cell().step());
			ShapePos to = d.target();
			if (to.x() > platformEdge && to.x() != beamX && to.y() <= waterTop) {
				submerged++;
			}
		}
		eqi(submerged, 0, "no log sinks below the water surface");
		eqi(steps.size(), r.shape.sectionCount(), "every section is present");
		report("redwood over ocean", r);

		// Situation 3: loose material comes to rest on the surface, not the sea bed.
		for (int x : new int[] { 75, 80, 90, 100 }) {
			eqi(descendTo(ocean, x, 80, 0), waterTop + 1,
					"loose block at x=" + x + " floats on the surface");
		}
		eqi(descendTo(ocean, 65, 80, 0), 64, "loose block over land rests on the ground");
	}

	private static void testCanopySettle() {
		group("canopy collapses onto trunk stations");
		TrunkPlanner.BlockedProbe unblocked = t -> false;
		TrunkSolver.SupportProbe flat63 = (x, z) -> 63;

		// Fall along x (fallAxisIsX=true). Trunk stations at x=10,11,12.
		List<Integer> stations = List.of(10, 11, 12);

		// Three leaves at perpendicular z=0, rotated fall-axis x=10,11,12.
		// They snap to three different stations, each a 1-leaf column.
		List<ShapePos> src = List.of(
				new ShapePos(0, 10, 0),
				new ShapePos(0, 11, 0),
				new ShapePos(0, 12, 0));
		List<ShapePos> rot = List.of(
				new ShapePos(10, 70, 0),
				new ShapePos(11, 70, 0),
				new ShapePos(12, 70, 0));
		List<CanopySettler.Decision> sep = CanopySettler.settle(src, rot, true, stations, flat63, unblocked);
		Map<String, TreeSet<Integer>> sepCols = new TreeMap<>();
		for (CanopySettler.Decision d : sep) {
			sepCols.computeIfAbsent(d.target().x() + "," + d.target().z(), k -> new TreeSet<>())
					.add(d.target().y());
		}
		eqi(sepCols.size(), 3, "three leaves at three stations = three columns");
		for (var e : sepCols.values()) {
			eqi(e.first(), 64, "each leaf rests on terrain+1");
		}

		// Two leaves at same perpendicular z=0, rotated fall-axis x=10 and x=10.
		// Both snap to station x=10 (nearest). They stack vertically: 64, 65.
		List<ShapePos> pileSrc = List.of(new ShapePos(0, 10, 0), new ShapePos(0, 11, 0));
		List<ShapePos> pileRot = List.of(new ShapePos(10, 70, 0), new ShapePos(10, 71, 0));
		List<Integer> oneStation = List.of(10);
		List<CanopySettler.Decision> pile = CanopySettler.settle(pileSrc, pileRot, true, oneStation, flat63, unblocked);
		TreeSet<Integer> pileYs = new TreeSet<>();
		for (CanopySettler.Decision d : pile) {
			pileYs.add(d.target().y());
		}
		eqi(pileYs.size(), 2, "two leaves stack at one station");
		eqi(pileYs.first(), 64, "bottom leaf on terrain+1");
		eqi(pileYs.last(), 65, "second leaf stacks above");

		// The canopy rests on the fallen trunk, not terrain under it: support 68
		// means leaves stack from 69.
		TrunkSolver.SupportProbe trunkTop = (x, z) -> 68;
		List<CanopySettler.Decision> onTrunk = CanopySettler.settle(pileSrc, pileRot, true, oneStation, trunkTop, unblocked);
		TreeSet<Integer> onTrunkYs = new TreeSet<>();
		for (CanopySettler.Decision d : onTrunk) {
			onTrunkYs.add(d.target().y());
		}
		eqi(onTrunkYs.first(), 69, "canopy bottom rests on trunk top");
		eqi(onTrunkYs.last(), 70, "second leaf stacks above");

		// Different perpendicular axes are independent columns.
		List<ShapePos> twoPerpSrc = List.of(new ShapePos(0, 10, 0), new ShapePos(0, 10, 1));
		List<ShapePos> twoPerpRot = List.of(new ShapePos(10, 70, 0), new ShapePos(10, 70, 1));
		List<CanopySettler.Decision> twoPerp = CanopySettler.settle(twoPerpSrc, twoPerpRot, true, oneStation, flat63, unblocked);
		Map<Integer, Integer> perpBottoms = new TreeMap<>();
		for (CanopySettler.Decision d : twoPerp) {
			perpBottoms.merge(d.target().z(), d.target().y(), Math::min);
		}
		eqi(perpBottoms.size(), 2, "two perpendicular axes = two columns");
		eqi(perpBottoms.get(0), 64, "z=0 column at 64");
		eqi(perpBottoms.get(1), 64, "z=1 column at 64");

		// A settled cell that lands on a trunk cell is marked dropped.
		TrunkPlanner.BlockedProbe blocked = t -> t.y() == 64;
		List<CanopySettler.Decision> collide = CanopySettler.settle(pileSrc, pileRot, true, oneStation, flat63, blocked);
		Map<Integer, Boolean> droppedByStack = new HashMap<>();
		for (CanopySettler.Decision d : collide) {
			droppedByStack.put(d.target().y(), d.dropped());
		}
		check(droppedByStack.get(64), "bottom leaf colliding with trunk is dropped");
		check(!droppedByStack.get(65), "leaf above the collision is kept");

		// A ball-shaped canopy collapses into a mound: the centre station (most
		// leaves snap there) is tallest, edges are shortest.
		List<ShapePos> sphereSrc = new ArrayList<>();
		List<ShapePos> sphereRot = new ArrayList<>();
		for (int dy = -4; dy <= 4; dy++) {
			for (int dx = -4; dx <= 4; dx++) {
				if (dx * dx + dy * dy > 16) {
					continue;
				}
				sphereSrc.add(new ShapePos(dx, 10 + dy, 0));
				sphereRot.add(new ShapePos(10 + dx, 70 + dy, 0));
			}
		}
		List<Integer> sphereStations = new ArrayList<>();
		for (int s = 6; s <= 14; s++) {
			sphereStations.add(s);
		}
		List<CanopySettler.Decision> mound = CanopySettler.settle(sphereSrc, sphereRot, true, sphereStations, flat63, unblocked);
		Map<Integer, Integer> stackHeights = new TreeMap<>();
		for (CanopySettler.Decision d : mound) {
			stackHeights.merge(d.target().x(), 1, Integer::sum);
		}
		int centreHeight = stackHeights.getOrDefault(10, 0);
		int edgeHeight = stackHeights.getOrDefault(14, 0);
		check(centreHeight > edgeHeight,
				"sphere centre stacks taller than edge (" + centreHeight + " > " + edgeHeight + ")");
		check(centreHeight > 1, "centre column has a real stack, not a single leaf");

		// The water surface is a support face, so a canopy over water floats.
		TrunkSolver.SupportProbe waterSurface = (x, z) -> 62;
		List<CanopySettler.Decision> afloat = CanopySettler.settle(
				List.of(new ShapePos(0, 10, 0)), List.of(new ShapePos(10, 70, 0)), true, oneStation, waterSurface, unblocked);
		eqi(afloat.get(0).target().y(), 63, "canopy floats on the water surface");
	}

	/* The fallen trunk must keep its cross section, not be flattened into a slab. */
	private static void testShapeIsKept() {
		group("cross section is kept");
		int base = 64;
		Terrain flat = dry((x, z) -> 63);

		Result thin = run(pillar(64, 0, base, 20), 1, 0, base, flat);
		Set<Integer> ys = new TreeSet<>();
		for (TrunkPlanner.Decision d : thin.plan.decisions()) {
			ys.add(d.target().y());
		}
		eqi(ys.size(), 1, "flat 1x1 trunk lies level");
		check(ys.contains(64), "flat 1x1 trunk lies on the ground, ys=" + ys);

		Result redwood = run(cylinder(64, 0, base, 30, 7), 1, 0, base, flat);
		Map<Integer, Set<Integer>> columns = new TreeMap<>();
		for (TrunkPlanner.Decision d : redwood.plan.decisions()) {
			columns.computeIfAbsent(d.target().x(), k -> new TreeSet<>()).add(d.target().y());
		}
		int mid = ((TreeMap<Integer, Set<Integer>>) columns).firstKey() + 15;
		Set<Integer> column = columns.get(mid);
		int span = ((TreeSet<Integer>) column).last() - ((TreeSet<Integer>) column).first() + 1;
		eqi(span, 15, "redwood keeps its 15 block cross section at x=" + mid);
	}

	public static void main(String[] args) {
		testActualSections();
		testTaperFloatBaseline();
		testFlatNoOp();
		testTerrain();
		testCliffs();
		testWalls();
		testNarrowObstruction();
		testEmbedding();
		testSeveredSegmentShape();
		testWater();
		testCanopySettle();
		testShapeIsKept();

		System.out.println();
		if (failures == 0) {
			System.out.println("ALL " + checks + " CHECKS PASSED");
		} else {
			System.out.println(failures + " of " + checks + " CHECKS FAILED");
			System.exit(1);
		}
	}

	private ShapeTests() {
	}
}

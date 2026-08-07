package com.shovinus.chopdownupdated.tree.shape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/*
 * Minecraft free geometry of one trunk turned ninety degrees around its root
 * axis.
 *
 * The trunk is described by the log positions that were actually found, so a
 * trunk that is wide at the root and narrow further up keeps that real profile:
 * nothing is filled in from a configured radius, holes stay holes, and a
 * missing layer stays visible as an empty section.
 *
 * The rotation is a pure lattice permutation, which is what keeps the fallen
 * trunk rigid:
 *
 *   standing height          -> distance away from the stump
 *   standing fall axis       -> height inside the fallen cross section
 *   standing perpendicular   -> carried over unchanged, it is the rotation axis
 */
public final class TrunkShape {

	/* One log of the standing trunk together with its rotated coordinates. */
	public static final class Cell {
		private final ShapePos source;
		private final int step;
		private final int verticalOffset;
		private final int columnX;
		private final int columnZ;
		private final int perpendicular;
		private final long fibreKey;

		private Cell(ShapePos source, int step, int verticalOffset, int columnX, int columnZ,
				int perpendicular, long fibreKey) {
			this.source = source;
			this.step = step;
			this.verticalOffset = verticalOffset;
			this.columnX = columnX;
			this.columnZ = columnZ;
			this.perpendicular = perpendicular;
			this.fibreKey = fibreKey;
		}

		public ShapePos source() {
			return source;
		}

		/* Steps along the fallen beam away from the root cross section. */
		public int step() {
			return step;
		}

		/* Height of this log inside the fallen cross section. */
		public int verticalOffset() {
			return verticalOffset;
		}

		public int columnX() {
			return columnX;
		}

		public int columnZ() {
			return columnZ;
		}

		/*
		 * Coordinate along the rotation axis. Every log sharing this value is stacked
		 * into the same vertical column of the fallen beam, so terrain is judged one
		 * column at a time rather than once for the whole cross section.
		 */
		public int perpendicular() {
			return perpendicular;
		}

		/*
		 * One horizontal fibre of the fallen beam: constant rotation axis column and
		 * constant height inside the cross section. That is exactly one pillar of the
		 * standing trunk, so walking a fibre outward walks along the beam.
		 */
		public long fibreKey() {
			return fibreKey;
		}
	}

	/* The actual cross section at one step along the beam. */
	public static final class Section {
		private final int step;
		private final List<Cell> cells;
		private final int verticalMin;
		private final int verticalMax;

		private Section(int step, List<Cell> cells) {
			this.step = step;
			this.cells = Collections.unmodifiableList(cells);
			int lo = Integer.MAX_VALUE;
			int hi = Integer.MIN_VALUE;
			for (Cell cell : cells) {
				lo = Math.min(lo, cell.verticalOffset());
				hi = Math.max(hi, cell.verticalOffset());
			}
			this.verticalMin = lo;
			this.verticalMax = hi;
		}

		public int step() {
			return step;
		}

		public List<Cell> cells() {
			return cells;
		}

		public int count() {
			return cells.size();
		}

		public boolean isEmpty() {
			return cells.isEmpty();
		}

		/* Lowest log of this cross section, or MAX_VALUE when the section is empty. */
		public int verticalMin() {
			return verticalMin;
		}

		public int verticalMax() {
			return verticalMax;
		}

		/* Vertical extent of this cross section only, not of the whole trunk. */
		public int thickness() {
			return isEmpty() ? 0 : verticalMax - verticalMin + 1;
		}
	}

	private final int fallX;
	private final int fallZ;
	private final int baseY;
	private final int centerX2;
	private final int centerZ2;
	private final int anchorX;
	private final int anchorZ;
	private final int minHeight;
	private final int footprintReach;
	private final int rootAdvance;
	private final int verticalMin;
	private final int verticalMax;
	private final int maxStep;
	private final List<Cell> cells;
	private final Section[] sections;
	private final int emptySteps;

	private TrunkShape(int fallX, int fallZ, int baseY, int centerX2, int centerZ2, int minHeight,
			int footprintReach, List<Cell> cells) {
		this.fallX = fallX;
		this.fallZ = fallZ;
		this.baseY = baseY;
		this.centerX2 = centerX2;
		this.centerZ2 = centerZ2;
		this.anchorX = Math.floorDiv(centerX2, 2);
		this.anchorZ = Math.floorDiv(centerZ2, 2);
		this.minHeight = minHeight;
		this.footprintReach = footprintReach;
		// The lowest fallen cross section lands directly next to the stump, exactly
		// like a trunk pivoting around the cut.
		this.rootAdvance = footprintReach + 1;
		this.cells = Collections.unmodifiableList(cells);

		int step = 0;
		int lo = Integer.MAX_VALUE;
		int hi = Integer.MIN_VALUE;
		for (Cell cell : cells) {
			step = Math.max(step, cell.step());
			lo = Math.min(lo, cell.verticalOffset());
			hi = Math.max(hi, cell.verticalOffset());
		}
		this.maxStep = step;
		this.verticalMin = lo;
		this.verticalMax = hi;

		List<List<Cell>> buckets = new ArrayList<>(maxStep + 1);
		for (int k = 0; k <= maxStep; k++) {
			buckets.add(new ArrayList<>());
		}
		for (Cell cell : cells) {
			buckets.get(cell.step()).add(cell);
		}
		this.sections = new Section[maxStep + 1];
		int empty = 0;
		for (int k = 0; k <= maxStep; k++) {
			sections[k] = new Section(k, buckets.get(k));
			if (sections[k].isEmpty()) {
				empty++;
			}
		}
		this.emptySteps = empty;
	}

	/*
	 * Builds the rotated description of a standing trunk. sources are the actual
	 * trunk log positions, fallX/fallZ is the unit fall direction (exactly one of
	 * them is non zero) and baseY is the chop layer.
	 */
	public static TrunkShape build(List<ShapePos> sources, int fallX, int fallZ, int baseY) {
		if (sources.isEmpty()) {
			throw new IllegalArgumentException("trunk shape needs at least one log");
		}
		if ((fallX == 0) == (fallZ == 0)) {
			throw new IllegalArgumentException("fall direction must use exactly one axis");
		}
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		int minHeight = Integer.MAX_VALUE;
		for (ShapePos pos : sources) {
			minX = Math.min(minX, pos.x());
			maxX = Math.max(maxX, pos.x());
			minZ = Math.min(minZ, pos.z());
			maxZ = Math.max(maxZ, pos.z());
			minHeight = Math.min(minHeight, pos.y() - baseY);
		}
		int centerX2 = minX + maxX;
		int centerZ2 = minZ + maxZ;
		int anchorX = Math.floorDiv(centerX2, 2);
		int anchorZ = Math.floorDiv(centerZ2, 2);

		int footprintReach = Integer.MIN_VALUE;
		for (ShapePos pos : sources) {
			int reach = fallX != 0 ? (pos.x() - anchorX) * fallX : (pos.z() - anchorZ) * fallZ;
			footprintReach = Math.max(footprintReach, reach);
		}
		int rootAdvance = footprintReach + 1;

		int fallSign = fallX != 0 ? fallX : fallZ;
		List<Cell> cells = new ArrayList<>(sources.size());
		for (ShapePos pos : sources) {
			int step = pos.y() - baseY - minHeight;
			int relativeFall2 = -fallSign
					* (fallX != 0 ? pos.x() * 2 - centerX2 : pos.z() * 2 - centerZ2);
			int verticalOffset = Math.floorDiv(relativeFall2, 2);
			int fallDistance = rootAdvance + step;
			int columnX = fallX != 0 ? anchorX + fallX * fallDistance : pos.x();
			int columnZ = fallZ != 0 ? anchorZ + fallZ * fallDistance : pos.z();
			int perpendicular = fallX != 0 ? pos.z() : pos.x();
			long fibreKey = ((long) perpendicular << 32) ^ (verticalOffset & 0xffffffffL);
			cells.add(new Cell(pos, step, verticalOffset, columnX, columnZ, perpendicular,
					fibreKey));
		}
		return new TrunkShape(fallX, fallZ, baseY, centerX2, centerZ2, minHeight, footprintReach,
				cells);
	}

	public int fallX() {
		return fallX;
	}

	public int fallZ() {
		return fallZ;
	}

	public int baseY() {
		return baseY;
	}

	public int anchorX() {
		return anchorX;
	}

	public int anchorZ() {
		return anchorZ;
	}

	public int minHeight() {
		return minHeight;
	}

	/* Largest fall axis offset of the standing footprint from the rotation axis. */
	public int footprintReach() {
		return footprintReach;
	}

	public int rootAdvance() {
		return rootAdvance;
	}

	public int verticalMin() {
		return verticalMin;
	}

	public int verticalMax() {
		return verticalMax;
	}

	/* Vertical extent of the whole rotated trunk. */
	public int thickness() {
		return verticalMax - verticalMin + 1;
	}

	public int maxStep() {
		return maxStep;
	}

	public List<Cell> cells() {
		return cells;
	}

	public Section section(int step) {
		return sections[step];
	}

	public int sectionCount() {
		return sections.length;
	}

	/* Steps with no logs at all: a gap in the trunk, kept visible on purpose. */
	public int emptySteps() {
		return emptySteps;
	}

	public int targetY(Cell cell, int datum, double pitch) {
		return datum + (int) Math.round(pitch * cell.step()) + cell.verticalOffset();
	}

	public ShapePos target(Cell cell, int datum, double pitch) {
		return new ShapePos(cell.columnX(), targetY(cell, datum, pitch), cell.columnZ());
	}
}

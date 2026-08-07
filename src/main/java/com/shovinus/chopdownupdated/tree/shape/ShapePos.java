package com.shovinus.chopdownupdated.tree.shape;

/*
 * Immutable block coordinate for the Minecraft free trunk shape model.
 *
 * The shape package deliberately does not reference BlockPos or World so the
 * whole rigid trunk geometry can be exercised by the offline shape tests.
 */
public record ShapePos(int x, int y, int z) {

	public ShapePos offset(int dx, int dy, int dz) {
		return new ShapePos(x + dx, y + dy, z + dz);
	}

	@Override
	public String toString() {
		return "(" + x + "," + y + "," + z + ")";
	}
}

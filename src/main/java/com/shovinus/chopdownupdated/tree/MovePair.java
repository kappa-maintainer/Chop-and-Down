package com.shovinus.chopdownupdated.tree;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

class TreeMovePair {
	public BlockPos to;
	public BlockPos from;
	public Tree tree;
	public Boolean leaves;
	public Boolean trunk = false;
	// Set by the trunk planner when this section is occluded, for example when it
	// sits behind a wall the leaning beam ran into. Such a log drops as an item
	// instead of being placed on the far side of the obstacle.
	public boolean dropAsItem = false;
	// Where a dropAsItem section actually comes down: the last free cell of its
	// fibre, i.e. the point the trunk leans against the obstacle. Null means drop
	// at the planned beam position.
	public BlockPos dropAt = null;
	// Set by the trunk planner for sections past the break. They are no longer part
	// of the rigid beam: they fall loose from where the beam would have carried them
	// instead of being placed in mid air.
	public boolean severed = false;
	public boolean sourceCleared = false;
	public TileEntity tile;
	private boolean tileResolved = false;
	public IBlockState state;
	public Boolean moved = false;

	public TreeMovePair(BlockPos from, BlockPos to, Tree tree) {
		this.from = from;
		this.to = to;
		this.tree = tree;
		leaves = tree.isLeaves(from);
		trunk = tree.isTrunkBlock(from);
		// The tile entity is fetched lazily: this constructor runs on the
		// calculation thread while the server thread may modify the chunk tile
		// entity map at the same time
		tile = null;
		state = tree.world.getBlockState(from);
		if (tree.isLog(from)) {
			state = tree.rotateLog(tree.world, state);
		}
	}

	/*
	 * Get the tile entity at the source position, fetching it on first use (on
	 * the server thread)
	 */
	public TileEntity getTile() {
		if (!tileResolved) {
			tile = tree.world.getTileEntity(from);
			tileResolved = true;
		}
		return tile;
	}

	public void move() {
		IBlockState state2 = tree.world.getBlockState(to);
		if (!tree.isAir(to)) {
			if (Tree.moveReplaceLogged < 30) {
				System.out.println("[ChopDown-DEBUG] moveReplace to=" + to + " block=" + state2.getBlock()
						+ " trunk=" + trunk + " leaves=" + leaves);
				Tree.moveReplaceLogged++;
			}
			Tree.dropDrops(from, to, state2, tree.world);
		}
		tree.world.setBlockState(to, state);
		if (getTile() != null) {
			NBTTagCompound tileEntityData = getTile().writeToNBT(new NBTTagCompound());
			TileEntity tileentity = tree.world.getTileEntity(to);
			if (tileentity != null) {
				NBTTagCompound nbttagcompound = tileentity.writeToNBT(new NBTTagCompound());

				for (String s : tileEntityData.getKeySet()) {
					NBTBase nbtbase = tileEntityData.getTag(s);

					if (!"x".equals(s) && !"y".equals(s) && !"z".equals(s)) {
						nbttagcompound.setTag(s, nbtbase.copy());
					}
				}

				tileentity.readFromNBT(nbttagcompound);
				tileentity.markDirty();
			}
		}
	}
}

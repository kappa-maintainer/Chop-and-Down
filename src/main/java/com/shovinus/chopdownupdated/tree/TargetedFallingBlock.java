package com.shovinus.chopdownupdated.tree;

import javax.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData;

/*
 * A falling trunk block whose trajectory is visual only: server-side placement
 * always happens at targetPos. This keeps a planned trunk rigid over chasms,
 * slopes, foliage, and previously placed blocks.
 *
 * targetPos == null is reserved for the unregistered leaf entity subclass. It
 * intentionally delegates to vanilla falling-block physics so canopy behaviour
 * remains independent from rigid trunk planning.
 */
public class TargetedFallingBlock extends net.minecraft.entity.item.EntityFallingBlock
		implements IEntityAdditionalSpawnData {

	private boolean isLog = true;
	private BlockPos targetPos;
	private IBlockState clientState;

	public TargetedFallingBlock(World worldIn) {
		super(worldIn);
		// The vanilla no-arg constructor skips setSize, which is the constructor FML
		// uses to build the client side entity. Match the spawned server entity so
		// the falling block is rendered and culled with the same bounds.
		preventEntitySpawning = true;
		setSize(0.98F, 0.98F);
	}

	public TargetedFallingBlock(World worldIn, double x, double y, double z, IBlockState fallingBlockState,
			TileEntity tile, boolean isLog, BlockPos target) {
		super(worldIn, x, y, z, fallingBlockState);
		this.isLog = isLog;
		this.targetPos = target;
		setHurtEntities(true);
		if (tile != null) {
			tileEntityData = tile.writeToNBT(new NBTTagCompound());
		}
	}

	@Override
	@Nullable
	public IBlockState getBlock() {
		IBlockState state = super.getBlock();
		return state != null ? state : clientState;
	}

	@Override
	public void onUpdate() {
		if (targetPos == null) {
			// The internal leaf subclass deliberately retains vanilla ground-seeking.
			super.onUpdate();
			return;
		}

		IBlockState state = getBlock();
		if (state == null || state.getMaterial() == Material.AIR) {
			setDead();
			return;
		}

		prevPosX = posX;
		prevPosY = posY;
		prevPosZ = posZ;
		fallTime++;

		motionY -= 0.03999999910593033D;
		double nextY = posY + motionY;
		double landingY = targetPos.getY() + 0.5D;

		if (!world.isRemote && isLog) {
			clearLeavesOnPath(nextY);
		}

		if (nextY <= landingY || fallTime > 240) {
			setPosition(posX, landingY, posZ);
			if (world.isRemote) {
				setDead();
			} else {
				placeAtTarget(state);
			}
			return;
		}

		// Deliberately bypasses collisions. The trunk plan, not physics, owns the
		// final coordinates and must be able to bridge a ravine.
		setPosition(posX, nextY, posZ);
	}

	private void clearLeavesOnPath(double nextY) {
		int top = (int) Math.floor(posY);
		int bottom = (int) Math.floor(nextY);
		for (int y = top; y >= bottom; y--) {
			if (y <= 0) {
				break;
			}
			BlockPos leafPos = new BlockPos(posX, y, posZ);
			if (Tree.isLeaves(leafPos, world)) {
				Tree.dropDrops(leafPos, leafPos, world.getBlockState(leafPos), world);
				world.setBlockState(leafPos, Blocks.AIR.getDefaultState());
			}
		}
	}

	private void placeAtTarget(IBlockState state) {
		// The planner's pitch cap can leave a section wedged inside a wall or cliff.
		// Drop it as an item rather than demolishing whatever it landed in.
		if (!Tree.canTrunkOccupy(targetPos, world)) {
			Tree.dropDrops(targetPos, targetPos, state, world);
			setDead();
			return;
		}
		IBlockState existing = world.getBlockState(targetPos);
		if (!existing.getBlock().isAir(existing, world, targetPos)) {
			Tree.dropDrops(targetPos, targetPos, existing, world);
		}
		world.setBlockState(targetPos, state, 3);

		Block block = state.getBlock();
		if (block instanceof BlockFalling) {
			((BlockFalling) block).onEndFalling(world, targetPos, state, existing);
		}
		if (tileEntityData != null && block instanceof ITileEntityProvider) {
			TileEntity tileentity = world.getTileEntity(targetPos);
			if (tileentity != null) {
				mergeTileEntityData(tileentity, tileEntityData);
			}
		}
		setDead();
	}

	private static void mergeTileEntityData(TileEntity tileentity, NBTTagCompound sourceData) {
		NBTTagCompound destination = tileentity.writeToNBT(new NBTTagCompound());
		for (String key : sourceData.getKeySet()) {
			if (!"x".equals(key) && !"y".equals(key) && !"z".equals(key)) {
				NBTBase value = sourceData.getTag(key);
				destination.setTag(key, value.copy());
			}
		}
		tileentity.readFromNBT(destination);
		tileentity.markDirty();
	}

	@Override
	protected void writeEntityToNBT(NBTTagCompound compound) {
		super.writeEntityToNBT(compound);
		compound.setBoolean("TargetedLog", isLog);
		if (targetPos != null) {
			compound.setLong("TargetPos", targetPos.toLong());
		}
	}

	@Override
	protected void readEntityFromNBT(NBTTagCompound compound) {
		super.readEntityFromNBT(compound);
		isLog = !compound.hasKey("TargetedLog") || compound.getBoolean("TargetedLog");
		if (compound.hasKey("TargetPos")) {
			targetPos = BlockPos.fromLong(compound.getLong("TargetPos"));
		}
	}

	@Override
	public void writeSpawnData(ByteBuf buffer) {
		IBlockState state = super.getBlock();
		buffer.writeInt(state == null ? Block.getStateId(Blocks.AIR.getDefaultState()) : Block.getStateId(state));
		buffer.writeBoolean(targetPos != null);
		if (targetPos != null) {
			buffer.writeLong(targetPos.toLong());
		}
		buffer.writeBoolean(isLog);
	}

	@Override
	public void readSpawnData(ByteBuf buffer) {
		clientState = Block.getStateById(buffer.readInt());
		if (buffer.readBoolean()) {
			targetPos = BlockPos.fromLong(buffer.readLong());
		}
		isLog = buffer.readBoolean();
	}

	@Nullable
	@Override
	public EntityItem entityDropItem(ItemStack stack, float offsetY) {
		IBlockState state = getBlock();
		if (state == null) {
			return null;
		}
		Block block = state.getBlock();
		BlockPos pos = new BlockPos(this);
		IBlockState toState = world.getBlockState(pos);

		boolean isPassable = toState.getBlock().isPassable(world, pos);
		while (!isPassable && pos.getY() < 256) {
			pos = pos.add(0, 1, 0);
			toState = world.getBlockState(pos);
			isPassable = toState.getBlock().isPassable(world, pos);
		}
		if (pos.getY() > 255) {
			return null;
		}
		Tree.dropDrops(pos, pos, toState, world);
		world.setBlockState(pos, state);
		if (tileEntityData != null && block instanceof ITileEntityProvider) {
			TileEntity tileentity = world.getTileEntity(pos);
			if (tileentity != null) {
				mergeTileEntityData(tileentity, tileEntityData);
			}
		}
		return null;
	}
}

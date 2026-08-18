package com.shovinus.chopdownupdated;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.EntityRegistry;

import java.util.LinkedList;
import java.util.Iterator;
import java.util.concurrent.*;

import org.apache.commons.lang3.ArrayUtils;

import com.shovinus.chopdownupdated.command.CDUCommand;
import com.shovinus.chopdownupdated.config.Config;
import com.shovinus.chopdownupdated.config.TreeConfiguration;
import com.shovinus.chopdownupdated.tree.TargetedFallingBlock;
import com.shovinus.chopdownupdated.tree.Tree;

@Mod(
		modid = ChopDown.MODID,
		name = ChopDown.MODNAME,
		version = ChopDown.VERSION,
		acceptedMinecraftVersions = "[1.12.2]",
		acceptableRemoteVersions = "*",
guiFactory = "com.shovinus.chopdownupdated.config.GuiConfigFactoryChopDown")
public class ChopDown {
	ExecutorService executor;

	public static final String MODID = Reference.MOD_ID;
	public static final String MODNAME = Reference.MOD_NAME;
	public static final String VERSION = Reference.VERSION;
	public static final String AUTHOR = "Shovinus";/*
													 * Original Idea by Ternsip,however the mod does not really resemble
													 * that in any way other that the turning of blocks in to falling
													 * entities with a push out of 1 per y height.
													 */
	public static LinkedList<Tree> FallingTrees = new LinkedList<Tree>();

	@EventHandler
	public void init(FMLInitializationEvent event) {
		MinecraftForge.EVENT_BUS.register(this);
	}

	@EventHandler
	public void preinit(FMLPreInitializationEvent event) {
		Config.load(event);
		// Embedded HT's TreeChop (MIT, hammertater): the chop-layer mechanic is
		// registered here so TreeMode can route breaking behaviour between the
		// rigid-fell and chop-layer systems. Its config lives in treechop.cfg.
		ht.treechop.TreeChopMod.init(event);
		// Falling block entity for the visible trunk fall animation. updateFrequency
		// 2 = position packet every other tick, smooth enough for the short fall.
		EntityRegistry.registerModEntity(new ResourceLocation(MODID, "targeted_falling_block"),
				TargetedFallingBlock.class, "targeted_falling_block", 0, this, 64, 2, true);
		// Keep FML spawning enabled. TargetedFallingBlock implements
		// IEntityAdditionalSpawnData so the client receives the block state and the
		// exact planned target position rather than guessing a vanilla landing spot.
		if (event.getSide().isClient()) {
			// Separate class so the client render classes are never loaded on a
			// dedicated server
			ClientRenderRegistration.register();
		}
	}

	@EventHandler
	public void postinit(FMLPostInitializationEvent event) {
		ht.treechop.TreeChopMod.postInit(event);
	}

	@EventHandler
	public void serverLoad(FMLServerStartingEvent event) {
		// register server commands
		event.registerServerCommand(new CDUCommand());
		executor = Executors.newFixedThreadPool(2);
	}

	@SubscribeEvent
	public void onBlockBreak(BlockEvent.BreakEvent event) {

		// TreeMode routes between the embedded systems. TREECHOP is handled by
		// ht.treechop Common.onBreakEvent; CHOPDOWN and BOTH are handled here.
		if (Config.treeMode == Config.TreeMode.OFF || Config.treeMode == Config.TreeMode.TREECHOP) {
			return;
		}

		World world = event.getWorld();
		BlockPos pos = event.getPos();
		EntityPlayer player = event.getPlayer();

		if (Config.treeMode == Config.TreeMode.BOTH) {
			System.out.println("[ChopDown-DEBUG] both entry: pos=" + pos + " state="
					+ Tree.blockName(pos, world) + " mode=" + Config.treeMode);
			handleBoth(event, world, pos, player);
			return;
		}

		// ===================== CHOPDOWN mode =====================
		if (!Tree.isWood(pos, world)
				|| !ArrayUtils.contains(Config.allowedPlayers, player.getClass().getName())) {
			return;
		}
		if (player.getHeldItemMainhand() != null
				&& Config.MatchesTool(Tree.stackName(player.getHeldItemMainhand()))) {
			return;
		}
		TreeConfiguration config = Tree.findConfig(world, pos);
		BlockPos playerStanding = player.getPosition();
		if (config == null || !Tree.isTrunk(pos, world, config) || !Tree.isWood(pos.add(0, 1, 0), world)
				|| (playerStanding.getX() == 0 && playerStanding.getZ() == 0)) {
			return;
		}

		// Check to see if this player has already started a tree chop event. Trees that
		// failed to build (e.g. the chop layer is not cut through enough for the
		// configured cut ratio) are removed on the next tick; they must not block the
		// player from breaking more logs, or fast consecutive breaks would be
		// cancelled while the failed tree is still in the list. Trees that are still
		// being analysed on the calculation thread must not block either: the running
		// BFS will see the newly broken blocks, so we just skip creating another tree.
		Tree activeTree = null;
		for (Tree tree : FallingTrees) {
			if (tree.player == player && !tree.failedToBuild) {
				activeTree = tree;
				break;
			}
		}
		if (activeTree != null) {
			if (!activeTree.finishedCalculation) {
				return;
			}
			player.sendMessage(new TextComponentString("Still chopping down the last tree"));
			event.setCanceled(true);
			return;
		}
		//Initialise the tree and add it to the list, get the executor to start chopping it down;;
		Tree tree;
		try {
			tree = new Tree(pos, world, player);
			FallingTrees.add(tree);
			executor.submit(tree);
		} catch (Exception e) {
			player.sendMessage(new TextComponentString("Can't find a tree configuration for this log."));
		}

	}

	/*
	 * BOTH mode: HT's chop-accumulation mechanic drives the visuals (one chop
	 * layer per swing, chopping continues until the tree has taken enough
	 * chops), but when the threshold is reached ChopDown rigid-fells the whole
	 * tree with falling-block animation instead of HT's instant destruction.
	 * The chopped cell survives as the stump and is destroyed (dropping the
	 * log) once the fell completes.
	 */
	private void handleBoth(BlockEvent.BreakEvent event, World world, BlockPos pos, EntityPlayer player) {
		IBlockState originalState = world.getBlockState(pos);
		boolean isChopped = originalState.getBlock() instanceof ht.treechop.api.IChoppableBlock;

		// Only logs and chopped logs are handled; everything else is vanilla.
		if (!isChopped && !ht.treechop.common.util.ChopUtil.isBlockChoppable(world, pos, originalState)) {
			System.out.println("[ChopDown-DEBUG] both: not choppable at " + pos + " state="
					+ Tree.blockName(pos, world));
			return;
		}
		if (player.getHeldItemMainhand() != null
				&& Config.MatchesTool(Tree.stackName(player.getHeldItemMainhand()))) {
			System.out.println("[ChopDown-DEBUG] both: tool match, vanilla break at " + pos);
			return;
		}

		// When the player hits a chopped cell, the trunk base is the first real
		// log above it (ChopUtil.isBlockALog would stop on a chopped cell, and
		// findConfig needs an actual log).
		BlockPos basePos = pos;
		if (isChopped) {
			BlockPos up = pos;
			while (true) {
				up = up.add(0, 1, 0);
				if (up.getY() > world.getHeight()) {
					System.out.println("[ChopDown-DEBUG] both: no trunk above chopped cell at " + pos);
					return;
				}
				if (Tree.isWood(up, world)) {
					basePos = up;
					break;
				}
				if (!(world.getBlockState(up).getBlock() instanceof ht.treechop.api.IChoppableBlock)) {
					System.out.println("[ChopDown-DEBUG] both: chopped cell " + pos + " has non-tree above at "
							+ up + " (" + Tree.blockName(up, world) + ")");
					return;
				}
			}
		}

		ItemStack tool = player.getHeldItemMainhand();
		int numChops = ht.treechop.common.util.ChopUtil.getNumChopsByTool(tool, originalState);

		// The tree must be a fellable ChopDown trunk for the rigid fell; any
		// other choppable wood falls back to the full HT behaviour (chop layers,
		// and HT's instant fell once the chop count is met).
		TreeConfiguration config = Tree.findConfig(world, basePos);
		if (config == null || !Tree.isTrunk(basePos, world, config)) {
			System.out.println("[ChopDown-DEBUG] both: NOT a fellable trunk (config=" + (config != null)
					+ ") at base=" + basePos + " -> HT fallback");
			ht.treechop.common.util.ChopResult result = ht.treechop.common.util.ChopUtil.getChopResult(world, pos,
					player, numChops, true, p -> ht.treechop.common.util.ChopUtil.isBlockALog(world, p));
			if (result != ht.treechop.common.util.ChopResult.IGNORED && result.apply(pos, player, tool,
					ht.treechop.common.config.ConfigHandler.COMMON.breakLeaves.get())) {
				event.setCanceled(true);
			}
			return;
		}

		// A fell already in progress blocks further chops.
		for (Tree tree : FallingTrees) {
			if (tree.player == player && !tree.failedToBuild) {
				if (!tree.finishedCalculation) {
					System.out.println("[ChopDown-DEBUG] both: fell calculating, skip");
					return;
				}
				System.out.println("[ChopDown-DEBUG] both: fell in progress, skip");
				player.sendMessage(new TextComponentString("Still chopping down the last tree"));
				event.setCanceled(true);
				return;
			}
		}

		// HT chop accounting: how many chops the tree needs, and how many chops
		// are already stored in the tree's chopped cells.
		java.util.Set<BlockPos> treeBlocks = ht.treechop.common.util.ChopUtil.getTreeBlocks(world, basePos,
				p -> ht.treechop.common.util.ChopUtil.isBlockALog(world, p), false);
		int numChopsToFell = ht.treechop.common.util.ChopUtil.numChopsToFell(treeBlocks.size());
		java.util.Set<BlockPos> nearby = ht.treechop.common.util.ChopUtil.getConnectedBlocks(
				java.util.Collections.singletonList(basePos),
				p -> ht.treechop.common.util.BlockNeighbors.ADJACENTS_AND_DIAGONALS.asStream(p)
						.filter(q -> Math.abs(q.getY() - p.getY()) < 4
								&& ht.treechop.common.util.ChopUtil.isBlockChoppable(world, q)),
				64);
		int currentChops = ht.treechop.common.util.ChopUtil.getNumChops(world, nearby);

		System.out.println("[ChopDown-DEBUG] both: pos=" + pos + " chopped=" + isChopped + " base=" + basePos
				+ " config=" + (config != null ? "Y" : "N") + " treeBlocks=" + treeBlocks.size()
				+ " toFell=" + numChopsToFell + " chops=" + currentChops + " +" + numChops);

		if (currentChops + numChops < numChopsToFell) {
			// Not enough chops yet: add another chop layer (HT visual) and keep
			// the cell standing.
			nearby.remove(pos);
			ht.treechop.common.util.ChopResult result = ht.treechop.common.util.ChopUtil
					.gatherChops(world, pos, numChops, nearby);
			if (result != ht.treechop.common.util.ChopResult.IGNORED) {
				result.apply(pos, player, tool, false);
				event.setCanceled(true);
			}
			return;
		}

		// Enough chops: rigid-fell the whole tree with ChopDown's falling
		// blocks instead of HT's instant destruction.
		try {
			IBlockState fellStumpState = originalState;
			if (isChopped) {
				// The Tree constructor needs a configured log at the base, so
				// every chopped cell of the tree is restored to the trunk log
				// for the fell; otherwise the felling BFS breaks on the first
				// chopped cell. The stump cell is destroyed (dropping the log)
				// once the fell completes.
				IBlockState trunkState = world.getBlockState(basePos);
				for (BlockPos bp : treeBlocks) {
					if (world.getBlockState(bp).getBlock() instanceof ht.treechop.api.IChoppableBlock) {
						world.setBlockState(bp, trunkState, 2);
					}
				}
				world.setBlockState(pos, trunkState, 3);
				fellStumpState = trunkState;
			}
			Tree tree = new Tree(pos, world, player);
			FallingTrees.add(tree);
			executor.submit(tree);
			tree.destroyStump = true;
			tree.stumpOriginalState = fellStumpState;
			event.setCanceled(true);
			System.out.println("[ChopDown-DEBUG] both: rigid fell started at " + pos);
		} catch (Exception e) {
			player.sendMessage(new TextComponentString("Can't find a tree configuration for this log."));
		}
	}

	static int tick = 0;

	@SubscribeEvent
	public void onTick(TickEvent.ServerTickEvent event) {
		try {
			tick++;
			boolean throttledTick = tick % 4 == 0;
			if (throttledTick) {
				tick = 0;
			}
			Iterator<Tree> iterator = FallingTrees.iterator();
			while (iterator.hasNext()) {
				Tree tree = iterator.next();
				if (tree.failedToBuild) {
					iterator.remove();
				} else if (tree.finishedCalculation
						&& (!tree.startedDropping || throttledTick) && tree.dropBlocks()) {
					iterator.remove();
				}
			}
		} catch (Exception ex) {
			System.out.println("Error while continuing to chop trees");
		}
	}

	@SubscribeEvent
	public void clickBlock(PlayerInteractEvent.LeftClickBlock event) {
		if (!(event.getEntityPlayer() instanceof EntityPlayerMP)) {
			return;
		}
		if (Config.getPlayerConfig(event.getEntityPlayer().getUniqueID()).showBlockName) {
			World world = event.getWorld();
			BlockPos pos = event.getPos();
			event.getEntityPlayer().sendMessage(new TextComponentString("Block:" + Tree.blockName(pos, world)));
			if (event.getEntityPlayer().getHeldItemMainhand() != null) {
				event.getEntityPlayer().sendMessage(new TextComponentString(
						"Tool:" + Tree.stackName(event.getEntityPlayer().getHeldItemMainhand())));
			}
			event.getEntityPlayer().sendMessage(
					new TextComponentString("Player Class:" + event.getEntityPlayer().getClass().getName()));
		}
	}
}
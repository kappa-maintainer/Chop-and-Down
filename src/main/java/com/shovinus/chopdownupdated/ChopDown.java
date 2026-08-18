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

		// Hanging trees (natura bloodwood) grow downward from a ceiling. They are
		// marked in the tree config. CHOPDOWN keeps the direct downward fell
		// (there is no upward trunk pipeline for them); BOTH runs the normal
		// chop-layer mechanic and only the fell itself goes downward, handled
		// in handleBoth/fellBoth.
		TreeConfiguration hangingConfig = Tree.findConfig(world, pos);
		if (Config.treeMode == Config.TreeMode.CHOPDOWN && hangingConfig != null && hangingConfig.Hanging()
				&& Tree.isWood(pos, world)) {
			Config.debugLog("[ChopDown-DEBUG] hanging: fell at " + pos + " name=" + Tree.blockName(pos, world));
			fellHangingTree(event, world, pos, player, hangingConfig, pos);
			return;
		}

		if (Config.treeMode == Config.TreeMode.BOTH) {
			Config.debugLog("[ChopDown-DEBUG] both entry: pos=" + pos + " state="
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
			Config.debugLog("[ChopDown-DEBUG] chopdown: not a fellable trunk at " + pos + " name="
					+ Tree.blockName(pos, world) + " config=" + (config != null)
					+ " isTrunk=" + (config != null && Tree.isTrunk(pos, world, config))
					+ " upWood=" + Tree.isWood(pos.add(0, 1, 0), world)
					+ " atOrigin=" + (playerStanding.getX() == 0 && playerStanding.getZ() == 0));
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
	 * Hanging (upside-down) tree: the hit log is part of a trunk that grows
	 * downward from a ceiling. Everything from the hit point down (trunk and
	 * canopy) is released as falling blocks that drop vertically to the floor;
	 * the section above the cut (attached to the ceiling) stays.
	 *
	 * In BOTH mode this is called once the chop layer is fully chopped (each
	 * cell holds its two chop layers), so the chop mechanic is respected and
	 * the fell itself just goes downward instead of through the upward-growing
	 * trunk pipeline. Chopped cells of the fell range are restored to the
	 * trunk log first.
	 */
	private void fellHangingTree(BlockEvent.BreakEvent event, World world, BlockPos pos, EntityPlayer player,
			TreeConfiguration config, BlockPos basePos) {
		try {
			// Search downward from the cut: the hanging trunk, the chopped
			// cells of the chop layer and the canopy. Sideways cells of the
			// same layer (2x2 trunks) are included.
			java.util.Set<BlockPos> tree = new java.util.HashSet<>();
			java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
			queue.add(pos);
			while (!queue.isEmpty()) {
				BlockPos p = queue.poll();
				if (tree.contains(p) || p.getY() <= 0) {
					continue;
				}
				String name = Tree.blockName(p, world);
				boolean choppedCell = world.getBlockState(p)
						.getBlock() instanceof ht.treechop.api.IChoppableBlock;
				if (!config.isLog(name) && !config.isLeaf(name) && !choppedCell) {
					continue;
				}
				tree.add(p);
				// Expand sideways on the same layer and downward only; the
				// ceiling-side section above the cut is not part of the drop.
				BlockPos[] neighbors = { p.down(), p.east(), p.west(), p.north(), p.south() };
				for (BlockPos n : neighbors) {
					if (n.getY() <= pos.getY()) {
						queue.add(n);
					}
				}
			}
			event.setCanceled(true);

			// Restore every chopped cell of the fell range back to the trunk
			// log so the whole tree falls as regular wood.
			IBlockState trunkState = world.getBlockState(basePos);
			for (BlockPos p : tree) {
				if (world.getBlockState(p).getBlock() instanceof ht.treechop.api.IChoppableBlock) {
					world.setBlockState(p, trunkState, 2);
				}
			}

			// Release from the lowest block up. Each block flies to the first
			// solid spot below it; liquids (lava pools in nether caverns) act
			// as a surface, so the tree rests on top of the lava instead of
			// sinking into it and despawning. The whole fell range is cleared
			// first so the landing scan looks through open air (a targeted
			// falling block does not verify its origin block, unlike the
			// vanilla entity, so clearing first is safe here).
			java.util.List<BlockPos> sorted = new java.util.ArrayList<>(tree);
			sorted.sort(java.util.Comparator.comparingInt(BlockPos::getY));
			java.util.List<IBlockState> states = new java.util.ArrayList<>();
			for (BlockPos p : sorted) {
				states.add(world.getBlockState(p));
			}
			for (BlockPos p : sorted) {
				world.setBlockState(p, net.minecraft.init.Blocks.AIR.getDefaultState(), 3);
			}
			// Every block gets its own unique landing spot: blocks sharing a
			// column stack upward from the surface instead of turning into
			// item drops (which a lava pool burns up instantly). Leaves are
			// placed non-decaying so a stacked canopy survives without trunk
			// support.
			java.util.Map<Long, Integer> stackHeights = new java.util.HashMap<>();
			for (int i = 0; i < sorted.size(); i++) {
				IBlockState st = states.get(i);
				if (st.getBlock() == net.minecraft.init.Blocks.AIR) {
					continue;
				}
				BlockPos landing = findLandingPos(world, sorted.get(i));
				long key = ((long) landing.getX() << 32) | (landing.getZ() & 0xFFFFFFFFL);
				int h = stackHeights.getOrDefault(key, 0);
				landing = landing.add(0, h, 0);
				stackHeights.put(key, h + 1);
				if (st.getBlock() instanceof net.minecraft.block.BlockLeaves) {
					st = st.withProperty(net.minecraft.block.BlockLeaves.DECAYABLE, false);
				}
				// isLog=false skips clearLeavesOnPath so the canopy falls as
				// whole blocks instead of being turned into items early.
				TargetedFallingBlock fallingBlock = new TargetedFallingBlock(world,
						sorted.get(i).getX() + 0.5, sorted.get(i).getY() + 0.5, sorted.get(i).getZ() + 0.5,
						st, null, false, landing);
				world.spawnEntity(fallingBlock);
			}
		} catch (Exception e) {
			player.sendMessage(new TextComponentString("Can't find a tree configuration for this log."));
		}
	}

	/*
	 * First resting spot under the block: air lets it pass, a solid block
	 * stops it, and a liquid (water or lava) counts as a surface so the block
	 * floats on top of it instead of sinking in and despawning.
	 */
	private BlockPos findLandingPos(World world, BlockPos p) {
		BlockPos landing = p;
		while (landing.getY() > 1) {
			BlockPos below = landing.down();
			IBlockState belowState = world.getBlockState(below);
			net.minecraft.block.Block b = belowState.getBlock();
			if (b.isAir(belowState, world, below)) {
				landing = below;
				continue;
			}
			if (b instanceof net.minecraft.block.BlockLiquid) {
				break;
			}
			if (b.isPassable(world, below)) {
				landing = below;
				continue;
			}
			break;
		}
		return landing;
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
			Config.debugLog("[ChopDown-DEBUG] both: not choppable at " + pos + " state="
					+ Tree.blockName(pos, world));
			return;
		}
		if (player.getHeldItemMainhand() != null
				&& Config.MatchesTool(Tree.stackName(player.getHeldItemMainhand()))) {
			Config.debugLog("[ChopDown-DEBUG] both: tool match, vanilla break at " + pos);
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
					Config.debugLog("[ChopDown-DEBUG] both: no trunk above chopped cell at " + pos);
					return;
				}
				if (Tree.isWood(up, world)) {
					basePos = up;
					break;
				}
				if (!(world.getBlockState(up).getBlock() instanceof ht.treechop.api.IChoppableBlock)) {
					Config.debugLog("[ChopDown-DEBUG] both: chopped cell " + pos + " has non-tree above at "
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
		if (config == null) {
			Config.debugLog("[ChopDown-DEBUG] both: no tree config for " + basePos + " -> HT fallback");
			ht.treechop.common.util.ChopResult result = ht.treechop.common.util.ChopUtil.getChopResult(world, pos,
					player, numChops, true, p -> ht.treechop.common.util.ChopUtil.isBlockALog(world, p));
			if (result != ht.treechop.common.util.ChopResult.IGNORED && result.apply(pos, player, tool,
					ht.treechop.common.config.ConfigHandler.COMMON.breakLeaves.get())) {
				event.setCanceled(true);
			}
			return;
		}
		// A stump, a single log or a fallen log has nothing above it: it is not
		// a tree, so it keeps the vanilla behaviour instead of showing chop
		// layers. The block above the base may be a log or leaves: a short tree
		// (natura hopseed) tops out straight into the canopy, and the canopy is
		// part of the tree.
		// Hanging trees skip the trunk/above checks entirely: the config flag
		// is the tree marker, the trunk top sits against the ceiling so the
		// block above the base is netherrack, and the downward run ends in
		// open air or lava (isTrunk already short-circuits for them). Without
		// this, chopping a chopped cell walks the base up to the trunk top and
		// the ceiling fails the above-is-tree check.
		if (!config.Hanging()) {
			BlockPos abovePos = basePos.add(0, 1, 0);
			boolean aboveIsTree = Tree.isWood(abovePos, world) || Tree.isLeaves(abovePos, world);
			if (!Tree.isTrunk(basePos, world, config) || !aboveIsTree) {
				Config.debugLog("[ChopDown-DEBUG] both: not a tree at base=" + basePos + " (above="
						+ Tree.blockName(abovePos, world) + ") -> vanilla break");
				return;
			}
		}

		// A fell already in progress blocks further chops.
		for (Tree tree : FallingTrees) {
			if (tree.player == player && !tree.failedToBuild) {
				if (!tree.finishedCalculation) {
					Config.debugLog("[ChopDown-DEBUG] both: fell calculating, skip");
					return;
				}
				Config.debugLog("[ChopDown-DEBUG] both: fell in progress, skip");
				player.sendMessage(new TextComponentString("Still chopping down the last tree"));
				event.setCanceled(true);
				return;
			}
		}

		// BOTH chop mechanic: every cell of the chop layer takes at most two
		// chops - the first shows the in-between chopped state, the second
		// fills it. Once the hit cell is full, further swings spill the chop
		// HORIZONTALLY to the nearest unfilled cell of the same layer, so a
		// thick trunk fills its whole outer ring without the player having to
		// hit every cell. The tree rigid-fells when every peripheral cell of
		// the layer is filled.
		int r = config.Trunk_Radius();
		if (ht.treechop.common.util.ChopUtil.getNumChops(world, pos) < 2) {
			IBlockState chopped = ht.treechop.common.util.ChopUtil
					.getBlockStateAfterChops(world, pos, 1, false);
			IBlockState stateNow = world.getBlockState(pos);
			if (chopped != stateNow) {
				world.setBlockState(pos, chopped, 3);
			}
		} else {
			spillChopLayer(world, pos, config, r);
		}
		event.setCanceled(true);

		// Is the chop layer fully chopped? Every peripheral cell of the trunk
		// cross-section at this height must hold two chop layers.
		int layerTotal = 0;
		int layerFull = 0;
		for (int qx = -r; qx <= r; qx++) {
			for (int qz = -r; qz <= r; qz++) {
				BlockPos p = pos.add(qx, 0, qz);
				if (!isChopLayerCell(world, p, config)) {
					continue;
				}
				layerTotal++;
				if (ht.treechop.common.util.ChopUtil.getNumChops(world, p) >= 2) {
					layerFull++;
				}
			}
		}
		Config.debugLog("[ChopDown-DEBUG] both: pos=" + pos + " chopped=" + isChopped + " base=" + basePos
				+ " config=" + (config != null ? "Y" : "N") + " layerFull=" + layerFull + "/" + layerTotal);

		if (layerTotal > 0 && layerFull >= layerTotal) {
			if (config.Hanging()) {
				fellHangingTree(event, world, pos, player, config, basePos);
			} else {
				fellBoth(event, world, pos, player, originalState, basePos);
			}
		}
	}

	/*
	 * A chop-layer cell: a configured log or chopped cell of the trunk
	 * cross-section, excluding interior cells of a very thick trunk (they are
	 * not chopable and do not have to be chopped for the tree to fall).
	 */
	private boolean isChopLayerCell(World world, BlockPos p, TreeConfiguration config) {
		IBlockState st = world.getBlockState(p);
		boolean isTreeCell = config.isLog(Tree.blockName(p, world))
				|| st.getBlock() instanceof ht.treechop.api.IChoppableBlock;
		if (!isTreeCell) {
			return false;
		}
		return ht.treechop.common.util.ChopUtil.isBlockChoppable(world, p, st)
				|| st.getBlock() instanceof ht.treechop.api.IChoppableBlock;
	}

	/*
	 * The hit cell already holds two chop layers; spill one more layer to the
	 * nearest unfilled peripheral cell of the same layer, searching outward in
	 * rings. Horizontal only: the chop never crawls up the trunk.
	 */
	private void spillChopLayer(World world, BlockPos pos, TreeConfiguration config, int r) {
		for (int radius = 1; radius <= r; radius++) {
			for (int qx = -radius; qx <= radius; qx++) {
				for (int qz = -radius; qz <= radius; qz++) {
					if (Math.max(Math.abs(qx), Math.abs(qz)) != radius) {
						continue;
					}
					BlockPos p = pos.add(qx, 0, qz);
					if (!isChopLayerCell(world, p, config)) {
						continue;
					}
					if (ht.treechop.common.util.ChopUtil.getNumChops(world, p) < 2) {
						IBlockState chopped = ht.treechop.common.util.ChopUtil
								.getBlockStateAfterChops(world, p, 1, false);
						IBlockState stateNow = world.getBlockState(p);
						if (chopped != stateNow) {
							world.setBlockState(p, chopped, 3);
						}
						return;
					}
				}
			}
		}
	}

	/*
	 * The chop layer is fully chopped: rigid-fell the whole tree with
	 * ChopDown's falling blocks. Every chopped cell of the tree is restored to
	 * the trunk log for the fell (the Tree constructor needs a configured log
	 * at the base and the felling BFS breaks on chopped cells), and the stump
	 * cell is destroyed, dropping the log, once the fell completes.
	 */
	private void fellBoth(BlockEvent.BreakEvent event, World world, BlockPos pos, EntityPlayer player,
			IBlockState originalState, BlockPos basePos) {
		try {
			java.util.Set<BlockPos> treeBlocks = ht.treechop.common.util.ChopUtil.getTreeBlocks(world, pos,
					p -> ht.treechop.common.util.ChopUtil.isBlockALog(world, p), false);
			IBlockState fellStumpState = originalState;
			if (originalState.getBlock() instanceof ht.treechop.api.IChoppableBlock) {
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
			// BOTH: a fully chopped chop layer is the fell trigger, not the
			// chop-layer cut ratio. A thick trunk (e.g. natura hopseed 2x2)
			// must not abort the fell just because Min_cut_ratio is unmet.
			tree.skipChopLayerCheck = true;
			FallingTrees.add(tree);
			executor.submit(tree);
			tree.destroyStump = true;
			tree.stumpOriginalState = fellStumpState;
			event.setCanceled(true);
			Config.debugLog("[ChopDown-DEBUG] both: rigid fell started at " + pos);
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
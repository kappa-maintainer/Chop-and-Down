package com.shovinus.chopdownupdated.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.shovinus.chopdownupdated.config.TreeConfiguration;
import com.shovinus.chopdownupdated.config.Config;
import com.shovinus.chopdownupdated.config.PersonalConfig;
import com.shovinus.chopdownupdated.tree.shape.ShapePos;
import com.shovinus.chopdownupdated.tree.shape.TrunkPlanner;
import com.shovinus.chopdownupdated.tree.shape.TrunkShape;
import com.shovinus.chopdownupdated.tree.shape.TrunkSolver;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

public class Tree implements Runnable {

	BlockPos base;
	BlockPos treeCenter;
	public World world;
	public EntityPlayer player;
	Boolean main = false;
	HashMap<BlockPos, Integer> estimatedTree = new HashMap<>();
	PriorityQueue<BlockPos> queue = new PriorityQueue<>(new BuilderQueueComparer(estimatedTree));
	LinkedList<BlockPos> estimatedTreeQueue = new LinkedList<>();

	LinkedList<BlockPos> realisticTree = new LinkedList<>();

	HashMap<BlockPos, String> blockNameCache = new HashMap<>();
	HashMap<BlockPos, Boolean> logCache = new HashMap<>();
	HashMap<BlockPos, Boolean> leafCache = new HashMap<>();
	HashMap<BlockPos, Boolean> trunkCache = new HashMap<>();
	HashMap<BlockPos, Boolean> trunkBlockCache = new HashMap<>();
	HashMap<BlockPos, Boolean> draggableCache = new HashMap<>();
	HashMap<Long, Integer> supportHeightCache = new HashMap<>();
	HashSet<BlockPos> movingTreeBlocks = new HashSet<>();
	HashSet<BlockPos> trunkTargets = new HashSet<>();
	/* Terrain cells a redwood sized trunk is allowed to bed into as it lands. */
	HashSet<BlockPos> trunkEmbedded = new HashSet<>();

	HashMap<BlockPos, TreeMovePair> fallingBlocks = new HashMap<>();

	LinkedList<BlockPos> fallingBlocksList;

	int fallX = 1;
	int fallZ = 0;
	int fallOffset = 0;
	double trunkPitch = 0;
	boolean trunkAnimated = true;
	int trunkMaxRelY = 0;
	private int supportScanTop = 0;
	private boolean trunkSourcesCleared = false;

	EnumFallAxis axis = EnumFallAxis.X;

	TreeConfiguration config;

	int radius = 8;
	int leafLimit = 7;

	boolean wentUp = false;

	public volatile boolean finishedCalculation = false;
	public volatile boolean failedToBuild = false;
	public volatile boolean startedDropping = false;
	private volatile boolean pendingLeavesBroken = false;

	LinkedList<Tree> nearbyTrees = new LinkedList<Tree>();

	/*
	 * Get a tree estimate, used in forests to calculate if leaves should belong to
	 * this tree or the tree we are chopping down
	 */
	public Tree(BlockPos pos, World world) throws Exception {
		initTree(pos, world);
		while (isLog(pos.add(0, -1, 0))) {
			pos = pos.add(0, -1, 0);
		}
		base = pos;
		getPossibleTree();
	}

	/*
	 * Add a tree that can be chopped down, this is one we are targeting to chop as
	 * opposed to one we just want to get an estimate of blocks from
	 */
	public Tree(BlockPos pos, World world, EntityPlayer player) throws Exception {
		main = true;
		this.player = player;
		initTree(pos, world);
		getFallDirection(player);
	}

	public static TreeConfiguration findConfig(World world, BlockPos pos) {
		for (TreeConfiguration treeConfig : Config.treeConfigurations) {
			if (treeConfig.isLog(blockName(pos, world))) {
				return treeConfig;
			}
		}
		return null;
	}

	/*
	 * Setup the basic settings of the tree
	 */
	private void initTree(BlockPos pos, World world) throws Exception {
		base = pos;
		this.world = world;
		addEstimateBlock(base, 0);
		this.config = findConfig(world, pos);
		if (this.config == null) {
			System.out.println(blockName(base, world) + " block has no tree configuration");
			throw new Exception("The chopped log type is unknown and not setup");
		}
		this.radius = this.config.Radius();
		this.leafLimit = this.config.Leaf_limit();

	}

	/*
	 * Calculate which direction the tree should fall in
	 */
	private void getFallDirection(EntityPlayer player) {
		double x = ((base.getX() + 0.5) - player.posX);
		double z = (base.getZ() + 0.5) - player.posZ;
		double abX = Math.abs(x);
		double abZ = Math.abs(z);
		fallX = (int) Math.floor(abX / x);
		fallZ = (int) Math.floor(abZ / z);
		if (abX > abZ) {
			fallZ = 0;
			axis = EnumFallAxis.Z;
		} else {
			fallX = 0;
			axis = EnumFallAxis.X;
		}
	}

	public boolean isLog(BlockPos pos) {
		return logCache.computeIfAbsent(pos, key -> isLog(blockName(key)));
	}

	private boolean isLog(String name) {
		return config.isLog(name);
	}

	public boolean isLeaf(BlockPos pos) {
		return leafCache.computeIfAbsent(pos, key -> isLeaf(blockName(key)));
	}

	private boolean isLeaf(String name) {
		return config.isLeaf(name);
	}

	/*
	 * Gets a possible tree, but only if it thinks the trunk is completely cut
	 * through
	 */
	private void getPossibleTree() throws Exception {
		boolean ratioCheckNeeded = false;
		boolean quickRatioChecked = false;
		HashMap<Integer, Integer> layerLogs = new HashMap<>();
		// Radius checks are centered on the trunk (the average of the log positions
		// in the layer above the chop point) instead of the chop point itself, so a
		// chop point near one side of a thick trunk does not cut the canopy off
		// asymmetrically
		int centerX = 0, centerZ = 0, centerCount = 0;
		int cr = config.Trunk_Radius();
		for (int qx = -cr; qx <= cr; qx++) {
			for (int qz = -cr; qz <= cr; qz++) {
				if (isLog(base.add(qx, 1, qz))) {
					centerX += base.getX() + qx;
					centerZ += base.getZ() + qz;
					centerCount++;
				}
			}
		}
		treeCenter = centerCount > 0 ? new BlockPos(centerX / centerCount, base.getY(), centerZ / centerCount)
				: base;
        while (!queue.isEmpty()) {
            BlockPos blockStep = queue.poll();
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    for (int dz = -1; dz <= 1; ++dz) {
                        int dzA = dz * dz, dxA = dx * dx, dyA = dy * dy;
                        int stepInc = (dzA + dxA + dyA);
                        BlockPos inspectPos = blockStep.add(dx, dy, dz);
                        String blockName = blockName(inspectPos);

                        boolean log = isLog(blockName);
                        boolean leaf = !log && isLeaf(blockName);
                        if (!(log || leaf)) {
                            continue;
                        }

                        boolean logAbove = isLog(inspectPos.add(0, 1, 0));
                        int y = inspectPos.getY();
                        boolean isTrunk = isTrunk(inspectPos);
                        boolean yMatch = (y == base.getY());
                        if (y > base.getY()) {
                            wentUp = true;
                        }
                        Integer leafStep = getEstimate(blockStep);
                        leafStep = (leafStep == null ? 0 : leafStep) + (leaf ? stepInc : 0);

                        // Don't chop below the chop point, nor if this is the base point, nor if
                        // leafStep reached, nor if radius limit reaches, nor if this block is our main
                        // block
                        if (inspectPos.compareTo(base) == 0 || y < base.getY() || leafStep >= leafLimit
                                || horizontalDistanceSquared(treeCenter, inspectPos) > radius * radius) {
                            continue;
                        }
                        // If not directly connected to the tree search down for a base
                        if (log && (leafStep > 0 || dy < 0) && !estimatedTree.containsKey(inspectPos) && isTrunk
                                && (Math.abs(inspectPos.getX() - treeCenter.getX()) > config.Trunk_Radius()
                                        || Math.abs(inspectPos.getZ() - treeCenter.getZ()) > config.Trunk_Radius())) {
                            // Its the trunk of another tree, check to see if we already have this tree in
                            // the list, or add it.
                            if (main) {
                                boolean treeFound = false;
                                for (Tree tree : nearbyTrees) {
                                    if (tree.getEstimate(inspectPos) != null && tree.getEstimate(inspectPos) == 0) {
                                        treeFound = true;
                                    }
                                }
                                if (!treeFound) {
                                    Tree otherTree = new Tree(inspectPos, world);
                                    nearbyTrees.add(otherTree);
                                }
                            }
                            continue;
							} else if (main && log && (leafStep > 0 || dy < 0) && !estimatedTree.containsKey(inspectPos)
									&& isTrunk && isLog(inspectPos.add(0, 1, 0)) && config.Trunk_Radius() == 1) {
								System.out.println("[ChopDown-DEBUG] tree build aborted at " + inspectPos
										+ " (other tree not cut through)");
								estimatedTree.clear();
								queue.clear();
								failedToBuild = true;
								return;
							}

                        /*
                         * If a log but next to a solid none tree block then fail to chop (avoids 99% of
                         * cases of issues building with logs in houses)
                         * 
                         * The yMatch check (log below the chop point that still has logs above it)
                         * only applies to thin trunks: thick trunks (trunk radius > 1) like the
                         * natura redwood keep other pillars standing at the chop level, so it must
                         * not abort the chop or thick trees can never be felled
                         */
							if (main && log && ((cantDrag(inspectPos) && !yMatch)
									|| (yMatch && logAbove && !wentUp)) && leafStep == 0) {
								if (yMatch && logAbove && !wentUp && !cantDrag(inspectPos)
										&& config.Min_cut_ratio() > 0.0) {
									// Partial cut: keep the remaining pillar in the tree and decide
									// whether the chop layer is cut through enough after the BFS
									ratioCheckNeeded = true;
									// Quick pre-check on the first hit: counting the chop layer and the
									// layer above avoids running the full BFS (which takes seconds on
									// huge trees) when the cut is obviously not deep enough
									if (!quickRatioChecked) {
										quickRatioChecked = true;
										int quickR = 0;
										int quickT = 0;
										int qr = config.Trunk_Radius();
										for (int qx = -qr; qx <= qr; qx++) {
											for (int qz = -qr; qz <= qr; qz++) {
												if (isLog(base.add(qx, 0, qz))) {
													quickR++;
												}
												if (isLog(base.add(qx, 1, qz))) {
													quickT++;
												}
											}
										}
										// Only trust the layer above as a reference layer when it still
										// holds at least as many logs as the chop layer; otherwise fall
										// through to the exact check after the full BFS
										if (quickT >= quickR && quickR > (int) Math.floor(quickT * (1.0 - config.Min_cut_ratio()))) {
											System.out.println("[ChopDown-DEBUG] tree build aborted (quick partial cut: " + quickR
													+ " of " + quickT + " logs at chop layer, allowed "
													+ (int) Math.floor(quickT * (1.0 - config.Min_cut_ratio())) + ")");
											estimatedTree.clear();
											queue.clear();
											failedToBuild = true;
											return;
										}
									}
								} else {
									System.out.println("[ChopDown-DEBUG] tree build aborted at " + inspectPos
											+ (cantDrag(inspectPos) && !yMatch ? " (blocked by solid block)"
													: " (log not cut through at chop level)"));
									if (cantDrag(inspectPos) && !yMatch) {
										sendBlockedMessage();
									}
									estimatedTree.clear();
									queue.clear();
									failedToBuild = true;
									return;
								}
							}
                        if (!yMatch || !cantDrag(inspectPos)) {
							if (log && !estimatedTree.containsKey(inspectPos)) {
								layerLogs.merge(inspectPos.getY(), 1, Integer::sum);
							}
                            addEstimateBlock(inspectPos, leafStep);
                        } else {
                            continue;
                        }
                    }
                }
            }
        }

		if (ratioCheckNeeded) {
			int baseCount = layerLogs.getOrDefault(base.getY(), 0);
			int maxCount = 0;
			for (int count : layerLogs.values()) {
				if (count > maxCount) {
					maxCount = count;
				}
			}
			int allowed = (int) Math.floor(maxCount * (1.0 - config.Min_cut_ratio()));
			if (baseCount > allowed) {
				System.out.println("[ChopDown-DEBUG] tree build aborted (partial cut: " + baseCount
						+ " of " + maxCount + " logs still at chop layer, allowed " + allowed + ")");
				estimatedTree.clear();
				queue.clear();
				failedToBuild = true;
				return;
			}
			System.out.println("[ChopDown-DEBUG] partial cut accepted: " + baseCount + " of " + maxCount
					+ " logs still at chop layer (allowed " + allowed + ")");
		}

    }

	/*
	 * The overall calculation of where the tree should end up, does not actually
	 * drop the blocks, just creates a list of movements needed to be done
	 */
	public void getDropBlocks() throws Exception {
		getPossibleTree();
		getRealisticTree();
		this.finishedCalculation = true;
	}

	/*
	 * Calculate where this block should end up in our fallen tree (pre log in leaf
	 * fall)
	 */
	/*
	 * Canopy blocks retain the legacy spreading rule. Trunk logs are planned
	 * separately as one rigid, rotated volume by planRigidTrunkTargets().
	 */
	private BlockPos repositionCanopyBlock(BlockPos pos) {
		int y = pos.getY() - base.getY();
		int x = pos.getX() - (base.getX() + fallOffset);
		int z = pos.getZ() - (base.getZ() + fallOffset);
		int changeX = fallZ * z;
		int changeZ = fallX * x;
		int normPosX = y * fallX;
		int normPosZ = y * fallZ;
		return pos.add(normPosX - (changeZ * fallX), -(changeX + changeZ), normPosZ - (changeX * fallZ));
	}

	/*
	 * A log block that belongs to the trunk itself: inside the trunk radius around
	 * the trunk centre and part of a vertical run of logs at least 4 blocks long.
	 * Canopy branches (short vertical runs) are excluded and drop flat together
	 * with the leaves.
	 */
	boolean isTrunkBlock(BlockPos pos) {
		return trunkBlockCache.computeIfAbsent(pos, this::calculateIsTrunkBlock);
	}

	private boolean calculateIsTrunkBlock(BlockPos pos) {
		int dx = pos.getX() - treeCenter.getX();
		int dz = pos.getZ() - treeCenter.getZ();
		int r = config.Trunk_Radius();
		// Chebyshev distance: the whole 2x2 cross section of a thick trunk must be
		// inside the trunk radius, not just the two pillars aligned with the axes
		if (!isLog(pos) || Math.max(Math.abs(dx), Math.abs(dz)) > r) {
			return false;
		}
		int run = 1;
		BlockPos p = pos.add(0, 1, 0);
		while (isLog(p) && run < 64) {
			run++;
			p = p.add(0, 1, 0);
		}
		p = pos.add(0, -1, 0);
		while (isLog(p) && run < 64) {
			run++;
			p = p.add(0, -1, 0);
		}
		return run >= 4;
	}

	/*
	 * Terrain support at an absolute horizontal position. Only blocks included in
	 * this tree's move plan are transparent to the scan. The retained chop layer
	 * (stump), branches outside the plan, and nearby trees remain solid support.
	 */
	private int supportHeightAt(int x, int z) {
		long key = ((long) x << 32) ^ (z & 0xffffffffL);
		Integer cached = supportHeightCache.get(key);
		if (cached != null) {
			return cached;
		}
		BlockPos p = new BlockPos(x, supportScanTop > 0 ? supportScanTop
				: Math.min(255, base.getY() + trunkMaxRelY + 1), z);
		while (p.getY() > 0) {
			IBlockState state = world.getBlockState(p);
			// The chop layer and everything below it is the retained stump. Only
			// actual planned sources above that layer are transparent to this scan.
			boolean movingTreeBlock = p.getY() > base.getY() && movingTreeBlocks.contains(p);
			// Wood floats. The topmost water block is a support surface, so a trunk or
			// a canopy block comes to rest on the surface instead of sinking to the
			// sea floor. Water is replaceable, so this has to be tested before the
			// crushable rule below or the scan walks straight through it.
			if (!movingTreeBlock && isWater(state)) {
				supportHeightCache.put(key, p.getY());
				return p.getY();
			}
			// A falling trunk crushes vegetation instead of resting on it: grass,
			// flowers, snow layers and vines are never support. isPassable covers the
			// plant materials, isReplaceable additionally covers deep snow layers.
			boolean crushable = isLeaf(p) || Tree.isLeaves(p, world)
					|| state.getBlock().isReplaceable(world, p);
			if (!movingTreeBlock && !crushable && !state.getBlock().isAir(state, world, p)
					&& !state.getBlock().isPassable(world, p)) {
				supportHeightCache.put(key, p.getY());
				return p.getY();
			}
			p = p.add(0, -1, 0);
		}
		supportHeightCache.put(key, 0);
		return 0;
	}

	/*
	 * Adds a block to the queue unless the queue already processed the block with
	 * this step value and its not still pending in the queue. Updates the blocks
	 * step value if it is lower than the currently stored value.
	 */
	public void addEstimateBlock(BlockPos pos, int step) {
		if (estimatedTree.containsKey(pos) && estimatedTree.get(pos) <= step) {
			return;
		}
		estimatedTree.put(pos, step);
		queue.remove(pos);
		queue.add(pos);
	}

	/*
	 * Get the leaf step value from an estimated tree block
	 */
	private Integer getEstimate(BlockPos pos) {
		return estimatedTree.get(pos);
	}

	@SuppressWarnings("deprecation")
	public static String blockName(BlockPos pos, World world) {
		ItemStack stack = null;
		try {
			stack = world.getBlockState(pos).getBlock().getPickBlock(world.getBlockState(pos), null, world, pos, null);
		} catch (Exception ex) {
			try {
				stack = world.getBlockState(pos).getBlock().getItem(world, pos, world.getBlockState(pos));
			} catch (Exception _) {
			}
		}
		if (stack == null) {
			return "unknown, getPickBlock and getItem not set";
		}
		return stackName(stack);
	}

	private String blockName(BlockPos pos) {
		return blockNameCache.computeIfAbsent(pos, key -> blockName(key, world));
	}

	public static String stackName(ItemStack stack) {
		try {
			ResourceLocation loc = stack.getItem().getRegistryName();
			int damageValue = stack.getItem().getDamage(stack);
			return loc.getNamespace() + ":" + loc.getPath() + ":" + String.valueOf(damageValue);
		} catch (Exception ex) {
			return "";
		}
	}

	/*
	 * Checks the blocks in the estimated tree against other trees that were found
	 * to determine if the block more likely belongs to this tree or another
	 */
	private void getRealisticTree() {
		// TEMP DEBUG: what did the BFS actually include
		TreeMap<Integer, Integer> layerLogCount = new TreeMap<>();
		HashMap<String, Integer> estPillar = new HashMap<>();
		for (java.util.Map.Entry<BlockPos, Integer> e : estimatedTree.entrySet()) {
			BlockPos p = e.getKey();
			if (isLog(p)) {
				estPillar.merge(p.getX() + "," + p.getZ(), 1, Integer::sum);
				layerLogCount.merge(p.getY(), 1, Integer::sum);
			}
		}
		System.out.println("[ChopDown-DEBUG] est pillars=" + estPillar + " layers=" + layerLogCount);
		estimatedTreeQueue = new LinkedList<BlockPos>(estimatedTree.keySet());
		LinkedList<BlockPos> realisticTree = new LinkedList<BlockPos>();
		while (!estimatedTreeQueue.isEmpty()) {

			BlockPos from = estimatedTreeQueue.pollFirst();
			boolean mine = true;
			int leafStep = estimatedTree.get(from);
			int distance = horizontalDistanceSquared(treeCenter, from);
			if (distance > config.Radius() * config.Radius() || leafStep >= config.Leaf_limit()) {
				continue;
			}
			for (Tree otherTree : nearbyTrees) {
				if (otherTree.myBlock(from, distance, leafStep)) {
					mine = false;
					break;
				}
			}
			if (mine && !base.equals(from)) {
				if (isLog(from) && (from.getY() == base.getY() + 1 || from.getY() == base.getY() + 2)
						&& ((fallZ != 0 && (isLog(from.add(1, 0, 0)) || isLog(from.add(-1, 0, 0))))
								|| (fallX != 0 && (isLog(from.add(0, 0, 1)) || isLog(from.add(0, 0, -1)))))) {
					if (from.getX() * fallX > (fallOffset + base.getX()) * fallX) {
						fallOffset = from.getX() - base.getX();
					} else if (from.getZ() * fallZ > (fallOffset + base.getZ()) * fallZ) {
						fallOffset = from.getZ() - base.getZ();
					}
				}
				realisticTree.add(from);
			}
		}
		// The shape wall rests on the stump, its height is the trunk cross section
		// along the fall axis: find the minimum fall-axis offset of the trunk logs
		// before computing any drop positions
		int trunkCount = 0;
		int maxRelY = 0;
		for (BlockPos from : realisticTree) {
			if (!isTrunkBlock(from)) {
				continue;
			}
			trunkCount++;
			int relY = from.getY() - base.getY();
			if (relY > maxRelY) {
				maxRelY = relY;
			}
		}
		trunkMaxRelY = maxRelY;
		trunkAnimated = trunkCount <= 100;
		// The rigid planner computes the center-line pitch after it knows the actual
		// trunk footprint. Keep this value neutral until then.
		trunkPitch = 0.0;

		LinkedList<BlockPos> trunkBlocks = new LinkedList<>();
		LinkedList<BlockPos> canopyBlocks = new LinkedList<>();
		while (!realisticTree.isEmpty()) {
			BlockPos from = realisticTree.pollFirst();
			if (isTrunkBlock(from)) {
				trunkBlocks.add(from);
			} else {
				canopyBlocks.add(from);
			}
		}
		movingTreeBlocks.clear();
		movingTreeBlocks.addAll(trunkBlocks);
		movingTreeBlocks.addAll(canopyBlocks);
		supportHeightCache.clear();
		// Trunk coordinates are authoritative. Canopy may yield vertically if it
		// happens to target the same cell, never the other way around.
		planRigidTrunkTargets(trunkBlocks);
		if (failedToBuild) {
			return;
		}
		for (BlockPos from : canopyBlocks) {
			BlockPos to = repositionCanopyBlock(from);
			while (fallingBlocks.containsKey(to)) {
				to = to.add(0, 1, 0);
			}
			TreeMovePair pair = new TreeMovePair(from, to, this);
			fallingBlocks.put(pair.to, pair);
		}
		// Canopy logs still swap down through pending leaves so they do not land on
		// top of them. Rigid trunk targets are never swapped.
		pushLogsThroughPendingLeaves();
		fallingBlocksList = new LinkedList<>(fallingBlocks.keySet());
		fallingBlocksList.sort(new AxisComparer(DirectionSort.UP));
		// TEMP DEBUG: shape statistics
		int leafCount = 0, nonTrunkLogs = 0, trunkSample = 0, trunkTotal = 0;
		HashMap<String, Integer> pillarCount = new HashMap<>();
		HashMap<String, BlockPos> pillarFirstTo = new HashMap<>();
		BlockPos firstTrunkTo = null, lastTrunkTo = null;
		for (TreeMovePair pair : fallingBlocks.values()) {
			if (pair.trunk) {
				trunkTotal++;
				String pillar = pair.from.getX() + "," + pair.from.getZ();
				pillarCount.merge(pillar, 1, Integer::sum);
				pillarFirstTo.putIfAbsent(pillar, pair.to);
				if (firstTrunkTo == null) {
					firstTrunkTo = pair.to;
				}
				lastTrunkTo = pair.to;
				if (trunkSample < 10) {
					System.out.println("[ChopDown-DEBUG] trunkBlock from=" + pair.from + " to=" + pair.to);
					trunkSample++;
				}
			} else {
				leafCount++;
				if (!pair.leaves) {
					nonTrunkLogs++;
				}
			}
		}
		System.out.println("[ChopDown-DEBUG] shape: base=" + base + " treeCenter=" + treeCenter + " fallX=" + fallX
				+ " fallZ=" + fallZ + " trunk=" + trunkTotal + " leaf=" + leafCount + " nonTrunkLogs=" + nonTrunkLogs
				+ " pitch=" + trunkPitch + " animated=" + trunkAnimated + " pillars=" + pillarCount + " pillarTos="
				+ pillarFirstTo + " firstTo=" + firstTrunkTo + " lastTo=" + lastTrunkTo);
	}

	/*
	 * Rotate the vertical trunk volume ninety degrees around the root axis.
	 *
	 * The geometry, the resting attitude and the per-log decisions all live in the
	 * Minecraft free shape package so they can be exercised by the offline shape
	 * tests. This method only translates between BlockPos and ShapePos and turns
	 * the resulting decisions into move pairs.
	 *
	 * No individual trunk target is shifted to avoid another trunk target. A
	 * collision means the plan is wrong and is reported rather than silently
	 * tearing a bark ring apart.
	 */
	private void planRigidTrunkTargets(LinkedList<BlockPos> trunkBlocks) {
		if (trunkBlocks.isEmpty()) {
			return;
		}
		ArrayList<ShapePos> sources = new ArrayList<>(trunkBlocks.size());
		for (BlockPos pos : trunkBlocks) {
			sources.add(new ShapePos(pos.getX(), pos.getY(), pos.getZ()));
		}
		// The actual trunk profile: a trunk that is wide at the root and narrow
		// further up keeps that real shape, holes stay holes and a missing layer
		// stays visible as an empty section.
		TrunkShape shape = TrunkShape.build(sources, fallX, fallZ, base.getY());
		// A beam that pitches up ends well above the standing tree, so the terrain
		// scan has to start above that or a tall wall would never be seen at all.
		supportScanTop = Math.min(255, base.getY() + trunkMaxRelY + shape.footprintReach() + 2);
		TrunkSolver.Solution solution = TrunkSolver.solve(shape, this::supportHeightAt,
				this::canEmbedInto);
		trunkPitch = solution.pitch();
		trunkEmbedded.clear();
		for (ShapePos cell : solution.embedded()) {
			trunkEmbedded.add(toBlockPos(cell));
		}
		TrunkPlanner.Plan plan = TrunkPlanner.plan(solution,
				target -> isTrunkTargetBlocked(toBlockPos(target)));
		if (plan.hasDuplicateTarget()) {
			System.out.println(
					"[ChopDown-DEBUG] rigid trunk target collision at " + plan.duplicateTarget());
			failedToBuild = true;
			return;
		}

		HashMap<BlockPos, TreeMovePair> planned = new HashMap<>();
		for (TrunkPlanner.Decision decision : plan.decisions()) {
			BlockPos from = toBlockPos(decision.source());
			BlockPos target = toBlockPos(decision.target());
			if (fallingBlocks.containsKey(target)) {
				System.out.println("[ChopDown-DEBUG] rigid trunk target collision from=" + from
						+ " to=" + target);
				failedToBuild = true;
				return;
			}
			TreeMovePair pair = new TreeMovePair(from, target, this);
			if (decision.placement() == TrunkPlanner.Placement.DROP_AS_ITEM) {
				// Never got past an obstacle: it comes down at the point the trunk
				// actually leans against, not on the far side of it.
				pair.dropAsItem = true;
				pair.dropAt = toBlockPos(decision.dropAt());
			} else if (decision.placement() == TrunkPlanner.Placement.SEVERED) {
				// Past the break this section is no longer part of the rigid beam.
				pair.severed = true;
			}
			planned.put(target, pair);
		}

		fallingBlocks.putAll(planned);
		// Only cells the surviving rigid beam actually occupies are reserved against
		// canopy or severed logs sinking into them.
		for (TreeMovePair pair : planned.values()) {
			if (!pair.dropAsItem && !pair.severed) {
				trunkTargets.add(pair.to);
			}
		}
		System.out.println("[ChopDown-DEBUG] rigidTrunk rootSupport=" + solution.rootSupport()
				+ " advance=" + shape.rootAdvance() + " minHeight=" + shape.minHeight()
				+ " vMin=" + shape.verticalMin() + " thick=" + shape.thickness()
				+ " sections=" + shape.sectionCount() + " emptySections=" + shape.emptySteps()
				+ " pitch=" + solution.pitch() + " datum=" + solution.datum()
				+ " rootLift=" + solution.rootLift() + " com=" + solution.centreOfMass()
				+ " outerContact=" + solution.outermostContact()
				+ " tipRounds=" + solution.tipRounds()
				+ " momentSnap=" + solution.snapAtStep()
				+ " overhangSnap=" + solution.overhangSnap()
				+ " blockedSnap=" + plan.blockedSnap() + " snapStep=" + plan.snapStep()
				+ " clamped=" + solution.clampedColumns() + " contact=" + solution.contact()
				+ " embedded=" + solution.embedded().size()
				+ " fibres=" + plan.fibres() + " occluded=" + plan.items()
				+ " severed=" + plan.severed() + " segPitch=" + plan.severedPitch()
				+ " segDatum=" + plan.severedDatum() + " targets=" + plan.decisions().size());
	}

	private static BlockPos toBlockPos(ShapePos pos) {
		return new BlockPos(pos.x(), pos.y(), pos.z());
	}

	@Override
	public void run() {
		try {
			this.getDropBlocks();
		} catch (Exception e) {
			this.failedToBuild = true;
			System.out.println("[ChopDown-DEBUG] tree calculation failed: " + e);
		}
	}

	/*
	 * Iterates through blocks waiting to drop
	 */
	public boolean dropBlocks() {
		startedDropping = true;
		// breakStackedPendingLeaves touches the world (dropDrops spawns items,
		// setBlockState writes blocks), so it must run on the server thread, not on
		// the calculation thread that builds the tree. It runs before any source is
		// cleared so the leaves it removes still drop their items.
		if (!pendingLeavesBroken) {
			pendingLeavesBroken = true;
			breakStackedPendingLeaves();
		}
		clearTrunkSources();
		int blocksRemaining = Config.maxDropsPerTickPerTree;
		BlockPos pos;
		int size = fallingBlocksList.size();
		for (int i = 0; i < size; i++) {
			pos = fallingBlocksList.getFirst();
			TreeMovePair pair = fallingBlocks.get(pos);
			fallingBlocksList.removeFirst();
			if (!drop(pair, fallingBlocks.size() > Config.maxFallingBlockBeforeManualMove)) {
				// not finished moving
				fallingBlocksList.add(pos);
			}
			blocksRemaining--;
			if (blocksRemaining <= 0 && !fallingBlocksList.isEmpty()) {
				return false;
			}
		}
		if (!fallingBlocksList.isEmpty()) {
			return false;
		}
		System.out.println("[ChopDown-DEBUG] dropDone trunkPlaced=" + trunkPlaced + " trunkDirect=" + trunkDirect
				+ " trunkSevered=" + trunkSevered + " listEmpty=" + fallingBlocksList.isEmpty());
		return true;
	}

	private static long lastBlockedMessageTime = 0;

	/*
	 * Tell the player why the tree cannot be felled when it is stuck against a
	 * solid block. Throttled to avoid chat spam on consecutive breaks. Runs on the
	 * calculation thread, so the message is scheduled on the server thread.
	 */
	private void sendBlockedMessage() {
		long now = System.currentTimeMillis();
		if (now - lastBlockedMessageTime < 2000 || player == null
				|| world.getMinecraftServer() == null) {
			return;
		}
		lastBlockedMessageTime = now;
		world.getMinecraftServer().addScheduledTask(() -> player
				.sendMessage(new TextComponentString("Tree is blocked by a solid block")));
	}

	/*
	 * Is the block more likely to be yours or mine?
	 */
	public boolean myBlock(BlockPos pos, int yourDistance, int yourStepValue) {
		// TODO check if block type matches main types

		Integer step = estimatedTree.get(pos);
		if (step == null || step > yourStepValue) {
			return false;
		}
		if (step == yourStepValue) {
			return horizontalDistanceSquared(base, pos) < yourDistance;
		}
		return true;
	}

	/*
	 * Checks to see if the block is on the given axis
	 */
	private Boolean isAxis(IBlockState state, IProperty<?> property, String axis) {
		return ((Enum<?>) (state.getProperties().get(property))).name().equalsIgnoreCase(axis);
	}

	/*
	 * Sets the blocks axis by iterating through the property values.
	 */
	private IBlockState setAxis(IBlockState state, IProperty<?> property, String axis) {
		int i = 10;
		while (i > 0 && !isAxis(state, property, axis)) {
			i--;
			state = state.cycleProperty(property);
		}
		return state;
	}

	/*
	 * Trys to rotate the log along the axis given
	 */
	public IBlockState rotateLog(World world, IBlockState state) {
		IProperty<?> foundProp = null;
		for (net.minecraft.block.properties.IProperty<?> prop : state.getProperties().keySet()) {
			if (prop.getName().equals("axis")) {
				foundProp = prop;
			}
		}
		if (foundProp == null) {
			return state;
		}
		if (axis == EnumFallAxis.X) {
			if (isAxis(state, foundProp, "Y")) {
				state = setAxis(state, foundProp, "Z");
			} else if (isAxis(state, foundProp, "Z")) {
				state = setAxis(state, foundProp, "Y");
			}
		} else {
			if (isAxis(state, foundProp, "Y")) {
				state = setAxis(state, foundProp, "X");
			} else if (isAxis(state, foundProp, "X")) {
				state = setAxis(state, foundProp, "Y");
			}
		}
		return state;
	}

	public static void dropDrops(BlockPos pos, BlockPos dropPos, IBlockState state, World world) {
		// Do drops at location)
		for (ItemStack stacky : state.getBlock().getDrops(world, pos, state, 0)) {
			EntityItem entityitem = new EntityItem(world, dropPos.getX(), dropPos.getY(), dropPos.getZ(), stacky);
			entityitem.setDefaultPickupDelay();
			world.spawnEntity(entityitem);
		}
	}

	/*
	 * Drops a block in the world (basically moves it if it can, does block drop if
	 * it can't, handles falling entity and calculated drop) Also handles debug
	 * configs.
	 */
	private int nonTrunkLogged = 0;
	private int dropToItemLogged = 0;
	private int manualClearLogged = 0;
	private int vanishedLogged = 0;
	private int trunkPlaced = 0;
	private int trunkDirect = 0;
	private int directPlaceLogged = 0;
	private int severedLogged = 0;
	private int trunkSevered = 0;
	public static int moveReplaceLogged = 0;

	/*
	 * A rigid trunk is written back as one body, so every trunk source must be air
	 * before the first trunk target is placed. Canopy sources that a trunk target
	 * overlaps are cleared too: those pairs keep their captured state, so they
	 * still move their own block instead of picking up a freshly placed trunk log.
	 */
	private void clearTrunkSources() {
		if (trunkSourcesCleared) {
			return;
		}
		trunkSourcesCleared = true;
		// Bed the trunk in first: the cells it settles into have to be gone before
		// any trunk log is written, or the log would land on the soil it displaces.
		// The plan was built earlier, so every cell is re-checked against the world
		// as it is now; anything that changed is left alone and the log above it
		// falls back to the runtime canTrunkOccupy check in drop().
		int carved = 0;
		int refused = 0;
		for (BlockPos pos : trunkEmbedded) {
			if (!canEmbedInto(pos.getX(), pos.getY(), pos.getZ())) {
				refused++;
				continue;
			}
			// Displaced terrain is dropped rather than deleted: plain stone and soil
			// may well have been placed by the player.
			dropDrops(pos, pos, world.getBlockState(pos), world);
			world.setBlockState(pos, Blocks.AIR.getDefaultState());
			carved++;
		}
		if (carved > 0 || refused > 0) {
			System.out.println("[ChopDown-DEBUG] trunkEmbed carved=" + carved + " refused="
					+ refused + " planned=" + trunkEmbedded.size());
		}
		LinkedList<TreeMovePair> trunkPairs = new LinkedList<>();
		HashMap<BlockPos, TreeMovePair> canopyBySource = new HashMap<>();
		for (TreeMovePair pair : fallingBlocks.values()) {
			if (pair.trunk) {
				trunkPairs.add(pair);
			} else {
				canopyBySource.put(pair.from, pair);
			}
		}
		for (TreeMovePair pair : trunkPairs) {
			// Tile entities are rare on logs, but resolve them before clearing the
			// source so a rigid bulk move cannot lose their data.
			pair.getTile();
			world.setBlockState(pair.from, Blocks.AIR.getDefaultState());
			pair.sourceCleared = true;
		}
		for (TreeMovePair pair : trunkPairs) {
			TreeMovePair blocked = canopyBySource.get(pair.to);
			if (blocked == null || blocked.sourceCleared) {
				continue;
			}
			blocked.getTile();
			world.setBlockState(blocked.from, Blocks.AIR.getDefaultState());
			blocked.sourceCleared = true;
		}
	}

	/*
	 * A falling trunk crushes air, passable blocks (grass, flowers, snow layers)
	 * and leaves. Anything else stops it, and that log drops as an item instead of
	 * being placed.
	 *
	 * Deliberately NOT keyed on Config.logs: that list is the union of every tree
	 * configuration of every mod, so a player built wall made of redwood planks or
	 * logs would be treated as crushable and get eaten by the falling trunk.
	 */
	public static boolean canTrunkOccupy(BlockPos pos, World world) {
		if (pos.getY() <= 0) {
			return false;
		}
		IBlockState state = world.getBlockState(pos);
		Block block = state.getBlock();
		return block.isAir(state, world, pos) || block.isPassable(world, pos)
				|| block.isReplaceable(world, pos) || isLeaves(pos, world);
	}

	/*
	 * Blocked for the purpose of trunk planning. This tree's own blocks are still
	 * standing while the plan is built and will move away, so they never block it.
	 */
	private boolean isTrunkTargetBlocked(BlockPos target) {
		if (target.getY() <= 0 || target.getY() > 255) {
			return true;
		}
		if (movingTreeBlocks.contains(target)) {
			return false;
		}
		// Cells the trunk beds into are displaced before it is placed, so they do not
		// stop it either.
		if (trunkEmbedded.contains(target)) {
			return false;
		}
		return !canTrunkOccupy(target, world);
	}

	/*
	 * Terrain a redwood sized trunk is allowed to displace so it beds into the
	 * ground instead of lying on top of it.
	 *
	 * A strict whitelist, deliberately not a hardness or isReplaceable test: those
	 * would let a falling trunk eat ore, a player built wall, a chest or a machine.
	 * Only plain soil and plain stone give way, and only as deep and as much as
	 * TrunkSolver.EMBED_DEPTH_MAX and EMBED_BUDGET allow.
	 */
	private boolean canEmbedInto(int x, int y, int z) {
		if (y <= 0) {
			return false;
		}
		BlockPos pos = new BlockPos(x, y, z);
		IBlockState state = world.getBlockState(pos);
		Block block = state.getBlock();
		boolean soil = block == Blocks.DIRT || block == Blocks.GRASS;
		// Plain stone only. The granite, diorite and andesite variants share this
		// block but read as decoration, and every other rock is off the list.
		boolean plainStone = block == Blocks.STONE && block.getMetaFromState(state) == 0;
		if (!soil && !plainStone) {
			return false;
		}
		// Nothing on the list carries a tile entity, so if one is here the block is
		// not what it claims to be.
		return world.getTileEntity(pos) == null;
	}

	private boolean drop(TreeMovePair pair, Boolean UseSolid) {
		if (!pair.sourceCleared && !(isLog(pair.from) || isLeaf(pair.from))) {
			if (vanishedLogged < 20) {
				System.out.println("[ChopDown-DEBUG] vanished from=" + pair.from + " to=" + pair.to + " leaves="
						+ pair.leaves + " trunk=" + pair.trunk + " block="
						+ world.getBlockState(pair.from).getBlock());
				vanishedLogged++;
			}
			return true;
		}
		if (!pair.leaves && !pair.trunk && nonTrunkLogged < 8) {
			System.out.println("[ChopDown-DEBUG] branchLog from=" + pair.from + " to=" + pair.to + " useSolid="
					+ UseSolid);
			nonTrunkLogged++;
		}
		PersonalConfig playerConfig = Config.getPlayerConfig(player.getUniqueID());
		// Turn the tree in to glass if set as don't drop;
		if (playerConfig.makeGlass && playerConfig.dontFell) {
			if (pair.trunk || isLog(pair.from)) {
				world.setBlockState(pair.from, Blocks.STAINED_GLASS.getStateFromMeta(1));
			} else {
				world.setBlockState(pair.from, Blocks.STAINED_GLASS.getStateFromMeta(2));
			}
			return true;
		}
		// Pair state is captured during planning, before a rigid trunk is bulk-cleared.
		// It already includes the 90-degree log-axis rotation for logs.
		IBlockState state = pair.state;
		// Trunk logs keep the tree shape: they either fall from the original height
		// of the log down onto the computed spot (small trees, visible animation) or
		// are placed directly (large trees, animating hundreds of blocks would look
		// like a lag spike)
		if (pair.trunk) {
			// Planned occlusion: this section is behind a wall or inside a cliff, so it
			// drops as an item. The runtime check is a safety net for a world that
			// changed after the plan was built.
			if (pair.dropAsItem || !canTrunkOccupy(pair.to, world)) {
				// Planned occlusion drops at the contact point the fibre stopped on.
				BlockPos dropAt = pair.dropAt != null ? pair.dropAt : pair.to;
				if (dropToItemLogged < 20) {
					System.out.println("[ChopDown-DEBUG] trunkBlocked from=" + pair.from + " to=" + pair.to
							+ " dropAt=" + dropAt + " planned=" + pair.dropAsItem + " at="
							+ world.getBlockState(pair.to).getBlock());
					dropToItemLogged++;
				}
				dropDrops(pair.from, dropAt, state, world);
				if (!pair.sourceCleared) {
					world.setBlockState(pair.from, Blocks.AIR.getDefaultState());
				}
				return true;
			}
			if (pair.severed) {
				// The severed segment was re-attituded to its own resting position by
				// the planner, so it is placed directly as part of the rigid body
				// instead of falling loose and scattering. The runtime occupancy
				// check above is the safety net: if the world changed and the target
				// is no longer free, that log drops as an item.
				if (severedLogged < 20) {
					System.out.println("[ChopDown-DEBUG] severedPlace from=" + pair.from + " to=" + pair.to);
					severedLogged++;
				}
				trunkSevered++;
				// Fall through to the normal direct-place / animate path below.
			}
			if (!trunkAnimated || pair.from.getY() - base.getY() > 10) {
				// Large trees and the canopy sections of the trunk are placed directly:
				// animating them would leave falling blocks in the path of the canopy
				if (directPlaceLogged < 20) {
					System.out.println("[ChopDown-DEBUG] directPlace from=" + pair.from + " to=" + pair.to
							+ " below=" + world.getBlockState(pair.to.down()).getBlock() + " at="
							+ world.getBlockState(pair.to));
					directPlaceLogged++;
				}
				if (!pair.sourceCleared) {
					world.setBlockState(pair.from, Blocks.AIR.getDefaultState());
				}
				pair.from = pair.to;
				pair.moved = true;
				pair.move();
				trunkDirect++;
				trunkPlaced++;
				return true;
			}
			// Animated trunk section: spawn a visible falling entity from the original
			// height of the log. The entity falls with vanilla gravity but is placed
			// EXACTLY at the computed landing spot when its y reaches the target,
			// so the trunk keeps its straight line even across a chasm.
			if (!pair.sourceCleared) {
				world.setBlockState(pair.from, Blocks.AIR.getDefaultState());
			}
			// Always start above the planned landing height, even when the target ends
			// up higher than the source (uphill support line or a lifted beam).
			double spawnY = Math.max(pair.from.getY(), pair.to.getY() + 1) + 0.5;
			TargetedFallingBlock fallingBlock = new TargetedFallingBlock(world, pair.to.getX() + 0.5,
					spawnY, pair.to.getZ() + 0.5, state, pair.getTile(), true, pair.to);
			fallingBlock.fallTime = 1;
			world.spawnEntity(fallingBlock);
			trunkPlaced++;
			return true;
		}
		// If the target block is not passable or the source block is leaves and the
		// config is set to break leaves then do drops and state finished
		if ((!CanMoveTo(pair.to,!pair.leaves) && !pair.moved) || (isLeaf(pair.from) && Config.breakLeaves)) {
			if (dropToItemLogged < 20) {
				System.out.println("[ChopDown-DEBUG] dropToItem from=" + pair.from + " to=" + pair.to + " leaves="
						+ pair.leaves + " trunk=" + pair.trunk + " canMove=" + CanMoveTo(pair.to, !pair.leaves));
				dropToItemLogged++;
			}
			// Do drops at location
			dropDrops(pair.from, pair.to, state, world);
			if (!pair.sourceCleared) {
				world.setBlockState(pair.from, Blocks.AIR.getDefaultState());
			}
			return true;
		} else if (!CanMoveTo(pair.to,!pair.leaves)) {
			return true;
		}
		// Can move to this block, set the source block to air, set the from block as to
		// and state that we moved
		if (!pair.sourceCleared) {
			world.setBlockState(pair.from, Blocks.AIR.getDefaultState());
		}
		pair.from = pair.to;
		pair.moved = true;

		if (playerConfig.dontFell) {
			pair.move();
		} else if (!pair.leaves && !pair.trunk) {
			// Non-trunk logs (canopy branches) drop straight down through leaves to
			// the ground: falling entities would land on the still standing canopy
			// and be left floating in the air when the canopy falls away
			ManuallyDrop(pair, state);
		} else {
			if (!UseSolid && !restsOnWater(pair.to)) {
				// Use falling entities
				clearLeafLandingPath(pair);
				EntityFallingBlock fallingBlock = new EntityFallingBlock(world, pair.to.getX() + 0.5,
						pair.to.getY() + 0.5, pair.to.getZ() + 0.5, state, pair.getTile(), !pair.leaves);
				fallingBlock.setEntityBoundingBox(new AxisAlignedBB(pair.to.add(0, 0, 0), pair.to.add(1, 1, 1)));
				fallingBlock.fallTime = 1;
				world.spawnEntity(fallingBlock);
			} else {
				ManuallyDrop(pair, state);
			}
		}
		return true;
	}

	private void ManuallyDrop(TreeMovePair pair, IBlockState state) {
		// Move large trees to final resting place
		while (CanMoveThroughBelow(pair)) {
			pair.to = pair.to.add(0, -1, 0);
			breakLeafAt(pair.to);
			if(!isAir(pair.to)) {
				if (manualClearLogged < 20) {
					System.out.println("[ChopDown-DEBUG] manualClear from=" + pair.from + " cleared=" + pair.to
							+ " block=" + world.getBlockState(pair.to).getBlock() + " leaves=" + pair.leaves
							+ " trunk=" + pair.trunk);
					manualClearLogged++;
				}
				IBlockState state2 = world.getBlockState(pair.to);
				Tree.dropDrops(pair.from, pair.to, world.getBlockState(pair.to),world);
				world.setBlockState(pair.to,Blocks.AIR.getDefaultState() );
			}
		}
		pair.move();
	}

	/*
	 * Ported from the 1.21 version: logs swap targets with pending leaves below
	 * them so they land underneath instead of stacking on top of each other
	 */
	private void pushLogsThroughPendingLeaves() {
		boolean moved;
		do {
			moved = false;
			LinkedList<BlockPos> logPositions = new LinkedList<BlockPos>();
			for (TreeMovePair pair : fallingBlocks.values()) {
				if (!pair.leaves && !pair.trunk) {
					logPositions.add(pair.to);
				}
			}
			logPositions.sort(new AxisComparer(DirectionSort.DOWN));
			for (BlockPos pos : logPositions) {
				TreeMovePair pair = fallingBlocks.get(pos);
				if (pair == null || pair.leaves || pair.trunk) {
					continue;
				}
				moved = pushLogThroughPendingLeaves(pair) || moved;
			}
		} while (moved);
	}

	private boolean pushLogThroughPendingLeaves(TreeMovePair logPair) {
		boolean moved = false;
		TreeMovePair leafPair = fallingBlocks.get(logPair.to.add(0, -1, 0));
		while (leafPair != null && leafPair.leaves) {
			swapTargets(logPair, leafPair);
			moved = true;
			leafPair = fallingBlocks.get(logPair.to.add(0, -1, 0));
		}
		return moved;
	}

	private void swapTargets(TreeMovePair first, TreeMovePair second) {
		BlockPos firstTo = first.to;
		BlockPos secondTo = second.to;
		fallingBlocks.remove(firstTo);
		fallingBlocks.remove(secondTo);
		first.to = secondTo;
		second.to = firstTo;
		fallingBlocks.put(first.to, first);
		fallingBlocks.put(second.to, second);
	}

	/*
	 * Ported from the 1.21 version: pending leaves that end up stacked on top of
	 * each other are broken and drop instead of falling
	 */
	private void breakStackedPendingLeaves() {
		LinkedList<BlockPos> leafPositions = new LinkedList<BlockPos>();
		for (BlockPos pos : fallingBlocksList) {
			TreeMovePair pair = fallingBlocks.get(pos);
			if (pair != null && pair.leaves) {
				leafPositions.add(pos);
			}
		}
		leafPositions.sort(new AxisComparer(DirectionSort.DOWN));
		for (BlockPos pos : leafPositions) {
			TreeMovePair pair = fallingBlocks.get(pos);
			if (pair == null || !pair.leaves) {
				continue;
			}
			breakPendingLeaf(pos.add(0, -1, 0));
		}
	}

	/*
	 * Ported from the 1.21 version: clear the landing path of a falling block so
	 * it does not land on leaves, breaking them before the entity is spawned
	 */
	private void clearLeafLandingPath(TreeMovePair pair) {
		// Start at the landing position itself: the landing spot can be inside another
		// tree's canopy, and non-contiguous leaf blocks below are not guaranteed to be
		// cleared by the pre-scan, so the falling entity also breaks leaves on the way down.
		BlockPos below = pair.to;
		while (below.getY() > 0) {
			boolean cleared = false;
			if (Tree.isLeaves(below, world)) {
				Tree.dropDrops(below, below, world.getBlockState(below), world);
				world.setBlockState(below, Blocks.AIR.getDefaultState());
				cleared = true;
			}
			if (breakPendingLeaf(below)) {
				cleared = true;
			}
			if (!cleared) {
				return;
			}
			below = below.add(0, -1, 0);
		}
	}

	private boolean breakPendingLeaf(BlockPos pos) {
		TreeMovePair pair = fallingBlocks.get(pos);
		if (pair == null || !pair.leaves || !fallingBlocksList.remove(pos)) {
			return false;
		}
		fallingBlocks.remove(pos);
		if (pair.sourceCleared) {
			// The source was already emptied for a rigid trunk target, drop the
			// captured state instead of reading air back out of the world
			Tree.dropDrops(pair.from, pair.to, pair.state, world);
		} else if (Tree.isLeaves(pair.from, world)) {
			Tree.dropDrops(pair.from, pair.to, world.getBlockState(pair.from), world);
			world.setBlockState(pair.from, Blocks.AIR.getDefaultState());
		}
		return true;
	}

	private void breakWorldLeaf(BlockPos pos) {
		if (Tree.isLeaves(pos, world)) {
			Tree.dropDrops(pos, pos, world.getBlockState(pos), world);
			world.setBlockState(pos, Blocks.AIR.getDefaultState());
		}
	}

	private void breakLeafAt(BlockPos pos) {
		if (!breakPendingLeaf(pos)) {
			breakWorldLeaf(pos);
		}
	}

	private boolean isPendingLeaf(BlockPos pos) {
		TreeMovePair pair = fallingBlocks.get(pos);
		return pair != null && pair.leaves && fallingBlocksList.contains(pos);
	}

	/*
	 * Water is a support surface because wood floats. Lava deliberately is not: a
	 * tree felled into lava still burns up instead of resting on it.
	 */
	private static boolean isWater(IBlockState state) {
		return state.getMaterial() == Material.WATER;
	}

	/*
	 * Whether a block released at pos would come to rest on water. A vanilla
	 * falling block entity ignores water entirely and sinks to the sea floor, so
	 * those are routed through ManuallyDrop instead, which stops at the surface.
	 */
	private boolean restsOnWater(BlockPos pos) {
		BlockPos p = pos;
		while (p.getY() > 0) {
			BlockPos below = p.add(0, -1, 0);
			if (isWater(world.getBlockState(below))) {
				return true;
			}
			if (!CanMoveTo(below, true) && !Tree.isLeaves(below, world) && !isPendingLeaf(below)) {
				return false;
			}
			p = below;
		}
		return false;
	}

	private boolean CanMoveThroughBelow(TreeMovePair pair) {
		BlockPos below = pair.to.add(0, -1, 0);
		// A cell reserved for the rigid trunk is never entered by a canopy block:
		// the trunk is planned first and its continuity must not be broken by a
		// branch log that happens to sink into it first.
		if (trunkTargets.contains(below)) {
			return false;
		}
		// Wood floats: a block walking down comes to rest on the water surface.
		if (isWater(world.getBlockState(below))) {
			return false;
		}
		return CanMoveTo(below, !pair.leaves) || Tree.isLeaves(below, world) || isPendingLeaf(below);
	}

	private boolean CanMoveTo(BlockPos pos, Boolean log) {
		return (isAir(pos) || isPassable(pos) || (log && Tree.isLeaves(pos, world))) && pos.getY() > 0;
	}

	/*
	 * Gets the squared distance on the x-z plane only (comparing squared distances
	 * is equivalent to comparing distances and avoids the sqrt)
	 */
	private int horizontalDistanceSquared(BlockPos pos1, BlockPos pos2) {
		int diffX = Math.abs(pos1.getX() - pos2.getX());
		int diffZ = Math.abs(pos1.getZ() - pos2.getZ());
		return diffX * diffX + diffZ * diffZ;
	}

	/*
	 * If min vertical logs is 0 it only checks for the log being on a solid block,
	 * otherwise it also checks the log is vertically surrounded by the given number
	 * of blocks, this is useful for some BOP trees that have hollow centres or that
	 * get built floating in water.
	 */
	public static final Boolean isTrunk(BlockPos pos, World world, TreeConfiguration config) {

		// Normal tree check, requires the tree to be sat on a solid block
		BlockPos choppedPos = pos;
		boolean log = true;
		while (log) {
			pos = pos.add(0, -1, 0);
			if (!config.isLog(blockName(pos, world))) {
				log = false;
				if (!isDraggable(world, pos, config)) {
					return true;
				}
			}
		}

		if (config.Min_vertical_logs() == 0) {
			return false;
		} else {
			// Count the continuous vertical log run around the chopped position.
			// Counting from the chopped position (not from the first non log block
			// below it) keeps the check working when the blocks below the chop point
			// were already removed.
			int below = 0;
			for (int i = 1; i < config.Min_vertical_logs(); i++) {
				if (!config.isLog(blockName(choppedPos.add(0, -i, 0), world))) {
					break;
				}
				below++;
			}
			int above = 0;
			for (int i = 1; i < config.Min_vertical_logs(); i++) {
				if (!config.isLog(blockName(choppedPos.add(0, i, 0), world))) {
					break;
				}
				above++;
			}
			return (1 + below + above) >= config.Min_vertical_logs();
		}
	}

	/*
	 * Cached instance version of isTrunk for the tree build, the world is not
	 * modified while the tree is being calculated so the result is stable
	 */
	private boolean isTrunk(BlockPos pos) {
		return trunkCache.computeIfAbsent(pos, this::calculateIsTrunk);
	}

	private boolean calculateIsTrunk(BlockPos pos) {
		BlockPos choppedPos = pos;
		boolean log = true;
		BlockPos inspect = pos;
		while (log) {
			inspect = inspect.add(0, -1, 0);
			if (!isLog(inspect)) {
				log = false;
				if (!isDraggable(inspect)) {
					return true;
				}
			}
		}

		if (config.Min_vertical_logs() == 0) {
			return false;
		} else {
			int below = 0;
			for (int i = 1; i < config.Min_vertical_logs(); i++) {
				if (!isLog(choppedPos.add(0, -i, 0))) {
					break;
				}
				below++;
			}
			int above = 0;
			for (int i = 1; i < config.Min_vertical_logs(); i++) {
				if (!isLog(choppedPos.add(0, i, 0))) {
					break;
				}
				above++;
			}
			return (1 + below + above) >= config.Min_vertical_logs();
		}
	}

	/*
	 * Is the block touching either air, a tree block or a passable block only on
	 * all 6 sides
	 */
	private boolean cantDrag(BlockPos pos) {
		return !isDraggable(pos.add(1, 0, 0)) || !isDraggable(pos.add(-1, 0, 0))
				|| !isDraggable(pos.add(0, 1, 0)) || !isDraggable(pos.add(0, -1, 0))
				|| !isDraggable(pos.add(0, 0, 1)) || !isDraggable(pos.add(0, 0, -1));
	}

	/*
	 * Is this specific block either a tree block, air or a passable block
	 */
	private static boolean isDraggable(World world, BlockPos pos, TreeConfiguration tree) {

		IBlockState state = world.getBlockState(pos);

		if (state.getBlock().isAir(state, world, pos) || state.getBlock().isPassable(world, pos)) {
			return true;
		}

		if (tree != null) {
			String name = blockName(pos, world);
			if (tree.isLog(name) || tree.isLeaf(name)) {
				return true;
			}
		}
		return isWood(pos, world) || isLeaves(pos, world);
	}

	/*
	 * Cached instance version of isDraggable for the tree build
	 */
	private boolean isDraggable(BlockPos pos) {
		return draggableCache.computeIfAbsent(pos, this::calculateIsDraggable);
	}

	private boolean calculateIsDraggable(BlockPos pos) {
		IBlockState state = world.getBlockState(pos);

		if (state.getBlock().isAir(state, world, pos) || state.getBlock().isPassable(world, pos)) {
			return true;
		}

		String name = blockName(pos);
		return isLog(name) || isLeaf(name) || matchesAny(name, Config.logs) || matchesAny(name, Config.leaves);
	}

	/*
	 * Is the block at this position an air block;
	 */
	public Boolean isAir(BlockPos pos) {
		return world.getBlockState(pos).getBlock().isAir(world.getBlockState(pos), world, pos);
	}

	/*
	 * /* Is the block at this position an air block;
	 */
	private Boolean isPassable(BlockPos pos) {
		return world.getBlockState(pos).getBlock().isPassable(world, pos);
	}

	/*
	 * Is the block at this position a log
	 */
	public static boolean isWood(BlockPos pos, World world) {
		return matchesAny(blockName(pos, world), Config.logs);
	}

	/*
	 * Is the block at this position a log
	 */
	public static boolean isLeaves(BlockPos pos, World world) {
		return matchesAny(blockName(pos, world), Config.leaves);
	}

	private static boolean matchesAny(String blockName, String[] blocks) {
		for (String block : blocks) {
			if (block.equals(blockName) || blockName.matches(block)) {
				return true;
			}
		}
		return false;
	}

	/*
	 * Is the block at this position leaves
	 */
	public boolean isLeaves(BlockPos pos) {
		return isLeaf(pos);
	}

	/*
	 * Class to house the falling logs and leaves
	 */
	public static class EntityFallingBlock extends TargetedFallingBlock {

		EntityFallingBlock(World worldIn, double x, double y, double z, IBlockState fallingBlockState, TileEntity tile,
				Boolean isLog) {
			super(worldIn, x, y, z, fallingBlockState, tile, isLog, null);
		}
	}

}
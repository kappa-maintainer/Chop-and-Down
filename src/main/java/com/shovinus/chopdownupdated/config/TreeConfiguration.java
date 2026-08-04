package com.shovinus.chopdownupdated.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.ArrayUtils;

public class TreeConfiguration {
	/*
	 * The horizontal radius from the trunk to check for tree members
	 */
	public int Radius() {
		return radius == 0 ? 9 : radius;
	}

	/*
	 * Maximum steps from a log a leaf can be
	 */
	public int Leaf_limit() {
		return leaf_limit == 0 ? 12 : leaf_limit;
	}

	/*
	 * Maximum steps from a log a leaf can be
	 */
	public int Trunk_Radius() {
		return trunk_radius == 0 ? 1 : trunk_radius;
	}

	public int Min_vertical_logs() {
		return min_vertical_logs;
	}

	private int radius = 9;
	private int leaf_limit = 12;
	private int trunk_radius = 1;
	private int min_vertical_logs = 0;
	private List<String> logs;
	private List<String> leaves;
	private transient String[] leaves_merged;
	private transient String[] blocks = null;

	public TreeConfiguration() {
	}
	public TreeConfiguration(int radius, int leaf_limit, int min_logs, int trunk_radius, String[] logs,
			String[] leaves) {
		this.radius = radius;
		this.leaf_limit = leaf_limit;
		this.trunk_radius = trunk_radius;
		this.logs = new ArrayList<String>(Arrays.asList(logs));
		this.leaves = new ArrayList<String>(Arrays.asList(leaves));;
		this.min_vertical_logs = min_logs;
	}
	public TreeConfiguration(int radius, int leaf_limit, int min_logs, int trunk_radius) {
		this.radius = radius;
		this.leaf_limit = leaf_limit;
		this.trunk_radius = trunk_radius;		
		this.min_vertical_logs = min_logs;
	}
	public TreeConfiguration setLogs(String... logs) {
		this.logs = new ArrayList<String>(Arrays.asList(logs));
		return this;
	}
	public TreeConfiguration setLeaves(String... leaves) {
		this.leaves = new ArrayList<String>(Arrays.asList(leaves));
		return this;
	}

	public boolean isLog(String name) {
		for (String block : Logs()) {
			if (block.equals(name) || name.matches(block)) {
				return true;
			}
		}
		return false;
	}

	public boolean isLeaf(String name) {
		for (String block : Leaves()) {
			if (block.equals(name) || name.matches(block)) {
				return true;
			}
		}
		return false;
	}

	/*
	 * Logs and leaves may be missing after Gson deserialization (players editing
	 * the json files), lazily default them to empty lists so the config never
	 * crashes on a null list
	 */
	public List<String> Logs() {
		if (logs == null) {
			logs = new ArrayList<String>();
		}
		return logs;
	}

	private List<String> LeavesList() {
		if (leaves == null) {
			leaves = new ArrayList<String>();
		}
		return leaves;
	}

	//Gets all leaves after merging the shared leaves (beehives etc)
	public String[] Leaves() {
		if (leaves_merged == null) {
			leaves_merged = Config.MergeArray(Config.ConvertListToArray(LeavesList()), Config.sharedLeaves);
		}
		return leaves_merged;
	}
	//Gets all blocks associated with this tree
	public String[] Blocks() {
		if (blocks == null) {
			blocks = ArrayUtils.addAll(Config.ConvertListToArray(Logs()), Leaves());
		}
		return blocks;
	}

	public void Merge(TreeConfiguration newTree) {
		for (String log : newTree.Logs()) {
			if (!Logs().contains(log)) {
				logs.add(log);
			}
		}
		for (String leaf : newTree.Leaves()) {
			if (!LeavesList().contains(leaf)) {
				leaves.add(leaf);
			}
		}
		leaves_merged = null;
	}
	public TreeConfiguration Clone() {
		return new TreeConfiguration(radius, leaf_limit, min_vertical_logs, trunk_radius,
				Config.ConvertListToArray(Logs()), Config.ConvertListToArray(LeavesList()));
	}
}

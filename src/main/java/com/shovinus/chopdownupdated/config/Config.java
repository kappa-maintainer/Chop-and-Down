package com.shovinus.chopdownupdated.config;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.ArrayUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.shovinus.chopdownupdated.ChopDown;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber
public class Config {

	public static String CATEGORY = "General";
	public static String MOD_CATEGORY = "Mod Compatibility";

	/*
	 * How breaking a log behaves. CHOPDOWN fells the whole tree as one rigid
	 * body (this mod's classic behaviour). TREECHOP uses the embedded HT's
	 * TreeChop mechanic: one chop per swing, chopped-log blocks, and the tree
	 * drops once enough chops accumulate. BOTH shows one chop layer on the log
	 * and then rigid-fells the whole tree, destroying the chopped cell too.
	 * OFF disables all felling/chopping and falls back to vanilla breaking.
	 */
	public enum TreeMode {
		CHOPDOWN, TREECHOP, BOTH, OFF
	}

	public static TreeMode treeMode = TreeMode.CHOPDOWN;

	/*
	 * Debug logging: off by default, enabled with 'debug=true' in the config
	 * file. All [ChopDown-DEBUG] console output is gated through debugLog so
	 * the released mod stays quiet unless someone is troubleshooting.
	 */
	public static boolean debug = false;

	public static boolean breakLeaves;
	public static int maxDropsPerTickPerTree;
	public static int maxFallingBlockBeforeManualMove;
	public static String[] allowedPlayers;
	public static String[] ignoreTools;

	public static HashMap<UUID, PersonalConfig> playerConfigs = new HashMap<UUID, PersonalConfig>();
	public static TreeConfiguration[] treeConfigurations = new TreeConfiguration[0];

	public static String[] leaves;
	public static String[] logs;
	public static String[] sharedLeaves;

	public static ModTreeConfigurations mods = new ModTreeConfigurations();

	public static PersonalConfig getPlayerConfig(UUID player) {
		PersonalConfig playerConfig;
		if (playerConfigs.containsKey(player)) {
			playerConfig = Config.playerConfigs.get(player);
		} else {
			playerConfig = new PersonalConfig();
			playerConfigs.put(player, playerConfig);
		}
		return playerConfig;

	}

	public static Configuration config;

	/*
	 * Bump this only when the config structure changes (renamed/removed keys or
	 * incompatible defaults). It must NOT track the mod version: using
	 * ChopDown.VERSION here made every mod update rename the config to _old and
	 * rebuild defaults, silently discarding player edits like treeMode.
	 */
	private static final String CONFIG_VERSION = "1";

	public static void load(FMLPreInitializationEvent event) {
		config = new Configuration(event.getSuggestedConfigurationFile(), CONFIG_VERSION);
		if (!config.getDefinedConfigVersion().equals(config.getLoadedConfigVersion())) {
			event.getSuggestedConfigurationFile().renameTo(new File(event.getSuggestedConfigurationFile().getPath() + "_old"));

			config = new Configuration(event.getSuggestedConfigurationFile(), CONFIG_VERSION);
		}
		reloadConfig();
	}

	public static void reloadConfig() {

		treeMode = TreeMode.valueOf(config.getString("treeMode", CATEGORY, TreeMode.CHOPDOWN.name(),
				"How breaking a log behaves. CHOPDOWN: fell the whole tree as one rigid body. "
						+ "TREECHOP: HT's TreeChop mechanic, one chop layer per swing, tree drops when enough chops accumulate. "
						+ "BOTH: chop one layer then rigid-fell the whole tree, destroying the chopped cell. "
						+ "OFF: vanilla breaking.").toUpperCase());
		maxDropsPerTickPerTree = config.getInt("maxDropsPerTickPerTree", CATEGORY, 150, 1, 1000000,
				"Maximum number of blocks to drop per tick for each tree thats falling");
		maxFallingBlockBeforeManualMove = config.getInt("maxFallingBlockBeforeManualMove", CATEGORY, 1500, 1, 1000000,
				"If the total blocks in the tree is above this amount instead of creating entities then it will place the blocks directly on the floor, this is for really large trees like the natura Redwood");
		breakLeaves = config.getBoolean("breakLeaves", CATEGORY, false,
				"When you chop a tree down the leaves all fall off and do their drops instead of falling with the tree, this can be better as a) less load and b)The falling of trees gets less messy, you still need to chop the logs but the leaves don't get in the way");
		sharedLeaves = config.getStringList("sharedLeaves", CATEGORY, new String[] { "harvestcraft:beehive:0" },
				"Not necessarily leaves, objects that if seemingly attached to the tree should fall down with it, such as beehives");

		allowedPlayers = config.getStringList("allowedPlayers", CATEGORY,
				new String[] { EntityPlayerMP.class.getName(),
						"micdoodle8.mods.galacticraft.core.entities.player.GCEntityPlayerMP",
						"clayborn.universalremote.hooks.entity.HookedEntityPlayerMP" },
				"List of all the player classes allowed to chop down trees, used to distinguish fake and real players");

		debug = config.getBoolean("debug", CATEGORY, false,
				"Log chop down debug messages to the console.");
		ignoreTools = config.getStringList("ignoreTools", CATEGORY, new String[] { "tconstruct:lumberaxe:.*" },
				"List of tools to ignore chop down on, such as tinkers lumberaxe, any tool that veinmines or similar should be ignored for chopdown");

		//Enabled tree config files. Built in mod configs (mod id) are created with
		// their built in defaults on first use. Any other entry is loaded as a
		// player made json file from the same directory, so custom trees are
		// configured exactly the same way as mod trees.
		String[] enabledTreeConfigs = config.getStringList("enabledTreeConfigs", MOD_CATEGORY,
				DEFAULT_ENABLED_TREE_CONFIGS,
				"List of tree configuration files to enable. Built in mod configs use the mod id (e.g. 'minecraft', 'biomesoplenty') and are created "
						+ "with built in defaults on first use. Custom tree files are plain json files you create yourself in "
						+ "config/chopdownupdated/, list the file name without the .json extension to enable them. "
						+ "All files can be edited by hand, delete a built in file to restore its defaults.");
		cleanupLegacyConfigKeys();

		//Load the enabled tree config files (creating missing built in files from
		// the defaults) and merge them together
		mods.clear();
		List<TreeConfiguration> mergedTrees = new ArrayList<>();
		for (String entry : enabledTreeConfigs) {
			if (entry.isEmpty()) {
				continue;
			}
			String registrationName = mods.findRegistrationName(entry);
			String modId = registrationName != null ? mods.getModId(registrationName) : null;
			if (modId != null && !Loader.isModLoaded(modId)) {
				// The mod is not installed, skip its config without creating a file
				continue;
			}
			File configFile = getTreeConfigFile(registrationName != null ? modId : entry);
			if (registrationName != null) {
				if (!configFile.exists()) {
					writeTreeConfigFile(configFile, mods.getTrees(registrationName));
				}
				TreeConfiguration[] loaded = loadTreeConfigFile(configFile);
				if (loaded.length == 0) {
					// Empty or unreadable file, fall back to the built in defaults
					// without overwriting the players file
					loaded = mods.getTrees(registrationName);
				}
				for (TreeConfiguration tree : loaded) {
					mergedTrees.add(tree);
				}
			} else if (configFile.exists()) {
				TreeConfiguration[] loaded = loadTreeConfigFile(configFile);
				for (TreeConfiguration tree : loaded) {
					mergedTrees.add(tree);
				}
			} else {
				System.out.println("ChopDown: tree config '" + entry + "' not found in "
						+ getTreeConfigDir().getPath() + ", skipped");
			}
		}
		mods.mergeTrees(mergedTrees.toArray(new TreeConfiguration[0]));
		treeConfigurations = mods.UnifiedTreeConfigs.toArray(new TreeConfiguration[0]);
		GenerateLeavesAndLogs();
		config.save();

	}

	/*
	 * Built in tree configs, all enabled by default. Config files are plain data
	 * so enabling a mod that is not installed has no effect.
	 */
	private static final String[] DEFAULT_ENABLED_TREE_CONFIGS = { "minecraft", "abyssalcraft",
			"aether_legacy", "betterwithaddons", "biomesoplenty", "cuisine", "defiledlands", "extratrees",
			"forestry", "ic2", "integrateddynamics", "jurassicraft", "natura", "naturalpledge",
			"harvestcraft", "plants2", "primal", "rustic", "sugiforest", "terra", "terraqueous",
			"thaumcraft", "thebetweenlands", "erebus", "midnight", "twilightforest", "traverse",
			"treasure2", "tropicraft", "pvj" };

	/*
	 * The directory that holds one json tree config per mod
	 */
	private static File getTreeConfigDir() {
		return new File(config.getConfigFile().getParentFile(), ChopDown.MODID);
	}

	private static File getTreeConfigFile(String modId) {
		return new File(getTreeConfigDir(), modId + ".json");
	}

	/*
	 * Load a tree config json, returns an empty array when the file is missing,
	 * empty or unreadable
	 */
	private static TreeConfiguration[] loadTreeConfigFile(File file) {
		if (!file.exists()) {
			return new TreeConfiguration[0];
		}
		try {
			TreeConfiguration[] loaded = new Gson().fromJson(new FileReader(file), TreeConfiguration[].class);
			if (loaded == null || loaded.length == 0) {
				System.out.println("ChopDown: empty tree config " + file.getName() + ", using built in defaults");
				return new TreeConfiguration[0];
			}
			return loaded;
		} catch (JsonSyntaxException | IOException ex) {
			System.out.println("ChopDown: invalid tree config " + file.getName() + ", using built in defaults");
			return new TreeConfiguration[0];
		}
	}

	/*
	 * Write a tree config json with pretty printing
	 */
	private static void writeTreeConfigFile(File file, TreeConfiguration[] trees) {
		try {
			file.getParentFile().mkdirs();
			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			try (Writer writer = new FileWriter(file)) {
				gson.toJson(trees, writer);
			}
		} catch (IOException ex) {
			System.out.println("ChopDown: could not write tree config " + file.getName());
		}
	}

	/*
	 * Remove the old per mod boolean toggles and the old customTrees string list
	 * from the config file so the config GUI does not show dead entries. Values
	 * are intentionally not migrated, the enabledTreeConfigs list replaces them.
	 */
	private static final String[] LEGACY_MOD_TOGGLES = { "Vanilla", "AbyssalCraft", "AetherLegacy",
			"BetterWithAddons", "BiomesOPlenty", "Cuisine", "DefiledLands", "ExtraTrees", "Forestry",
			"IndustrialCraft2", "IntegratedDynamics", "JurassiCraft", "Natura", "NaturalPledge",
			"PamsHarvestCraft", "Plants", "PrimalCore", "Rustic", "SugiForest", "Terra", "Terraqueous",
			"Thaumcraft", "TheBetweenLands", "TheErebus", "TheMidnight", "TheTwilightForest", "Traverse",
			"Treasure2", "Tropicraft", "VibrantJourneys" };

	private static void cleanupLegacyConfigKeys() {
		ConfigCategory category = config.getCategory(MOD_CATEGORY);
		for (String key : LEGACY_MOD_TOGGLES) {
			if (category.containsKey(key)) {
				category.remove(key);
			}
		}
		if (category.containsKey("customTrees")) {
			category.remove("customTrees");
			System.out.println("ChopDown: the customTrees list moved to config/chopdownupdated/, move your entries "
					+ "in to a json file there and add it to enabledTreeConfigs");
		}
	}

	public static boolean MatchesTool(String name) {
		for (String tool : Config.ignoreTools) {
			if (tool.equals(name) || name.matches(tool)) {
				return true;
			}
		}
		return false;
	}

	static String[] MergeArray(String[] a, String[] b) {
		String[] d = a;
		for (String c : b) {
			if (!ArrayUtils.contains(d, c)) {
				d = ArrayUtils.add(d, c);
			}
		}
		return d;
	}

	/*
	 * Print a debug message only when debug logging is enabled. Non-debug
	 * messages (config errors, warnings) still use System.out directly.
	 */
	public static void debugLog(String message) {
		if (debug) {
			System.out.println(message);
		}
	}

	private static void GenerateLeavesAndLogs() {
		leaves = new String[] {};
		logs = new String[] {};
		for (TreeConfiguration treeConfig : treeConfigurations) {
			leaves = MergeArray(leaves, treeConfig.Leaves());
			logs = MergeArray(logs, ConvertListToArray(treeConfig.Logs()));
		}
	}

	@SubscribeEvent
	public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
		if (event.getModID().equals(ChopDown.MODID)) {
			reloadConfig();
		}
	}

	public static String[] ConvertListToArray(List<String> list) {
		return list.toArray(new String[0]);
	}

}

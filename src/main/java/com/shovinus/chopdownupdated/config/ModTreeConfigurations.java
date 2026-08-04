package com.shovinus.chopdownupdated.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModTreeConfigurations {
	/*
	 * Registration name to provider instance
	 */
	Map<String, TreeConfigProvider> Providers = new HashMap<String, TreeConfigProvider>();
	List<TreeConfiguration> UnifiedTreeConfigs = new ArrayList<TreeConfiguration>();

	public void AddMod(TreeConfigProvider provider) {
		Providers.put(provider.registrationName(), provider);
	}

	/*
	 * Get the mod id of a registered tree configuration, or null when unknown
	 */
	public String getModId(String registrationName) {
		TreeConfigProvider provider = Providers.get(registrationName);
		return provider == null ? null : provider.modId();
	}

	/*
	 * Get the built in tree definitions of a registered tree configuration
	 */
	public TreeConfiguration[] getTrees(String registrationName) {
		TreeConfigProvider provider = Providers.get(registrationName);
		return provider == null ? null : provider.trees();
	}

	/*
	 * Resolve an enabledTreeConfigs entry (mod id or registration name) to the
	 * registration name used by the registry, returns null when unknown
	 */
	public String findRegistrationName(String modIdOrName) {
		if (Providers.containsKey(modIdOrName)) {
			return modIdOrName;
		}
		for (TreeConfigProvider provider : Providers.values()) {
			if (provider.modId().equals(modIdOrName)) {
				return provider.registrationName();
			}
		}
		return null;
	}

	/*
	 * Merge tree definitions in to the unified tree config list
	 */
	public void mergeTrees(TreeConfiguration[] trees) {
		for (TreeConfiguration newTree : trees) {
			if (!compareTrees(newTree)) {
				// Clone to avoid messing up original with possible future merges
				UnifiedTreeConfigs.add(newTree.Clone());
			}
		}
	}

	/*
	 * Reset the unified tree config list before reloading
	 */
	public void clear() {
		UnifiedTreeConfigs.clear();
	}

	private boolean compareTrees(TreeConfiguration newTree) {
		for (TreeConfiguration currentTree : UnifiedTreeConfigs) {
			for (String newLog : newTree.Logs()) {
				if (currentTree.Logs().contains(newLog)) {
					// Already have this log in a tree so merge the tree and return
					currentTree.Merge(newTree);
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Creates the holding class for the trees from different mods
	 */
	public ModTreeConfigurations() {
		for (TreeConfigData config : BuiltinTreeConfigs.ALL) {
			AddMod(config);
		}
	}
}

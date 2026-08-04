package com.shovinus.chopdownupdated.config;

/**
 * Generic tree config data of one mod. Instances are created for the built in
 * mods in {@link BuiltinTreeConfigs}, custom mods can provide their own
 * TreeConfigProvider implementations.
 */
public record TreeConfigData(String registrationName, String modId, TreeConfiguration[] trees)
		implements TreeConfigProvider {

	/*
	 * Convenience factory, varargs avoids writing the TreeConfiguration[] wrapper
	 */
	public static TreeConfigData of(String registrationName, String modId, TreeConfiguration... trees) {
		return new TreeConfigData(registrationName, modId, trees);
	}
}

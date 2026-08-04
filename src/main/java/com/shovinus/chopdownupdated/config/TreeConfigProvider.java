package com.shovinus.chopdownupdated.config;

/**
 * Provides the built in tree definitions of one mod. Each mod has a record
 * implementation holding the registration name, the mod id and the tree
 * definitions, registered as an instance in {@link ModTreeConfigurations}.
 */
public interface TreeConfigProvider {

	/*
	 * The registration name used by the registry (class style, e.g. "Vanilla")
	 */
	String registrationName();

	/*
	 * The mod id used for the json config file name and the enabledTreeConfigs
	 * list in the main config (e.g. "minecraft")
	 */
	String modId();

	/*
	 * The built in tree definitions of this mod, written to the json config file
	 * when it is missing
	 */
	TreeConfiguration[] trees();
}

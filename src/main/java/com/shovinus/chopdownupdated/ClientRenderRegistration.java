package com.shovinus.chopdownupdated;

import com.shovinus.chopdownupdated.tree.TargetedFallingBlock;

import net.minecraft.client.renderer.entity.RenderFallingBlock;
import net.minecraftforge.fml.client.registry.RenderingRegistry;

/*
 * Client only entity renderer registration. Kept in its own class so a dedicated
 * server never loads the client render classes: ChopDown only references this
 * class inside a client side branch, and class loading is lazy.
 */
final class ClientRenderRegistration {

	private ClientRenderRegistration() {
	}

	static void register() {
		RenderingRegistry.registerEntityRenderingHandler(TargetedFallingBlock.class,
				RenderFallingBlock::new);
	}
}

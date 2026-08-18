package ht.treechop;

import com.shovinus.chopdownupdated.ChopDown;
import ht.treechop.client.Client;
import ht.treechop.common.Common;
import ht.treechop.common.config.ConfigHandler;
import ht.treechop.common.event.CompatRegistrationEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.Logger;

/*
 * TreeChop chopping mechanics, embedded into ChopDownUpdated.
 *
 * Port of HT's TreeChop (MIT License, Copyright (c) 2020 hammertater),
 * 1.12.2 community port. No longer a standalone @Mod: the chop-layer
 * mechanic (ChoppedLogBlock, per-player settings, network sync, in-game
 * settings GUI) is registered by ChopDown and toggled by the TreeMode
 * config option.
 */
public class TreeChopMod {
    public static final String MOD_ID = ChopDown.MODID;
    public static final String MOD_NAME = "HT's TreeChop";
    public static final String VERSION = "0.14.7";

    public static Logger LOGGER; // Pretend this is final

    private static Common proxy;

    private TreeChopMod() {
    }

    /* Called by ChopDown.preinit after its own config is loaded. */
    public static void init(FMLPreInitializationEvent event) {
        LOGGER = event.getModLog();
        // The client config must load with a side-aware proxy in place, so
        // resolve the proxy before ConfigHandler.load().
        proxy = event.getSide() == Side.CLIENT ? new Client() : new Common();
        ConfigHandler.load(new java.io.File(event.getSuggestedConfigurationFile().getParentFile(), "chopdownupdated.cfg"));

        MinecraftForge.EVENT_BUS.register(proxy);
        proxy.preInit();
    }

    /* Called by ChopDown.init. */
    public static void postInit(FMLPostInitializationEvent event) {
        MinecraftForge.EVENT_BUS.post(new CompatRegistrationEvent());
    }

    public static Common getProxy() {
        return proxy;
    }

    public static void showText(String text) {
        Minecraft.getMinecraft().player.sendMessage(new TextComponentString(String.format("%s[%s] %s%s", TextFormatting.GRAY, TreeChopMod.MOD_NAME, TextFormatting.WHITE, text)));
    }

}

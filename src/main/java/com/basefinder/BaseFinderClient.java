package com.basefinder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaseFinderClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("basefinder");

    /** Поточна мітка (ставиться кліком у списку). */
    public static BlockPos target = null;

    public static KeyBinding openKey;
    public static KeyBinding espKey;
    public static KeyBinding mapKey;

    @Override
    public void onInitializeClient() {
        BaseFinderConfig.load();

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.basefinder.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, "category.basefinder"));
        espKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.basefinder.esp", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "category.basefinder"));

        mapKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.basefinder.map", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.basefinder"));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            BaseScanner.tick(mc);

            while (openKey.wasPressed()) {
                if (mc.currentScreen == null && mc.player != null) {
                    mc.setScreen(new BaseListScreen());
                }
            }
            while (mapKey.wasPressed()) {
                BaseFinderConfig.mapEnabled = !BaseFinderConfig.mapEnabled;
                BaseFinderConfig.save();
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal("BaseHawk карта: " + (BaseFinderConfig.mapEnabled ? "§aON" : "§cOFF")), true);
                }
            }
            while (espKey.wasPressed()) {
                BaseFinderConfig.espEnabled = !BaseFinderConfig.espEnabled;
                BaseFinderConfig.save();
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal("BaseHawk ESP: " + (BaseFinderConfig.espEnabled ? "§aON" : "§cOFF")), true);
                }
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> BaseScanner.onDisconnect());

        // життєвий цикл чанків: скидаємо тимчасові дані, знайдені блоки лишаються в пам'яті
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) ->
                BaseScanner.onChunkLoad(chunk.getPos().x, chunk.getPos().z));
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) ->
                BaseScanner.onChunkUnload(chunk.getPos().x, chunk.getPos().z));

        BaseCommands.register();

        BaseRenderer.init();
        LOGGER.info("BaseHawk loaded");
    }
}

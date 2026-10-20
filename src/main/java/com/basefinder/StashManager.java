package com.basefinder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

public class StashManager {
    // Список для збереження знайдених баз (координат)
    public static final Set<BlockPos> SAVED_BOXES = new HashSet<>();
    // Файл, куди запишуться координати у папці з грою
    private static final File FILE = new File(MinecraftClient.getInstance().runDirectory, "basehawk_stashes.txt");

    public static void checkAndLog(BlockPos pos, String name) {
        // Якщо ми вже знаємо цю скриню, ігноруємо її, щоб не спамити
        if (SAVED_BOXES.contains(pos)) return; 
        SAVED_BOXES.add(pos);

        // Пишемо повідомлення тобі в чат гри
        MinecraftClient.getInstance().execute(() -> {
            if (MinecraftClient.getInstance().player != null) {
                MinecraftClient.getInstance().player.sendMessage(Text.literal("§6[BaseHawk] §fЗнайдено: §b" + name + " §fна X:" + pos.getX() + " Y:" + pos.getY() + " Z:" + pos.getZ()), false);
            }
        });

        // Записуємо в текстовий файл
        try (PrintWriter pw = new PrintWriter(new FileWriter(FILE, true))) {
            pw.println(name + " -> X: " + pos.getX() + " Y: " + pos.getY() + " Z: " + pos.getZ());
        } catch (Exception ignored) {}
    }
}

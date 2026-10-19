package com.basefinder;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.List;

/** Клієнтські команди діагностики: /basehawk log | stats | near | recheck | verbose | expire <хв>. */
public final class BaseCommands {
    private BaseCommands() {}

    private static void say(FabricClientCommandSource src, String s) {
        src.sendFeedback(Text.literal(s));
    }

    private static void sayAll(FabricClientCommandSource src, String title, List<String> lines) {
        say(src, "§e[BaseHawk] " + title);
        if (lines.isEmpty()) say(src, "§7(порожньо)");
        for (String l : lines) say(src, "§7• §f" + l);
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("basehawk")
                    .then(ClientCommandManager.literal("log").executes(ctx -> {
                        sayAll(ctx.getSource(), "останні події:", Diag.recent(15));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("stats").executes(ctx -> {
                        List<String> lines = new java.util.ArrayList<>(BaseScanner.stats());
                        lines.addAll(Diag.statsLines());
                        sayAll(ctx.getSource(), "статистика:", lines);
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("near").executes(ctx -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc.player == null) return 0;
                        sayAll(ctx.getSource(), "блоки в радіусі 12 м:",
                                BaseScanner.describeNear(mc.player.getX(), mc.player.getY(), mc.player.getZ(), 12));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("recheck").executes(ctx -> {
                        int n = BaseScanner.recheckAll();
                        say(ctx.getSource(), "§e[BaseHawk] §fЗаписів позначено «не перевірено»: " + n
                                + ". Вони знову стануть підтвердженими, лише коли їх побачать наживо.");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("verbose").executes(ctx -> {
                        Diag.verbose = !Diag.verbose;
                        say(ctx.getSource(), "§e[BaseHawk] §fДіагностика в чаті: " + (Diag.verbose ? "§aON" : "§cOFF"));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("expire")
                            .then(ClientCommandManager.argument("minutes", IntegerArgumentType.integer(0, 100000))
                                    .executes(ctx -> {
                                        int m = IntegerArgumentType.getInteger(ctx, "minutes");
                                        BaseFinderConfig.expireMinutes = m;
                                        BaseFinderConfig.save();
                                        say(ctx.getSource(), "§e[BaseHawk] §fЗаписи, яких не бачили наживо понад "
                                                + m + " хв, видалятимуться" + (m == 0 ? " (вимкнено)." : "."));
                                        return 1;
                                    }))));
        });
    }
}

package com.basefinder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BaseFinderConfig {
    private BaseFinderConfig() {}

    public static boolean espEnabled = true;
    public static boolean labelsEnabled = true;
    public static boolean chatNotify = true;
    /** Рахувати в базах лише підтверджені блоки (бачені в цій сесії й стабільні). */
    public static boolean requireConfirmed = true;
    /** Режим "ловити все": скан щотіку, підтвердження з першого разу, без перевірки стану блока. */
    public static boolean catchAll = false;
    /** Скільки сканів підряд блок має бути на місці, щоб вважатись підтвердженим. */
    public static int confirmScans = 3;
    /** Запам'ятований блок видаляється, лише якщо ти ближче за стільки блоків, а його там нема. */
    public static int removeRadius = 8;
    /** Мінімальна сума очок кластера, щоб він вважався базою. */
    public static int minScore = 6;
    /** Макс. відстань між контейнерами, щоб вони вважались однією базою (блоків). */
    public static int linkRadius = 24;
    /** Як часто сканувати завантажені чанки (тіки). */
    public static int scanIntervalTicks = 4;

    public static int effectiveScanTicks() {
        return catchAll ? 1 : Math.max(1, scanIntervalTicks);
    }

    public static int effectiveConfirmScans() {
        return catchAll ? 1 : Math.max(1, confirmScans);
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class Data {
        boolean espEnabled = true;
        boolean labelsEnabled = true;
        boolean chatNotify = true;
        boolean requireConfirmed = true;
        boolean catchAll = false;
        int minScore = 6;
        int linkRadius = 24;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("basefinder").resolve("settings.json");
    }

    public static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            try (Reader r = Files.newBufferedReader(f)) {
                Data d = GSON.fromJson(r, Data.class);
                if (d == null) return;
                espEnabled = d.espEnabled;
                labelsEnabled = d.labelsEnabled;
                chatNotify = d.chatNotify;
                requireConfirmed = d.requireConfirmed;
                catchAll = d.catchAll;
                minScore = Math.max(1, Math.min(60, d.minScore));
                linkRadius = Math.max(8, Math.min(64, d.linkRadius));
            }
        } catch (Exception ex) {
            BaseFinderClient.LOGGER.warn("BaseHawk: не вдалося завантажити налаштування", ex);
        }
    }

    public static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Data d = new Data();
            d.espEnabled = espEnabled;
            d.labelsEnabled = labelsEnabled;
            d.chatNotify = chatNotify;
            d.requireConfirmed = requireConfirmed;
            d.catchAll = catchAll;
            d.minScore = minScore;
            d.linkRadius = linkRadius;
            try (Writer w = Files.newBufferedWriter(f)) {
                GSON.toJson(d, w);
            }
        } catch (Exception ex) {
            BaseFinderClient.LOGGER.warn("BaseHawk: не вдалося зберегти налаштування", ex);
        }
    }
}

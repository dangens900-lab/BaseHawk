package com.basefinder;

public final class BaseFinderConfig {
    private BaseFinderConfig() {}

    public static boolean espEnabled = true;
    public static boolean chatNotify = true;
    /** Мінімальна сума очок кластера, щоб він вважався базою. */
    public static int minScore = 6;
    /** Макс. відстань між контейнерами, щоб вони вважались однією базою (блоків). */
    public static int linkRadius = 24;
    /** Як часто сканувати завантажені чанки (тіки). */
    public static int scanIntervalTicks = 40;
}

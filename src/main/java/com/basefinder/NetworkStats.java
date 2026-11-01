package com.basefinder;

import java.util.concurrent.atomic.LongAdder;

/** Пасивна статистика подій чанків на клієнті; вміст мережевих пакетів не читається і не змінюється. */
public final class NetworkStats {
    private NetworkStats() {}

    private static final LongAdder chunkLoads = new LongAdder();
    private static final LongAdder chunkUnloads = new LongAdder();
    private static final LongAdder scannedChunks = new LongAdder();
    private static volatile long lastChunkEventMs;

    public static void onChunkLoad(int x, int z) {
        chunkLoads.increment();
        lastChunkEventMs = System.currentTimeMillis();
    }

    public static void onChunkUnload(int x, int z) {
        chunkUnloads.increment();
        lastChunkEventMs = System.currentTimeMillis();
    }

    public static void recordScannedChunks(int count) {
        if (count > 0) scannedChunks.add(count);
    }

    public static String summary() {
        long age = lastChunkEventMs == 0 ? -1 : (System.currentTimeMillis() - lastChunkEventMs) / 1000;
        return "Чанки: +" + chunkLoads.sum() + " / -" + chunkUnloads.sum()
                + " · оброблено: " + scannedChunks.sum() + " · подія: "
                + (age < 0 ? "немає" : age + " с тому");
    }

    public static void resetSession() {
        chunkLoads.reset();
        chunkUnloads.reset();
        scannedChunks.reset();
        lastChunkEventMs = 0;
    }
}

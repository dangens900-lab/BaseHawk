package com.basefinder;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Обмежена черга подій життєвого циклу чанків.
 * Це НЕ перехоплювач сирих мережевих пакетів: зберігаються лише координати
 * та тип уже отриманої клієнтом події чанка.
 */
public final class NetworkStats {
    private NetworkStats() {}

    private static final int MAX_PENDING = 4096;
    private static final int MAX_HISTORY = 256;
    private static final ArrayDeque<Event> pending = new ArrayDeque<>();
    private static final ArrayDeque<Event> history = new ArrayDeque<>();

    private static final AtomicLong chunkLoads = new AtomicLong();
    private static final AtomicLong chunkUnloads = new AtomicLong();
    private static final AtomicLong scannedChunks = new AtomicLong();
    private static final AtomicLong uniqueChunkLoads = new AtomicLong();
    private static final AtomicLong droppedEvents = new AtomicLong();

    private static volatile long lastChunkEventMs;
    private static volatile long lastProcessedMs;

    private record Event(boolean load, int x, int z, long receivedAt) {}

    public static synchronized void onChunkLoad(int x, int z) {
        enqueue(new Event(true, x, z, System.currentTimeMillis()));
    }

    public static synchronized void onChunkUnload(int x, int z) {
        enqueue(new Event(false, x, z, System.currentTimeMillis()));
    }

    private static void enqueue(Event event) {
        if (pending.size() >= MAX_PENDING) {
            pending.removeFirst();
            droppedEvents.incrementAndGet();
        }
        pending.addLast(event);
        lastChunkEventMs = event.receivedAt();
    }

    /** Обробляє події після заданої затримки; викликати з клієнтського тіку. */
    public static synchronized void processDelayed(long now, int delayMs) {
        long delay = Math.max(0, Math.min(10_000, delayMs));
        while (!pending.isEmpty()) {
            Event e = pending.peekFirst();
            if (now - e.receivedAt() < delay) break;
            pending.removeFirst();
            if (e.load()) chunkLoads.incrementAndGet();
            else chunkUnloads.incrementAndGet();
            lastProcessedMs = now;
            if (history.size() >= MAX_HISTORY) history.removeFirst();
            history.addLast(e);
        }
    }

    public static void recordScannedChunks(int count) {
        if (count > 0) scannedChunks.updateAndGet(v -> Math.min(Long.MAX_VALUE - count, v) + count);
    }

    public static String summary() {
        long age = lastChunkEventMs == 0 ? -1 : (System.currentTimeMillis() - lastChunkEventMs) / 1000;
        return "Події чанків: +" + chunkLoads.get() + " / -" + chunkUnloads.get()
                + " · черга: " + pendingSize()
                + " · відкинуто: " + droppedEvents.get()
                + " · остання: " + (age < 0 ? "немає" : age + " с тому");
    }

    public static synchronized int pendingSize() {
        return pending.size();
    }

    public static synchronized int historySize() {
        return history.size();
    }

    public static long scannedTotal() {
        return scannedChunks.get();
    }

    public static long uniqueChunkLoadsTotal() {
        return uniqueChunkLoads.get();
    }

    public static void recordUniqueChunkLoad() {
        uniqueChunkLoads.updateAndGet(v -> v == Long.MAX_VALUE ? v : v + 1);
    }

    public static synchronized void resetSession() {
        pending.clear();
        history.clear();
        chunkLoads.set(0);
        chunkUnloads.set(0);
        scannedChunks.set(0);
        uniqueChunkLoads.set(0);
        droppedEvents.set(0);
        lastChunkEventMs = 0;
        lastProcessedMs = 0;
    }
}

package com.basefinder;

import java.util.List;

public record Base(int cx, int cy, int cz,
                   int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                   int score, int total, int confirmed, int[] counts, List<Entry> entries) {

    /**
     * seen     — у скількох сканах підряд клієнт бачив цей блок у завантаженому чанку;
     * verified — блок бачили в цій сесії (false = запис лише зі старого файлу збереження).
     */
    public record Entry(int x, int y, int z, StorageType type, int seen, boolean verified) {
        /** Підтверджений = перевірений клієнтом і стабільний кілька сканів підряд. */
        public boolean confirmed() {
            return verified && seen >= Math.max(1, BaseFinderConfig.confirmScans);
        }
    }

    public double distSq(double px, double py, double pz) {
        double dx = cx + 0.5 - px;
        double dy = cy + 0.5 - py;
        double dz = cz + 0.5 - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        StorageType[] v = StorageType.values();
        for (int i = 0; i < v.length; i++) {
            if (counts[i] > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(v[i].label).append(' ').append(counts[i]);
            }
        }
        return sb.toString();
    }
}

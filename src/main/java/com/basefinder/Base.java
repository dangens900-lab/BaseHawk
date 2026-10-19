package com.basefinder;

import java.util.List;

public record Base(int cx, int cy, int cz,
                   int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                   int score, int total, int confirmed, long lastSeen, int[] counts, List<Entry> entries) {

    /**
     * seen     — у скількох сканах чанка підряд клієнт бачив цей блок;
     * verified — блок бачили наживо в цій сесії (false = запис лише зі збереження);
     * seenAt   — мілісекунди (epoch), коли блок востаннє бачили наживо; 0 = невідомо.
     */
    public record Entry(int x, int y, int z, StorageType type, int seen, boolean verified, long seenAt) {
        /** Підтверджений = перевірений клієнтом у цій сесії й стабільний кілька сканів підряд. */
        public boolean confirmed() {
            return verified && seen >= BaseFinderConfig.effectiveConfirmScans();
        }

        /** Скільки хвилин тому блок востаннє бачили наживо; -1 якщо невідомо. */
        public long ageMinutes() {
            return seenAt <= 0 ? -1 : Math.max(0, (System.currentTimeMillis() - seenAt) / 60000L);
        }

        public String status() {
            if (!verified) return "з пам'яті (в цій сесії не бачено)";
            if (confirmed()) return "підтверджено";
            return "ще не стабільний (" + seen + "/" + BaseFinderConfig.effectiveConfirmScans() + ")";
        }
    }

    public double distSq(double px, double py, double pz) {
        double dx = cx + 0.5 - px;
        double dy = cy + 0.5 - py;
        double dz = cz + 0.5 - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Скільки хвилин тому останній раз бачили будь-який блок бази; -1 якщо невідомо. */
    public long ageMinutes() {
        return lastSeen <= 0 ? -1 : Math.max(0, (System.currentTimeMillis() - lastSeen) / 60000L);
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

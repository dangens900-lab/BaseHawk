package com.basefinder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Діагностика: чому блок прийнято, відхилено або видалено.
 * Подія пишеться лише коли причина для цієї позиції ЗМІНИЛАСЬ, тож журнал не засмічується повторами.
 */
public final class Diag {
    private Diag() {}

    public enum Reason {
        ACCEPTED("прийнято: новий блок у чанку"),
        CONFIRMED("підтверджено: стабільний кілька сканів"),
        REJ_REMOVED("відхилено: блок-ентіті позначений як видалений"),
        REJ_AIR("відхилено: у світі в цій позиції повітря"),
        REJ_STATE("відхилено: стан блока не збігається з блок-ентіті"),
        REJ_TYPE("відхилено: блок у світі не того типу"),
        IGNORED("пропущено: базу видалено користувачем"),
        DROP_FLICKER("відкинуто: з'явився на мить і зник"),
        DROP_GONE("видалено: зник, коли ти був поруч"),
        EXPIRED("видалено: запис застарів"),
        KEPT_HIDDEN("лишено в пам'яті: сервер не показує його здалеку");

        public final String text;

        Reason(String text) {
            this.text = text;
        }
    }

    private static final int MAX_LOG = 80;
    private static final ArrayDeque<String> log = new ArrayDeque<>();
    private static final EnumMap<Reason, Integer> counts = new EnumMap<>(Reason.class);
    private static final Map<Long, Reason> last = new HashMap<>();

    /** Якщо true — кожна нова подія ще й іде в чат і latest.log. */
    public static boolean verbose = false;

    public static long lastScanNs = 0;
    public static long maxScanNs = 0;
    public static double avgScanNs = 0;
    public static int lastScanChunks = 0;

    public static void event(Reason r, long posKey, StorageType t, int x, int y, int z) {
        if (last.get(posKey) == r) return;
        if (last.size() > 20000) last.clear();
        last.put(posKey, r);
        counts.merge(r, 1, Integer::sum);

        String line = r.text + " — " + (t == null ? "?" : t.label) + " " + x + " " + y + " " + z;
        log.addLast(line);
        while (log.size() > MAX_LOG) log.removeFirst();

        if (verbose) {
            BaseFinderClient.LOGGER.info("[diag] {}", line);
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.inGameHud != null) {
                mc.inGameHud.getChatHud().addMessage(Text.literal("§8[BaseHawk] " + line));
            }
        }
    }

    public static Reason lastReason(long posKey) {
        return last.get(posKey);
    }

    public static void recordScan(long ns) {
        lastScanNs = ns;
        if (ns > maxScanNs) maxScanNs = ns;
        avgScanNs = avgScanNs == 0 ? ns : avgScanNs * 0.95 + ns * 0.05;
    }

    public static List<String> recent(int n) {
        List<String> all = new ArrayList<>(log);
        int from = Math.max(0, all.size() - n);
        return all.subList(from, all.size());
    }

    public static List<String> statsLines() {
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "скан: середній %.2f мс, макс %.2f мс, останній %.2f мс, чанків за скан: %d",
                avgScanNs / 1e6, maxScanNs / 1e6, lastScanNs / 1e6, lastScanChunks));
        for (Reason r : Reason.values()) {
            int c = counts.getOrDefault(r, 0);
            if (c > 0) out.add(r.text + ": " + c);
        }
        return out;
    }

    public static void reset() {
        log.clear();
        counts.clear();
        last.clear();
    }
}

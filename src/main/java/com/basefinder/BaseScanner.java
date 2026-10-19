package com.basefinder;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.SpawnerBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Сканує завантажені клієнтом чанки, збирає контейнери (block entities),
 * запам'ятовує їх (навіть після вивантаження чанка) і групує в "бази".
 * Бачить тільки те, що сервер уже надіслав клієнту; відсутність даних не видається за знахідку.
 */
public final class BaseScanner {
    private BaseScanner() {}

    private static final Gson GSON = new Gson();
    private static final Map<Long, List<Base.Entry>> chunks = new HashMap<>();
    /** Позиції контейнерів, які користувач видалив зі списку (щоб вони не з'являлись знову). */
    private static final Set<Long> ignored = new HashSet<>();
    private static final Set<Long> notified = new HashSet<>();
    /** Відхилені як неправдиві позиції, по чанках (прибираються разом із чанком). */
    private static final Map<Long, Set<Long>> rejectedByChunk = new HashMap<>();
    /** Чанки, які вже сканувались у поточному завантаженні (скидається при load/unload чанка). */
    private static final Set<Long> scannedChunks = new HashSet<>();
    /** Відстані (блоки), на яких нові контейнери вперше з'являлись у вже завантаженому чанку. */
    private static final List<Integer> revealSamples = new ArrayList<>();
    private static int revealCache = -2;
    /** Клітинки 16x16, які ти вже "прочесав". */
    private static final Set<Long> covered = new HashSet<>();
    private static volatile List<Base> bases = List.of();
    private static String currentKey = null;
    private static int tickCounter = 0;
    private static boolean dirty = false;

    // стан поточного скану
    private static double sx, sy, sz, sRemoveR2;
    private static boolean scanChanged;
    private static long scanNow;
    private static int[][] order = null;
    private static int orderVd = -1;
    private static int nearCount = 0;
    private static int cursor = 0;
    /** Чанки в межах цього радіуса (у чанках², тобто 3 чанки) скануються щоразу повністю. */
    private static final int NEAR_R2 = 9;

    private static final class SaveData {
        int[][] entries;
        long[] seenAt;
        long[] ignored;
    }

    public static List<Base> bases() {
        return bases;
    }

    public static void tick(MinecraftClient mc) {
        ClientWorld world = mc.world;
        ClientPlayerEntity player = mc.player;
        if (world == null || player == null) return;

        String key = storeKey(mc, world);
        if (!key.equals(currentKey)) {
            if (currentKey != null) save();
            currentKey = key;
            resetState();
            load();
            recompute(true);
        }

        tickCounter++;
        if (tickCounter % BaseFinderConfig.effectiveScanTicks() == 0) {
            long t0 = System.nanoTime();
            scanLoaded(mc, world, player);
            Diag.recordScan(System.nanoTime() - t0);
            if (scanChanged) dirty = true;
        }
        if (tickCounter % 10 == 0) markCovered(player);
        if (tickCounter % 200 == 0) expireOld();
        if (dirty && tickCounter % 10 == 0) {
            recompute(false);
            dirty = false;
        }
        if (tickCounter % 1200 == 0) save();
    }

    private static void resetState() {
        chunks.clear();
        ignored.clear();
        notified.clear();
        rejectedByChunk.clear();
        scannedChunks.clear();
        revealSamples.clear();
        revealCache = -2;
        covered.clear();
        bases = List.of();
        cursor = nearCount;
        dirty = false;
        Diag.reset();
    }

    // ---------- життєвий цикл чанків ----------

    /** Чанк щойно завантажився: перший скан не вважаємо "появою поблизу" (не псуємо вимір радіуса показу). */
    public static void onChunkLoad(int x, int z) {
        scannedChunks.remove(ChunkPos.toLong(x, z));
    }

    /** Чанк вивантажено: забуваємо його тимчасові дані; знайдені блоки лишаються в пам'яті. */
    public static void onChunkUnload(int x, int z) {
        long k = ChunkPos.toLong(x, z);
        scannedChunks.remove(k);
        rejectedByChunk.remove(k);
    }

    // ---------- сканування ----------

    private static void buildOrder(int vd) {
        List<int[]> list = new ArrayList<>();
        for (int dx = -vd; dx <= vd; dx++) {
            for (int dz = -vd; dz <= vd; dz++) list.add(new int[]{dx, dz});
        }
        list.sort(Comparator.comparingInt(a -> a[0] * a[0] + a[1] * a[1]));
        order = list.toArray(new int[0][]);
        orderVd = vd;
        int n = 0;
        while (n < order.length && order[n][0] * order[n][0] + order[n][1] * order[n][1] <= NEAR_R2) n++;
        nearCount = n;
        cursor = nearCount;
    }

    private static void scanLoaded(MinecraftClient mc, ClientWorld world, ClientPlayerEntity player) {
        int vd = mc.options.getClampedViewDistance();
        if (order == null || orderVd != vd) buildOrder(vd);

        ChunkPos pc = player.getChunkPos();
        sx = player.getX();
        sy = player.getY();
        sz = player.getZ();
        sRemoveR2 = (double) BaseFinderConfig.removeRadius * BaseFinderConfig.removeRadius;
        scanNow = System.currentTimeMillis();
        scanChanged = false;
        int scanned = 0;

        // 1) ближні чанки — щоразу повністю (саме там сервер показує блоки)
        for (int i = 0; i < nearCount; i++) {
            scanChunk(world, pc.x + order[i][0], pc.z + order[i][1]);
            scanned++;
        }

        // 2) далекі чанки — по колу, у межах бюджету часу, щоб не гальмувати кадр
        int far = order.length - nearCount;
        if (far > 0) {
            long deadline = System.nanoTime() + BaseFinderConfig.scanBudgetMs * 1_000_000L;
            int done = 0;
            while (done < far && System.nanoTime() < deadline) {
                if (cursor < nearCount || cursor >= order.length) cursor = nearCount;
                scanChunk(world, pc.x + order[cursor][0], pc.z + order[cursor][1]);
                cursor++;
                done++;
                scanned++;
            }
        }
        Diag.lastScanChunks = scanned;
    }

    private static void scanChunk(ClientWorld world, int x, int z) {
        WorldChunk ch = world.getChunkManager().getWorldChunk(x, z);
        if (ch == null || ch.isEmpty()) return;
        long key = ChunkPos.toLong(x, z);
        boolean wasScanned = !scannedChunks.add(key);

        List<Base.Entry> old = chunks.get(key);
        if (ch.getBlockEntities().isEmpty()) {
            rejectedByChunk.remove(key);
            if (old == null) return;
        }

        Map<Long, Base.Entry> prev = null;
        if (old != null) {
            prev = new HashMap<>();
            for (Base.Entry e : old) prev.put(BlockPos.asLong(e.x(), e.y(), e.z()), e);
        }

        Map<Long, Base.Entry> now = new HashMap<>();
        Set<Long> rej = null;
        for (BlockEntity be : ch.getBlockEntities().values()) {
            StorageType t = StorageType.of(be);
            if (t == null) continue;
            BlockPos p = be.getPos();
            long pl = p.asLong();
            if (ignored.contains(pl)) {
                Diag.event(Diag.Reason.IGNORED, pl, t, p.getX(), p.getY(), p.getZ());
                continue;
            }
            if (!BaseFinderConfig.catchAll) {
                Diag.Reason bad = checkBlock(ch, be, p, t);
                if (bad != null) {
                    if (rej == null) rej = new HashSet<>();
                    rej.add(pl);
                    Diag.event(bad, pl, t, p.getX(), p.getY(), p.getZ());
                    continue;
                }
            }

            Base.Entry before = prev == null ? null : prev.get(pl);
            int seen = (before != null && before.type() == t) ? Math.min(before.seen() + 1, 1000) : 1;
            Base.Entry fresh = new Base.Entry(p.getX(), p.getY(), p.getZ(), t, seen, true, scanNow);
            if (before == null) {
                scanChanged = true;
                Diag.event(Diag.Reason.ACCEPTED, pl, t, p.getX(), p.getY(), p.getZ());
                if (wasScanned) addRevealSample(p);
            } else if (before.confirmed() != fresh.confirmed()) {
                scanChanged = true;
            }
            if (fresh.confirmed()) Diag.event(Diag.Reason.CONFIRMED, pl, t, p.getX(), p.getY(), p.getZ());
            now.put(pl, fresh);
        }
        if (rej == null) rejectedByChunk.remove(key);
        else rejectedByChunk.put(key, rej);

        // Сервер може показувати блоки лише поблизу. Тому запам'ятований блок, якого зараз нема в чанку,
        // видаляємо лише якщо ми впритул до нього. Здалеку він просто прихований сервером — лишається в
        // пам'яті (з віком "бачено N хв тому"), а не видається за свіжу знахідку.
        List<Base.Entry> merged = new ArrayList<>(now.values());
        if (old != null) {
            for (Base.Entry e : old) {
                long pl = BlockPos.asLong(e.x(), e.y(), e.z());
                if (now.containsKey(pl)) continue;
                if (rej != null && rej.contains(pl)) {   // блок-ентіті є, але світ каже "не те" — вірити пам'яті не можна
                    scanChanged = true;
                    continue;
                }
                if (e.verified() && !e.confirmed()) {      // мигнув і зник — фантом
                    Diag.event(Diag.Reason.DROP_FLICKER, pl, e.type(), e.x(), e.y(), e.z());
                    scanChanged = true;
                    continue;
                }
                double ddx = e.x() + 0.5 - sx, ddy = e.y() + 0.5 - sy, ddz = e.z() + 0.5 - sz;
                if (ddx * ddx + ddy * ddy + ddz * ddz > sRemoveR2) {
                    merged.add(e);
                    Diag.event(Diag.Reason.KEPT_HIDDEN, pl, e.type(), e.x(), e.y(), e.z());
                } else {
                    Diag.event(Diag.Reason.DROP_GONE, pl, e.type(), e.x(), e.y(), e.z());
                    scanChanged = true;
                }
            }
        }
        int oldSize = old == null ? 0 : old.size();
        if (merged.size() != oldSize) scanChanged = true;
        if (merged.isEmpty()) chunks.remove(key);
        else chunks.put(key, merged);
    }

    /**
     * Перевірка достовірності на боці клієнта: блок-ентіті не видалений, у світі справді стоїть
     * відповідний блок (не повітря), а закешований стан збігається зі станом у чанку.
     * Повертає причину відхилення або null. Клієнт не може перевірити, чи блок існує на сервері насправді:
     * якщо сервер сам надсилає підроблені блоки, ця перевірка їх не відрізнить.
     */
    private static Diag.Reason checkBlock(WorldChunk ch, BlockEntity be, BlockPos p, StorageType t) {
        if (be.isRemoved()) return Diag.Reason.REJ_REMOVED;
        BlockState state = ch.getBlockState(p);
        if (state.isAir()) return Diag.Reason.REJ_AIR;
        if (state.getBlock() != be.getCachedState().getBlock()) return Diag.Reason.REJ_STATE;
        boolean ok = switch (t) {
            case CHEST -> state.getBlock() instanceof ChestBlock;
            case BARREL -> state.getBlock() instanceof BarrelBlock;
            case SHULKER -> state.getBlock() instanceof ShulkerBoxBlock;
            case HOPPER -> state.getBlock() instanceof HopperBlock;
            case ENDER_CHEST -> state.getBlock() instanceof EnderChestBlock;
            case SPAWNER -> state.getBlock() instanceof SpawnerBlock;
        };
        return ok ? null : Diag.Reason.REJ_TYPE;
    }

    private static void addRevealSample(BlockPos p) {
        double dx = p.getX() + 0.5 - sx, dz = p.getZ() + 0.5 - sz;
        int rd = (int) Math.sqrt(dx * dx + dz * dz);
        if (rd > 200) return;
        if (revealSamples.size() >= 200) revealSamples.remove(0);
        revealSamples.add(rd);
        revealCache = -2;
    }

    // ---------- старіння, повторна перевірка, діагностика ----------

    /** Видаляє записи, яких не бачили наживо довше за expireMinutes (0 = вимкнено). Невідомий вік = застарілий. */
    private static void expireOld() {
        int mins = BaseFinderConfig.expireMinutes;
        if (mins <= 0) return;
        long limit = System.currentTimeMillis() - mins * 60_000L;
        boolean any = false;
        for (Iterator<List<Base.Entry>> it = chunks.values().iterator(); it.hasNext(); ) {
            List<Base.Entry> l = it.next();
            boolean removed = l.removeIf(e -> {
                if (e.seenAt() > 0 && e.seenAt() >= limit) return false;
                Diag.event(Diag.Reason.EXPIRED, BlockPos.asLong(e.x(), e.y(), e.z()), e.type(), e.x(), e.y(), e.z());
                return true;
            });
            if (removed) any = true;
            if (l.isEmpty()) it.remove();
        }
        if (any) dirty = true;
    }

    /** Позначає всі записи "не перевіреними": вони знову стануть підтвердженими, лише коли їх побачать наживо. */
    public static int recheckAll() {
        int n = 0;
        for (List<Base.Entry> l : chunks.values()) {
            for (int i = 0; i < l.size(); i++) {
                Base.Entry e = l.get(i);
                l.set(i, new Base.Entry(e.x(), e.y(), e.z(), e.type(), 0, false, e.seenAt()));
                n++;
            }
        }
        Diag.reset();
        recompute(true);
        return n;
    }

    public static List<String> stats() {
        int entries = 0, confirmed = 0, verified = 0;
        for (List<Base.Entry> l : chunks.values()) {
            for (Base.Entry e : l) {
                entries++;
                if (e.verified()) verified++;
                if (e.confirmed()) confirmed++;
            }
        }
        List<String> out = new ArrayList<>();
        out.add("записів у пам'яті: " + entries + " (бачено в цій сесії: " + verified + ", підтверджено: " + confirmed + ")");
        out.add("чанків із записами: " + chunks.size() + ", баз: " + bases.size()
                + ", відхилено: " + rejectedCount() + ", видалених вручну: " + ignored.size());
        out.add("радіус показу: " + (revealRadius() >= 0 ? "~" + revealRadius() + " бл." : "поки невідомо")
                + ", прочесано клітинок: " + covered.size());
        out.add("режим «Ловити все»: " + (BaseFinderConfig.catchAll ? "ON" : "OFF")
                + ", застарівання: " + (BaseFinderConfig.expireMinutes > 0 ? BaseFinderConfig.expireMinutes + " хв" : "вимкнено"));
        return out;
    }

    /** Що мод знає про блоки навколо гравця і чому. */
    public static List<String> describeNear(double px, double py, double pz, double r) {
        List<String> out = new ArrayList<>();
        double r2 = r * r;
        for (List<Base.Entry> l : chunks.values()) {
            for (Base.Entry e : l) {
                double dx = e.x() + 0.5 - px, dy = e.y() + 0.5 - py, dz = e.z() + 0.5 - pz;
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                long age = e.ageMinutes();
                Diag.Reason last = Diag.lastReason(BlockPos.asLong(e.x(), e.y(), e.z()));
                out.add(e.type().label + " " + e.x() + " " + e.y() + " " + e.z() + " — " + e.status()
                        + (age >= 0 ? ", бачено " + age + " хв тому" : ", вік невідомий")
                        + (last != null ? " [" + last.text + "]" : ""));
            }
        }
        for (Set<Long> s : rejectedByChunk.values()) {
            for (long pl : s) {
                BlockPos bp = BlockPos.fromLong(pl);
                double dx = bp.getX() + 0.5 - px, dy = bp.getY() + 0.5 - py, dz = bp.getZ() + 0.5 - pz;
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                Diag.Reason last = Diag.lastReason(pl);
                out.add("ВІДХИЛЕНО " + bp.getX() + " " + bp.getY() + " " + bp.getZ()
                        + (last != null ? " — " + last.text : ""));
            }
        }
        if (out.size() > 15) return new ArrayList<>(out.subList(0, 15));
        return out;
    }

    /** Орієнтовний радіус, на якому сервер показує контейнери (медіана), або -1, якщо даних мало. */
    public static int revealRadius() {
        if (revealSamples.size() < 3) return -1;
        if (revealCache < 0) {
            List<Integer> c = new ArrayList<>(revealSamples);
            Collections.sort(c);
            revealCache = c.get(c.size() / 2);
        }
        return revealCache;
    }

    public static Set<Long> covered() {
        return covered;
    }

    public static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    /** Позначає клітинки навколо гравця як прочесані (радіус = виміряний радіус показу, інакше 16 блоків). */
    private static void markCovered(ClientPlayerEntity player) {
        int r = revealRadius();
        if (r < 0) r = 16;
        int cr = Math.max(1, Math.min(8, (int) Math.ceil(r / 16.0)));
        int pcx = Math.floorDiv((int) Math.floor(player.getX()), 16);
        int pcz = Math.floorDiv((int) Math.floor(player.getZ()), 16);
        for (int dz = -cr; dz <= cr; dz++) {
            for (int dx = -cr; dx <= cr; dx++) {
                if (dx * dx + dz * dz <= cr * cr) covered.add(cellKey(pcx + dx, pcz + dz));
            }
        }
    }

    /** Скільки позицій клієнт зараз відхиляє як фантомні/неузгоджені. */
    public static int rejectedCount() {
        int n = 0;
        for (Set<Long> s : rejectedByChunk.values()) n += s.size();
        return n;
    }

    public static void recompute() {
        recompute(true);
    }

    private static void recompute(boolean silent) {
        List<Base.Entry> all = new ArrayList<>();
        for (List<Base.Entry> l : chunks.values()) {
            for (Base.Entry e : l) {
                // непідтверджені (з файлу або ще не стабільні) не враховуємо, якщо ввімкнено "лише підтверджені"
                if (!BaseFinderConfig.requireConfirmed || e.confirmed()) all.add(e);
            }
        }
        List<Base> result = cluster(all, Math.max(4, BaseFinderConfig.linkRadius), BaseFinderConfig.minScore);
        bases = result;

        MinecraftClient mc = MinecraftClient.getInstance();
        int shown = 0;
        for (Base b : result) {
            long k = ((long) Math.floorDiv(b.cx(), 48) << 32) ^ (Math.floorDiv(b.cz(), 48) & 0xFFFFFFFFL);
            if (notified.add(k) && !silent && BaseFinderConfig.chatNotify && shown < 3 && mc.inGameHud != null) {
                shown++;
                mc.inGameHud.getChatHud().addMessage(Text.literal(
                        "§e[BaseHawk] §fЗнайдено базу §a" + b.cx() + " " + b.cy() + " " + b.cz()
                                + " §7(" + b.summary() + ")"));
            }
        }
    }

    // ---------- видалення ----------

    /** Видаляє базу зі списку й запам'ятовує її контейнери як ігноровані. */
    public static void deleteBase(Base b) {
        for (Base.Entry e : b.entries()) {
            ignored.add(BlockPos.asLong(e.x(), e.y(), e.z()));
        }
        for (Iterator<List<Base.Entry>> it = chunks.values().iterator(); it.hasNext(); ) {
            List<Base.Entry> l = it.next();
            l.removeIf(e -> ignored.contains(BlockPos.asLong(e.x(), e.y(), e.z())));
            if (l.isEmpty()) it.remove();
        }
        BlockPos t = BaseFinderClient.target;
        if (t != null && t.getX() == b.cx() && t.getY() == b.cy() && t.getZ() == b.cz()) {
            BaseFinderClient.target = null;
        }
        recompute(true);
        save();
    }

    // ---------- кластеризація ----------

    private static final class Acc {
        long sx, sy, sz;
        int n;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int score;
        int confirmed;
        long lastSeen;
        int[] counts = new int[StorageType.values().length];
        List<Base.Entry> list = new ArrayList<>();
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private static long cell(int cx, int cy, int cz) {
        return (cx & 0x1FFFFFL) | ((cy & 0x1FFFFFL) << 21) | ((long) (cz & 0x1FFFFF) << 42);
    }

    private static List<Base> cluster(List<Base.Entry> all, int r, int minScore) {
        int n = all.size();
        if (n == 0) return List.of();

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Base.Entry e = all.get(i);
            long k = cell(Math.floorDiv(e.x(), r), Math.floorDiv(e.y(), r), Math.floorDiv(e.z(), r));
            grid.computeIfAbsent(k, kk -> new ArrayList<>()).add(i);
        }

        long r2 = (long) r * r;
        for (int i = 0; i < n; i++) {
            Base.Entry a = all.get(i);
            int cx = Math.floorDiv(a.x(), r), cy = Math.floorDiv(a.y(), r), cz = Math.floorDiv(a.z(), r);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        List<Integer> l = grid.get(cell(cx + dx, cy + dy, cz + dz));
                        if (l == null) continue;
                        for (int j : l) {
                            if (j <= i) continue;
                            Base.Entry b = all.get(j);
                            long ddx = a.x() - b.x(), ddy = a.y() - b.y(), ddz = a.z() - b.z();
                            if (ddx * ddx + ddy * ddy + ddz * ddz <= r2) {
                                int ra = find(parent, i), rb = find(parent, j);
                                if (ra != rb) parent[ra] = rb;
                            }
                        }
                    }
                }
            }
        }

        Map<Integer, Acc> accs = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Base.Entry e = all.get(i);
            Acc acc = accs.computeIfAbsent(find(parent, i), k -> new Acc());
            acc.sx += e.x();
            acc.sy += e.y();
            acc.sz += e.z();
            acc.n++;
            acc.minX = Math.min(acc.minX, e.x());
            acc.minY = Math.min(acc.minY, e.y());
            acc.minZ = Math.min(acc.minZ, e.z());
            acc.maxX = Math.max(acc.maxX, e.x());
            acc.maxY = Math.max(acc.maxY, e.y());
            acc.maxZ = Math.max(acc.maxZ, e.z());
            acc.score += e.type().weight;
            if (e.confirmed()) acc.confirmed++;
            acc.lastSeen = Math.max(acc.lastSeen, e.seenAt());
            acc.counts[e.type().ordinal()]++;
            acc.list.add(e);
        }

        List<Base> out = new ArrayList<>();
        for (Acc a : accs.values()) {
            if (a.score < minScore) continue;
            out.add(new Base(
                    (int) Math.round(a.sx / (double) a.n),
                    (int) Math.round(a.sy / (double) a.n),
                    (int) Math.round(a.sz / (double) a.n),
                    a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ,
                    a.score, a.n, a.confirmed, a.lastSeen, a.counts, a.list));
        }
        return out;
    }

    // ---------- збереження ----------

    private static String storeKey(MinecraftClient mc, ClientWorld world) {
        String srv;
        ServerInfo si = mc.getCurrentServerEntry();
        if (si != null) {
            srv = si.address;
        } else {
            srv = "singleplayer";
            try {
                if (mc.getServer() != null) {
                    srv = "sp_" + mc.getServer().getSaveProperties().getLevelName();
                }
            } catch (Exception ignoredEx) {
            }
        }
        String dim = world.getRegistryKey().getValue().toString();
        return (srv + "__" + dim).replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("basefinder").resolve(currentKey + ".v2.json");
    }

    private static void save() {
        if (currentKey == null) return;
        try {
            List<int[]> out = new ArrayList<>();
            List<Long> ages = new ArrayList<>();
            for (List<Base.Entry> l : chunks.values()) {
                for (Base.Entry e : l) {
                    out.add(new int[]{e.x(), e.y(), e.z(), e.type().ordinal()});
                    ages.add(e.seenAt());
                }
            }
            long[] sa = new long[ages.size()];
            for (int i = 0; i < sa.length; i++) sa[i] = ages.get(i);
            long[] ign = new long[ignored.size()];
            int j = 0;
            for (long l : ignored) ign[j++] = l;

            Path f = file();
            Files.createDirectories(f.getParent());
            if (out.isEmpty() && ign.length == 0) {
                Files.deleteIfExists(f);
                return;
            }
            SaveData d = new SaveData();
            d.entries = out.toArray(new int[0][]);
            d.seenAt = sa;
            d.ignored = ign;
            try (Writer w = Files.newBufferedWriter(f)) {
                GSON.toJson(d, w);
            }
        } catch (Exception ex) {
            BaseFinderClient.LOGGER.warn("BaseHawk: не вдалося зберегти дані", ex);
        }
    }

    private static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            try (Reader r = Files.newBufferedReader(f)) {
                SaveData d = GSON.fromJson(r, SaveData.class);
                if (d == null) return;
                if (d.ignored != null) {
                    for (long l : d.ignored) ignored.add(l);
                }
                if (d.entries != null) {
                    StorageType[] vals = StorageType.values();
                    for (int i = 0; i < d.entries.length; i++) {
                        int[] a = d.entries[i];
                        if (a == null || a.length < 4 || a[3] < 0 || a[3] >= vals.length) continue;
                        if (ignored.contains(BlockPos.asLong(a[0], a[1], a[2]))) continue;
                        long seenAt = (d.seenAt != null && i < d.seenAt.length) ? d.seenAt[i] : 0L;
                        long key = ChunkPos.toLong(a[0] >> 4, a[2] >> 4);
                        // запис зі збереження: ще не перевірений у цій сесії
                        chunks.computeIfAbsent(key, k -> new ArrayList<>())
                                .add(new Base.Entry(a[0], a[1], a[2], vals[a[3]], 0, false, seenAt));
                    }
                }
            }
        } catch (Exception ex) {
            BaseFinderClient.LOGGER.warn("BaseHawk: не вдалося завантажити дані", ex);
        }
    }

    public static void onDisconnect() {
        save();
        resetState();
        currentKey = null;
    }

    /** Повне скидання: пам'ять баз, список ігнорованих і файл збереження. */
    public static void clearAll() {
        resetState();
        BaseFinderClient.target = null;
        try {
            if (currentKey != null) Files.deleteIfExists(file());
        } catch (Exception ignoredEx) {
        }
    }
}

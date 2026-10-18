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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Сканує завантажені клієнтом чанки, збирає контейнери (block entities),
 * запам'ятовує їх (навіть після вивантаження чанка) і групує в "бази".
 * Бачить тільки те, що сервер уже надіслав клієнту.
 */
public final class BaseScanner {
    private BaseScanner() {}

    private static final Gson GSON = new Gson();
    private static final Map<Long, List<Base.Entry>> chunks = new HashMap<>();
    /** Позиції контейнерів, які користувач видалив зі списку (щоб вони не з'являлись знову). */
    private static final Set<Long> ignored = new HashSet<>();
    private static final Set<Long> notified = new HashSet<>();
    /** Позиції, які клієнт відхилив як неправдиві/застарілі (фантомні блоки). */
    private static final Set<Long> rejected = new HashSet<>();
    private static volatile List<Base> bases = List.of();
    private static String currentKey = null;
    private static int tickCounter = 0;
    private static boolean dirty = false;

    private static final class SaveData {
        int[][] entries;
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
            chunks.clear();
            ignored.clear();
            notified.clear();
        rejected.clear();
            bases = List.of();
            load();
            recompute(true);
        }

        tickCounter++;
        if (tickCounter % Math.max(1, BaseFinderConfig.scanIntervalTicks) == 0) {
            scanLoaded(mc, world, player);
            dirty = true;
        }
        if (dirty && tickCounter % 10 == 0) {
            recompute(false);
            dirty = false;
        }
        if (tickCounter % 1200 == 0) save();
    }

    private static void scanLoaded(MinecraftClient mc, ClientWorld world, ClientPlayerEntity player) {
        int vd = mc.options.getClampedViewDistance();
        ChunkPos pc = player.getChunkPos();
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        double removeR2 = (double) BaseFinderConfig.removeRadius * BaseFinderConfig.removeRadius;
        for (int dx = -vd; dx <= vd; dx++) {
            for (int dz = -vd; dz <= vd; dz++) {
                int x = pc.x + dx;
                int z = pc.z + dz;
                WorldChunk ch = world.getChunkManager().getWorldChunk(x, z);
                if (ch == null || ch.isEmpty()) continue;
                long key = ChunkPos.toLong(x, z);

                // попередні записи цього чанка — щоб рахувати, скільки сканів поспіль блок на місці
                List<Base.Entry> old = chunks.get(key);
                Map<Long, Base.Entry> prev = null;
                if (old != null) {
                    prev = new HashMap<>();
                    for (Base.Entry e : old) prev.put(BlockPos.asLong(e.x(), e.y(), e.z()), e);
                }

                Map<Long, Base.Entry> now = new HashMap<>();
                for (BlockEntity be : new ArrayList<>(ch.getBlockEntities().values())) {
                    StorageType t = StorageType.of(be);
                    if (t == null) continue;
                    BlockPos p = be.getPos();
                    long pl = p.asLong();
                    if (ignored.contains(pl)) continue;
                    if (!isRealBlock(ch, be, p, t)) {
                        rejected.add(pl);
                        continue;
                    }
                    rejected.remove(pl);
                    Base.Entry before = prev == null ? null : prev.get(pl);
                    int seen = (before != null && before.type() == t) ? Math.min(before.seen() + 1, 1000) : 1;
                    now.put(pl, new Base.Entry(p.getX(), p.getY(), p.getZ(), t, seen, true));
                }

                // Сервер може показувати блоки лише поблизу. Тому запам'ятований блок, якого зараз
                // нема в чанку, видаляємо тільки якщо ми впритул до нього (він справді зник/фантом).
                // Здалеку він просто прихований сервером — залишаємо в пам'яті.
                List<Base.Entry> merged = new ArrayList<>(now.values());
                if (old != null) {
                    for (Base.Entry e : old) {
                        long pl = BlockPos.asLong(e.x(), e.y(), e.z());
                        if (now.containsKey(pl)) continue;
                        if (e.verified() && !e.confirmed()) continue; // мигнув і зник — фантом
                        double ddx = e.x() + 0.5 - px, ddy = e.y() + 0.5 - py, ddz = e.z() + 0.5 - pz;
                        if (ddx * ddx + ddy * ddy + ddz * ddz > removeR2) merged.add(e);
                    }
                }
                if (merged.isEmpty()) chunks.remove(key);
                else chunks.put(key, merged);
            }
        }
    }

    /**
     * Перевірка достовірності на боці клієнта: блок-ентіті не видалений, у світі справді стоїть
     * відповідний блок (не повітря), а закешований стан збігається зі станом у чанку.
     * Увага: клієнт не може перевірити, чи блок існує на сервері насправді — якщо сервер сам
     * надсилає підроблені блоки, ця перевірка їх не відрізнить. Тому додатково потрібне
     * повторне підтвердження кілька сканів поспіль (Entry.confirmed()).
     */
    private static boolean isRealBlock(WorldChunk ch, BlockEntity be, BlockPos p, StorageType t) {
        if (be.isRemoved()) return false;
        BlockState state = ch.getBlockState(p);
        if (state.isAir()) return false;
        if (state.getBlock() != be.getCachedState().getBlock()) return false;
        return switch (t) {
            case CHEST -> state.getBlock() instanceof ChestBlock;
            case BARREL -> state.getBlock() instanceof BarrelBlock;
            case SHULKER -> state.getBlock() instanceof ShulkerBoxBlock;
            case HOPPER -> state.getBlock() instanceof HopperBlock;
            case ENDER_CHEST -> state.getBlock() instanceof EnderChestBlock;
            case SPAWNER -> state.getBlock() instanceof SpawnerBlock;
        };
    }

    /** Скільки позицій клієнт відхилив як фантомні/застарілі. */
    public static int rejectedCount() {
        return rejected.size();
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
                    a.score, a.n, a.confirmed, a.counts, a.list));
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
            for (List<Base.Entry> l : chunks.values()) {
                for (Base.Entry e : l) out.add(new int[]{e.x(), e.y(), e.z(), e.type().ordinal()});
            }
            long[] ign = new long[ignored.size()];
            int i = 0;
            for (long l : ignored) ign[i++] = l;

            Path f = file();
            Files.createDirectories(f.getParent());
            if (out.isEmpty() && ign.length == 0) {
                Files.deleteIfExists(f);
                return;
            }
            SaveData d = new SaveData();
            d.entries = out.toArray(new int[0][]);
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
                    for (int[] a : d.entries) {
                        if (a.length < 4 || a[3] < 0 || a[3] >= vals.length) continue;
                        if (ignored.contains(BlockPos.asLong(a[0], a[1], a[2]))) continue;
                        long key = ChunkPos.toLong(a[0] >> 4, a[2] >> 4);
                        chunks.computeIfAbsent(key, k -> new ArrayList<>())
                                .add(new Base.Entry(a[0], a[1], a[2], vals[a[3]], 0, false));
                    }
                }
            }
        } catch (Exception ex) {
            BaseFinderClient.LOGGER.warn("BaseHawk: не вдалося завантажити дані", ex);
        }
    }

    public static void onDisconnect() {
        save();
        chunks.clear();
        ignored.clear();
        notified.clear();
        rejected.clear();
        bases = List.of();
        currentKey = null;
    }

    /** Повне скидання: пам'ять баз, список ігнорованих і файл збереження. */
    public static void clearAll() {
        chunks.clear();
        ignored.clear();
        notified.clear();
        rejected.clear();
        bases = List.of();
        BaseFinderClient.target = null;
        try {
            if (currentKey != null) Files.deleteIfExists(file());
        } catch (Exception ignoredEx) {
        }
    }
}

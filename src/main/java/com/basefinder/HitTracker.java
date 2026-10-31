package com.basefinder;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Шукає "дамагери" за поведінкою: там, де моби регулярно отримують удари, а гравців поруч нема.
 * Це може бути блок-дамагер, меч-пастка, кактуси, магма тощо. Удар визначається по hurtTime мобів.
 */
final class HitTracker {
    /** Вікно підрахунку ударів, тіки (10 с). */
    private static final int WINDOW = 200;
    /** Скільки ударів у вікні в одній клітинці 8x8x8 вважається "ударною точкою". */
    private static final int MIN_HITS = 6;
    /** Скільки тіків точка лишається активною після останнього підтвердження (5 хв). */
    private static final int KEEP = 6000;
    /** Якщо гравець ближче за цю відстань (блоків), удар вважається боєм гравця й ігнорується. */
    private static final double PLAYER_R2 = 5.0 * 5.0;

    private record Cell(int x, int y, int z) {}

    private static final class State {
        final ArrayDeque<Integer> hits = new ArrayDeque<>();
        int lastActive = Integer.MIN_VALUE;
    }

    private static final Map<Cell, State> cells = new HashMap<>();
    /** Останній тік, коли ми зарахували удар конкретному мобу (щоб один удар не рахувався двічі). */
    private static final Map<Integer, Integer> lastHit = new HashMap<>();

    private HitTracker() {}

    static void reset() {
        cells.clear();
        lastHit.clear();
    }

    /** Викликається щотіка. */
    static void tick(ClientWorld world, int now) {
        List<? extends PlayerEntity> players = null;
        for (Entity e : world.getEntities()) {
            if (!(e instanceof MobEntity m) || m.isRemoved()) continue;
            // свіжий удар: hurtTime щойно виставлено (до кінця тіка вже зменшився на 0-1)
            if (m.hurtTime <= 0 || m.hurtTime < m.maxHurtTime - 1) continue;
            if (m.isOnFire()) continue; // горіння/сонце — не удари дамагера
            Integer prev = lastHit.get(m.getId());
            if (prev != null && now - prev < 5) continue;
            lastHit.put(m.getId(), now);

            if (players == null) players = world.getPlayers();
            boolean playerNear = false;
            for (PlayerEntity p : players) {
                if (p.squaredDistanceTo(m) <= PLAYER_R2) { playerNear = true; break; }
            }
            if (playerNear) continue;

            BlockPos b = m.getBlockPos();
            State st = cells.computeIfAbsent(new Cell(b.getX() >> 3, b.getY() >> 3, b.getZ() >> 3), k -> new State());
            st.hits.addLast(now);
            while (!st.hits.isEmpty() && st.hits.peekFirst() < now - WINDOW) st.hits.pollFirst();
            if (st.hits.size() >= MIN_HITS) st.lastActive = now;
        }
        if (now % 200 == 0) cleanup(now);
    }

    private static void cleanup(int now) {
        lastHit.values().removeIf(t -> now - t > WINDOW);
        Iterator<Map.Entry<Cell, State>> it = cells.entrySet().iterator();
        while (it.hasNext()) {
            State st = it.next().getValue();
            while (!st.hits.isEmpty() && st.hits.peekFirst() < now - WINDOW) st.hits.pollFirst();
            if (st.hits.isEmpty() && now - st.lastActive > KEEP) it.remove();
        }
    }

    /** Центри клітинок, де зараз (або недавно) фіксувалися часті удари мобам. */
    static List<BlockPos> activeCenters(int now) {
        List<BlockPos> out = new ArrayList<>();
        for (Map.Entry<Cell, State> en : cells.entrySet()) {
            State st = en.getValue();
            if (st.lastActive == Integer.MIN_VALUE || now - st.lastActive > KEEP) continue;
            Cell c = en.getKey();
            out.add(new BlockPos(c.x() * 8 + 4, c.y() * 8 + 4, c.z() * 8 + 4));
        }
        return out;
    }
}

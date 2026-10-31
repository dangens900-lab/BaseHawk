package com.basefinder;

import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.EnchantingTableBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.passive.SnifferEntity;
import net.minecraft.entity.vehicle.VehicleInventory;

public enum StorageType {
    CHEST(1, "скринь", "Скриня", 0xFFFFD27F),
    BARREL(1, "бочок", "Бочка", 0xFFC98B4B),
    SHULKER(3, "шалкерів", "Шалкер", 0xFFE070FF),
    HOPPER(1, "воронок", "Воронка", 0xFFBBBBBB),
    ENDER_CHEST(1, "ендер-скринь", "Ендер-скриня", 0xFFB07CFF),
    SPAWNER(2, "спавнерів", "Спавнер", 0xFFFF6A6A),
    // Нові типи додано В КІНЕЦЬ: збереження використовує номер типу (ordinal), порядок змінювати не можна.
    FURNACE(1, "печей", "Піч", 0xFFFF9A3C),
    DISPENSER(1, "роздавачів", "Роздавач/дропер", 0xFF8FD3FF),
    BREWING_STAND(1, "варильних стійок", "Варильна стійка", 0xFF7CFFB2),
    BEACON(2, "маяків", "Маяк", 0xFF4DFFFF),
    ENCHANTING(1, "столів зачарування", "Стіл зачарування", 0xFFFF7CD8),
    SIGN(1, "табличок", "Табличка", 0xFFE8D9A0),
    // Сутності (видно лише поблизу — у межах дальності відстеження сервера)
    ITEM_FRAME(1, "рамок", "Рамка", 0xFFFFB347),
    ARMOR_STAND(1, "стійок для обладунку", "Стійка для обладунку", 0xFFB5E61D),
    CHEST_VEHICLE(1, "вагонеток/човнів із скринею", "Транспорт зі скринею", 0xFF9FA8FF),
    SNIFFER(2, "нюхачів", "Нюхач", 0xFF5FD38A),
    // Скупчення (групи сутностей в одній клітинці 8x8x8): окремий моб запису не дає
    VILLAGERS(2, "груп жителів", "Жителі (група)", 0xFF9BE564),
    MOB_FARM(3, "ферм мобів", "Ферма мобів (скупчення)", 0xFFFF5555),
    // Поведінкова ціль: місце, де моби регулярно отримують удари без гравця поруч (дамагер, пастка)
    DAMAGER(6, "ударних точок", "Дамагер (удари мобів)", 0xFFFF2D2D);

    public final int weight;
    public final String label;
    /** Назва в однині для голограми. */
    public final String title;
    /** Колір підпису (ARGB). */
    public final int color;

    StorageType(int weight, String label, String title, int color) {
        this.weight = weight;
        this.label = label;
        this.title = title;
        this.color = color;
    }

    /** Повертає тип, якщо це "базовий" блок, інакше null. */
    public static StorageType of(BlockEntity be) {
        if (be instanceof ShulkerBoxBlockEntity) return SHULKER;
        if (be instanceof ChestBlockEntity) return CHEST;
        if (be instanceof EnderChestBlockEntity) return ENDER_CHEST;
        if (be instanceof BarrelBlockEntity) return BARREL;
        if (be instanceof HopperBlockEntity) return HOPPER;
        if (be instanceof MobSpawnerBlockEntity) return SPAWNER;
        if (!BaseFinderConfig.extraTypes || !BaseFinderConfig.extraBlocks) return null;
        if (be instanceof AbstractFurnaceBlockEntity) return FURNACE;
        if (be instanceof DispenserBlockEntity) return DISPENSER; // включно з дропером
        if (be instanceof BrewingStandBlockEntity) return BREWING_STAND;
        if (be instanceof BeaconBlockEntity) return BEACON;
        if (be instanceof EnchantingTableBlockEntity) return ENCHANTING;
        if (be instanceof SignBlockEntity) return SIGN; // звичайні й підвісні таблички
        return null;
    }

    /** Тип для сутності (рамка, стійка, транспорт із інвентарем) або null. Лише коли увімкнено extraTypes. */
    public static StorageType ofEntity(Entity e) {
        if (!BaseFinderConfig.extraTypes || !BaseFinderConfig.extraEntities) return null;
        if (e instanceof ItemFrameEntity) return ITEM_FRAME;      // включно зі світними рамками
        if (e instanceof ArmorStandEntity) return ARMOR_STAND;
        if (e instanceof VehicleInventory) return CHEST_VEHICLE;  // вагонетки зі скринею/воронкою, човни зі скринею
        if (e instanceof SnifferEntity) return SNIFFER;
        return null;
    }

    /** Чи увімкнена категорія цього типу в налаштуваннях (базові типи завжди увімкнені). */
    public boolean enabled() {
        if (!BaseFinderConfig.extraTypes) return ordinal() <= SPAWNER.ordinal();
        return switch (this) {
            case FURNACE, DISPENSER, BREWING_STAND, BEACON, ENCHANTING, SIGN -> BaseFinderConfig.extraBlocks;
            case ITEM_FRAME, ARMOR_STAND, CHEST_VEHICLE, SNIFFER -> BaseFinderConfig.extraEntities;
            case VILLAGERS, MOB_FARM, DAMAGER -> BaseFinderConfig.extraGroups;
            default -> true;
        };
    }
}

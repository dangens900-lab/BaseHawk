package com.basefinder;

import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;

public enum StorageType {
    CHEST(1, "скринь", "Скриня", 0xFFFFD27F),
    BARREL(1, "бочок", "Бочка", 0xFFC98B4B),
    SHULKER(3, "шалкерів", "Шалкер", 0xFFE070FF),
    HOPPER(1, "воронок", "Воронка", 0xFFBBBBBB),
    ENDER_CHEST(1, "ендер-скринь", "Ендер-скриня", 0xFFB07CFF),
    SPAWNER(2, "спавнерів", "Спавнер", 0xFFFF6A6A);

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
        return null;
    }
}

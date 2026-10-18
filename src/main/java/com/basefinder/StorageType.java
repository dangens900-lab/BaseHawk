package com.basefinder;

import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;

public enum StorageType {
    CHEST(1, "скринь"),
    BARREL(1, "бочок"),
    SHULKER(3, "шалкерів"),
    HOPPER(1, "воронок"),
    ENDER_CHEST(1, "ендер-скринь"),
    SPAWNER(2, "спавнерів");

    public final int weight;
    public final String label;

    StorageType(int weight, String label) {
        this.weight = weight;
        this.label = label;
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

package com.basefinder;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.List;

public class BaseFinderScanner {

    // Максимальний радіус у блоках (160 блоків = 10 чанків)
    private static final int RADIUS_BLOCKS = 160;
    private static final int RADIUS_CHUNKS = RADIUS_BLOCKS / 16; 

    public static List<BlockPos> scanForStashes() {
        List<BlockPos> foundStashes = new ArrayList<>();
        MinecraftClient client = MinecraftClient.getInstance();
        
        if (client.player == null || client.world == null) return foundStashes;

        World world = client.world;
        BlockPos playerPos = client.player.getBlockPos();
        
        // Визначаємо чанк, в якому стоїть гравець
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;

        // Скануємо чанки в радіусі 10 штук навколо гравця (радіус 160 блоків)
        for (int cx = -RADIUS_CHUNKS; cx <= RADIUS_CHUNKS; cx++) {
            for (int cz = -RADIUS_CHUNKS; cz <= RADIUS_CHUNKS; cz++) {
                
                int currentChunkX = centerChunkX + cx;
                int currentChunkZ = centerChunkZ + cz;

                // Перевіряємо, чи цей чанк взагалі завантажений твоїм комп'ютером
                if (!world.getChunkManager().isChunkLoaded(currentChunkX, currentChunkZ)) {
                    continue; // Якщо чанк ще не прогрузився, пропускаємо його, щоб не лагало
                }

                Chunk chunk = world.getChunk(currentChunkX, currentChunkZ, ChunkStatus.FULL, false);
                if (chunk == null) continue;

                // Скануємо блоки всередині цього чанку (16х16 блоків) від бедроку до неба
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        // Перебираємо висоту у світі (від -64 до 320 у нових версіях)
                        for (int y = world.getBottomY(); y < world.getTopY(); y++) {
                            
                            BlockPos worldPos = new BlockPos(
                                (currentChunkX << 4) + x,
                                y,
                                (currentChunkZ << 4) + z
                            );

                            // Перевіряємо тип блоку через Стейт (це швидше)
                            var blockState = chunk.getBlockState(worldPos);
                            
                            if (blockState.isOf(Blocks.CHEST) || 
                                blockState.isOf(Blocks.TRAPPED_CHEST) || 
                                blockState.isOf(Blocks.SHULKER_BOX) || 
                                blockState.isOf(Blocks.BARREL)) {
                                
                                foundStashes.add(worldPos);
                                
                                // Якщо у тебе підключений StashManager з минулого кроку,
                                // він відразу запише координати в файл, щоб вони не зникли
                                StashManager.checkAndLog(worldPos, blockState.getBlock().getName().getString());
                            }
                        }
                    }
                }
            }
        }
        return foundStashes;
    }
}

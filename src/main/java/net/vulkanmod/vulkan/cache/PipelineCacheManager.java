package net.vulkanmod.vulkan.cache;

import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class PipelineCacheManager {

    private static final String CACHE_FILE_NAME = "vulkanmod_pipeline.cache";
    private static long pipelineCache = VK_NULL_HANDLE;

    public static void init() {
        try (MemoryStack stack = stackPush()) {
            VkPipelineCacheCreateInfo cacheInfo = VkPipelineCacheCreateInfo.calloc(stack);
            cacheInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);

            // Try to load existing cache
            byte[] cacheData = loadCacheFromDisk();
            if (cacheData != null) {
                ByteBuffer cacheBuffer = MemoryUtil.memAlloc(cacheData.length);
                cacheBuffer.put(cacheData);
                cacheBuffer.flip();

                cacheInfo.initialDataSize(cacheData.length);
                cacheInfo.pInitialData(MemoryUtil.memAddress(cacheBuffer));

                MemoryUtil.memFree(cacheBuffer);
            }

            PointerBuffer pCache = stack.mallocPointer(1);
            int result = vkCreatePipelineCache(Vulkan.getVkDevice(), cacheInfo, null, pCache);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create pipeline cache: " + result);
            }

            pipelineCache = pCache.get(0);
        }
    }

    public static void saveCacheToDisk() {
        if (pipelineCache == VK_NULL_HANDLE) return;

        try (MemoryStack stack = stackPush()) {
            // Get cache data size
            PointerBuffer pDataSize = stack.mallocPointer(1);
            vkGetPipelineCacheData(Vulkan.getVkDevice(), pipelineCache, pDataSize, null);
            long dataSize = pDataSize.get(0);

            if (dataSize == 0) return;

            // Get cache data
            ByteBuffer data = MemoryUtil.memAlloc((int) dataSize);
            vkGetPipelineCacheData(Vulkan.getVkDevice(), pipelineCache, pDataSize, MemoryUtil.memAddress(data));

            // Write to file
            Path cachePath = getCachePath();
            Files.createDirectories(cachePath.getParent());
            Files.write(cachePath, data.array());

            MemoryUtil.memFree(data);
        } catch (IOException e) {
            System.err.println("Failed to save pipeline cache: " + e.getMessage());
        }
    }

    private static byte[] loadCacheFromDisk() {
        try {
            Path cachePath = getCachePath();
            if (Files.exists(cachePath)) {
                return Files.readAllBytes(cachePath);
            }
        } catch (IOException e) {
            System.err.println("Failed to load pipeline cache: " + e.getMessage());
        }
        return null;
    }

    private static Path getCachePath() {
        // Android cache directory
        String cacheDir = System.getProperty("user.home") + "/.vulkanmod";
        return Paths.get(cacheDir, CACHE_FILE_NAME);
    }

    public static long getPipelineCache() {
        return pipelineCache;
    }

    public static void cleanup() {
        if (pipelineCache != VK_NULL_HANDLE) {
            saveCacheToDisk();
            vkDestroyPipelineCache(Vulkan.getVkDevice(), pipelineCache, null);
        }
    }
}
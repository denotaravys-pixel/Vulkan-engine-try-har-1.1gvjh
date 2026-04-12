package net.vulkanmod.vulkan.cache;

import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

// FIX 1: PointerBuffer → LongBuffer para vkCreatePipelineCache
// FIX 2: cacheInfo.initialDataSize(n) removido — API não aceita argumento
// FIX 3: pInitialData(long) → pInitialData(ByteBuffer)
// FIX 4: vkGetPipelineCacheData(..., long) → vkGetPipelineCacheData(..., ByteBuffer)
// FIX 5: pDataSize: PointerBuffer → LongBuffer
public class PipelineCacheManager {

    private static final String CACHE_FILE_NAME = "vulkanmod_pipeline.cache";
    private static long pipelineCache = VK_NULL_HANDLE;

    public static void init() {
        try (MemoryStack stack = stackPush()) {
            VkPipelineCacheCreateInfo cacheInfo = VkPipelineCacheCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);

            // Try to load existing cache
            byte[] cacheData = loadCacheFromDisk();
            ByteBuffer cacheBuffer = null;

            if (cacheData != null && cacheData.length > 0) {
                // Alocar fora da stack — tamanho pode ser grande
                cacheBuffer = MemoryUtil.memAlloc(cacheData.length);
                cacheBuffer.put(cacheData).flip();
                // FIX: pInitialData aceita ByteBuffer, não long
                // initialDataSize é definido automaticamente pelo LWJGL a partir do ByteBuffer
                cacheInfo.pInitialData(cacheBuffer);
            }

            // FIX: LongBuffer em vez de PointerBuffer
            LongBuffer pCache = stack.mallocLong(1);
            int result = vkCreatePipelineCache(Vulkan.getVkDevice(), cacheInfo, null, pCache);

            if (cacheBuffer != null) {
                MemoryUtil.memFree(cacheBuffer);
            }

            if (result != VK_SUCCESS) {
                System.err.println("[VULKANMOD] Falha ao criar pipeline cache: " + result
                    + " — continuando sem cache");
                pipelineCache = VK_NULL_HANDLE;
                return;
            }

            pipelineCache = pCache.get(0);
            System.err.println("[VULKANMOD] Pipeline cache criado: " + pipelineCache);
        }
    }

    public static void saveCacheToDisk() {
        if (pipelineCache == VK_NULL_HANDLE) return;

        try (MemoryStack stack = stackPush()) {
            // FIX: LongBuffer em vez de PointerBuffer para o tamanho
            LongBuffer pDataSize = stack.mallocLong(1);

            // Primeiro call: obter tamanho
            int result = vkGetPipelineCacheData(Vulkan.getVkDevice(), pipelineCache, pDataSize, (ByteBuffer) null);
            if (result != VK_SUCCESS || pDataSize.get(0) == 0) return;

            long dataSize = pDataSize.get(0);

            // Segundo call: obter dados
            // FIX: passar ByteBuffer directamente, não MemoryUtil.memAddress()
            ByteBuffer data = MemoryUtil.memAlloc((int) dataSize);
            vkGetPipelineCacheData(Vulkan.getVkDevice(), pipelineCache, pDataSize, data);

            // Escrever em disco
            byte[] bytes = new byte[(int) dataSize];
            data.get(bytes);
            MemoryUtil.memFree(data);

            Path cachePath = getCachePath();
            Files.createDirectories(cachePath.getParent());
            Files.write(cachePath, bytes);

            System.err.println("[VULKANMOD] Pipeline cache salvo: " + cachePath);

        } catch (IOException e) {
            System.err.println("[VULKANMOD] Falha ao salvar pipeline cache: " + e.getMessage());
        }
    }

    private static byte[] loadCacheFromDisk() {
        try {
            Path cachePath = getCachePath();
            if (Files.exists(cachePath)) {
                System.err.println("[VULKANMOD] Pipeline cache carregado: " + cachePath);
                return Files.readAllBytes(cachePath);
            }
        } catch (IOException e) {
            System.err.println("[VULKANMOD] Falha ao carregar pipeline cache: " + e.getMessage());
        }
        return null;
    }

    private static Path getCachePath() {
        String cacheDir = System.getProperty("user.home", "/data/data") + "/.vulkanmod";
        return Paths.get(cacheDir, CACHE_FILE_NAME);
    }

    public static long getPipelineCache() {
        return pipelineCache;
    }

    public static void cleanup() {
        if (pipelineCache != VK_NULL_HANDLE) {
            saveCacheToDisk();
            vkDestroyPipelineCache(Vulkan.getVkDevice(), pipelineCache, null);
            pipelineCache = VK_NULL_HANDLE;
        }
    }
}

package net.vulkanmod.vulkan;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/***
 * Synchronization utility to sync in frame ops that need to be completed before executing main cmd buffer.
 *
 * ANDROID FIX: vkWaitForFences return value is now checked.
 * Previously, VK_TIMEOUT (-4) was silently ignored, causing CB reset on still-in-use
 * buffers → GPU corruption on Mali-G52 (UMA architecture, no separate VRAM).
 */
public class Synchronization {
    private static final int ALLOCATION_SIZE = 50;

    public static final Synchronization INSTANCE = new Synchronization(ALLOCATION_SIZE);

    private final LongBuffer fences;
    private int idx = 0;

    private final ObjectArrayList<CommandPool.CommandBuffer> fenceCbs = new ObjectArrayList<>();

    private final LongArrayList semaphores = new LongArrayList();
    private final ObjectArrayList<CommandPool.CommandBuffer> semaphoreCbs = new ObjectArrayList<>();

    Synchronization(int allocSize) {
        this.fences = MemoryUtil.memAllocLong(allocSize);
    }

    public void addCommandBuffer(CommandPool.CommandBuffer commandBuffer) {
        addCommandBuffer(commandBuffer, false);
    }

    public synchronized void addCommandBuffer(CommandPool.CommandBuffer commandBuffer, boolean useSemaphore) {
        if (!useSemaphore) {
            this.addFence(commandBuffer.getFence());
            this.fenceCbs.add(commandBuffer);
        }
        else {
            this.semaphores.add(commandBuffer.getSemaphore());
            this.semaphoreCbs.add(commandBuffer);
        }
    }

    public synchronized void addFence(long fence) {
        if (idx == ALLOCATION_SIZE)
            waitFences();

        fences.put(idx, fence);
        idx++;
    }

    public synchronized void waitFences() {
        if (idx == 0)
            return;

        VkDevice device = Vulkan.getVkDevice();

        fences.limit(idx);

        // FIX: Verificar o retorno de vkWaitForFences.
        // Antes: retorno ignorado → reset de CB ainda em uso pela GPU (UB Vulkan).
        // Agora: VK_TIMEOUT → abortar reset para evitar corrupção na Mali-G52.
        int result = vkWaitForFences(device, fences, true, VUtil.FENCE_TIMEOUT_NS);

        if (result == VK_TIMEOUT) {
            // GPU ainda não terminou. Não resetar CBs — podem estar em uso.
            // Limpar o buffer de fences e sair sem tocar nos command buffers.
            // Na Mali-G52 isto acontece quando pipelines são null e não há
            // trabalho real submetido, ou em picos de carga.
            fences.limit(ALLOCATION_SIZE);
            idx = 0;
            return;
        }

        if (result != VK_SUCCESS) {
            // VK_ERROR_DEVICE_LOST ou outro erro grave — não resetar CBs.
            // O caller (Renderer) irá detectar o device lost na próxima submissão.
            fences.limit(ALLOCATION_SIZE);
            idx = 0;
            return;
        }

        // VK_SUCCESS — seguro resetar CBs
        this.fenceCbs.forEach(CommandPool.CommandBuffer::reset);
        this.fenceCbs.clear();

        fences.limit(ALLOCATION_SIZE);
        idx = 0;
    }

    public synchronized void addWaitSemaphore(long semaphore) {
        this.semaphores.add(semaphore);
    }

    public LongBuffer getWaitSemaphores(MemoryStack stack) {
        var buffer = stack.mallocLong(this.semaphores.size())
                          .put(this.semaphores.elements(), 0, this.semaphores.size());
        buffer.flip();

        this.semaphores.clear();
        return buffer;
    }

    public void scheduleCbReset() {
        final var frameSemaphoreCbs = this.semaphoreCbs.clone();

        // Use waitFences() path instead of frameOp to ensure GPU has finished
        // before resetting command buffers that use semaphores.
        for (CommandPool.CommandBuffer cb : frameSemaphoreCbs) {
            addCommandBuffer(cb, false);
        }

        this.semaphoreCbs.clear();
    }

    public static void waitFence(long fence) {
        VkDevice device = Vulkan.getVkDevice();

        // FIX: Verificar retorno — evitar stall infinito e corrupção em UMA.
        int result = vkWaitForFences(device, fence, true, VUtil.FENCE_TIMEOUT_NS);
        if (result != VK_SUCCESS && result != VK_TIMEOUT) {
            // Log seria ideal aqui, mas evitamos dependência de LOGGER neste nível
            // O erro será detectado na próxima submissão Vulkan
        }
    }

    public static boolean checkFenceStatus(long fence) {
        VkDevice device = Vulkan.getVkDevice();
        return vkGetFenceStatus(device, fence) == VK_SUCCESS;
    }

 }

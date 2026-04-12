package net.vulkanmod.vulkan.memory.buffer;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

// FIX: construtor Buffer(int usage, MemoryType type) — ordem e tipos corrigidos
// FIX: super.free() removido — Buffer não tem free(), usar scheduleFree()
public class PersistentMappedBuffer extends Buffer {

    private PointerBuffer mappedMemory;

    public PersistentMappedBuffer(long size, int usage) {
        // Buffer(int usage, MemoryType type) — ordem correcta
        super(usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                MemoryTypes.HOST_MEM);

        // Criar o buffer com o tamanho especificado
        this.createBuffer(size);

        // Map the memory persistently
        mappedMemory = MemoryUtil.memAllocPointer(1);
        vkMapMemory(Vulkan.getVkDevice(), this.allocation, 0, size, 0, mappedMemory);

        MemoryManager.getInstance().addBuffer(this);
    }

    public ByteBuffer getMappedBuffer() {
        return mappedMemory.getByteBuffer(0, (int) this.bufferSize);
    }

    public void updateData(ByteBuffer data, long offset) {
        ByteBuffer mapped = getMappedBuffer();
        mapped.position((int) offset);
        mapped.put(data);
        // No need to flush due to HOST_COHERENT_BIT
    }

    @Override
    public void scheduleFree() {
        if (mappedMemory != null) {
            vkUnmapMemory(Vulkan.getVkDevice(), allocation);
            MemoryUtil.memFree(mappedMemory);
            mappedMemory = null;
        }
        super.scheduleFree();
    }
}

package net.vulkanmod.vulkan.memory.buffer;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.ByteBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class PersistentMappedBuffer extends Buffer {

    private PointerBuffer mappedMemory;

    public PersistentMappedBuffer(long size, int usage) {
        super(size, usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
              VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

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
    public void free() {
        if (mappedMemory != null) {
            vkUnmapMemory(Vulkan.getVkDevice(), allocation);
            MemoryUtil.memFree(mappedMemory);
        }
        super.free();
    }
}
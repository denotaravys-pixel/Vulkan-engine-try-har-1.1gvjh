package net.vulkanmod.vulkan.memory;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.buffer.AreaBuffer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.Pair;
import net.vulkanmod.vulkan.util.VkResult;
import org.apache.commons.lang3.Validate;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class MemoryManager {
    private static final boolean DEBUG = false;
    public static final long BYTES_IN_MB = 1024 * 1024;

    private static MemoryManager INSTANCE;

    private static final Long2ReferenceOpenHashMap<Buffer> buffers = new Long2ReferenceOpenHashMap<>();
    private static final Long2ReferenceOpenHashMap<VulkanImage> images = new Long2ReferenceOpenHashMap<>();

    static int Frames;

    private static long deviceMemory = 0;
    private static long nativeMemory = 0;

    private int currentFrame = 0;

    private final ObjectArrayList<Buffer.BufferInfo>[] freeableBuffers;
    private final ObjectArrayList<VulkanImage>[] freeableImages;
    private final ObjectArrayList<Runnable>[] frameOps;
    private final ObjectArrayList<Pair<AreaBuffer, Integer>>[] segmentsToFree;

    private ObjectArrayList<StackTraceElement[]>[] stackTraces;

    public static MemoryManager getInstance() {
        return INSTANCE;
    }

    public static void createInstance(int frames) {
        Frames = frames;
        INSTANCE = new MemoryManager();
    }

    @SuppressWarnings("unchecked")
    MemoryManager() {
        freeableBuffers = new ObjectArrayList[Frames];
        freeableImages = new ObjectArrayList[Frames];
        frameOps = new ObjectArrayList[Frames];
        segmentsToFree = new ObjectArrayList[Frames];

        for (int i = 0; i < Frames; ++i) {
            freeableBuffers[i] = new ObjectArrayList<>();
            freeableImages[i] = new ObjectArrayList<>();
            frameOps[i] = new ObjectArrayList<>();
            segmentsToFree[i] = new ObjectArrayList<>();
        }

        if (DEBUG) {
            stackTraces = new ObjectArrayList[Frames];
            for (int i = 0; i < Frames; ++i) {
                stackTraces[i] = new ObjectArrayList<>();
            }
        }
    }

    public synchronized void initFrame(int frame) {
        this.freeBuffers(frame);
        this.freeImages(frame);
        this.doFrameOps(frame);
        this.freeSegments(frame);
        this.setCurrentFrame(frame);
    }

    public void setCurrentFrame(int frame) {
        Validate.isTrue(frame < Frames, "Out of bounds frame index");
        this.currentFrame = frame;
    }

    public void freeAllBuffers() {
        for (int frame = 0; frame < Frames; ++frame) {
            this.freeBuffers(frame);
            this.freeImages(frame);
            this.doFrameOps(frame);
        }
    }

    // ─── Alocação manual Vulkan 1.1 (substitui VMA) ───────────────────────────

    private int findMemoryType(int typeFilter, int properties) {
        VkPhysicalDeviceMemoryProperties memProperties = DeviceManager.memoryProperties;
        for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
            int flags = memProperties.memoryTypes(i).propertyFlags();
            if ((typeFilter & (1 << i)) != 0 && (flags & properties) == properties) {
                return i;
            }
        }
        // Fallback — tenta sem o filtro de tipo
        for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
            int flags = memProperties.memoryTypes(i).propertyFlags();
            if ((flags & properties) == properties) {
                return i;
            }
        }
        throw new RuntimeException(
                "Failed to find suitable memory type. Filter: " + typeFilter + " Properties: " + properties);
    }

    public void createBuffer(long size, int usage, int properties,
            LongBuffer pBuffer, PointerBuffer pBufferMemory) {
        try (MemoryStack stack = stackPush()) {
            // 1. Criar o buffer
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack);
            bufferInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            bufferInfo.size(size);
            bufferInfo.usage(usage);
            bufferInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            int result = vkCreateBuffer(DeviceManager.vkDevice, bufferInfo, null, pBuffer);
            if (result != VK_SUCCESS)
                throw new RuntimeException("Failed to create buffer: " + VkResult.decode(result));

            // 2. Obter requisitos de memória
            VkMemoryRequirements memReqs = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(DeviceManager.vkDevice, pBuffer.get(0), memReqs);

            // 3. Alocar memória
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
            allocInfo.allocationSize(memReqs.size());
            allocInfo.memoryTypeIndex(findMemoryType(memReqs.memoryTypeBits(), properties));

            // pBufferMemory é PointerBuffer mas vkAllocateMemory precisa LongBuffer
            LongBuffer pMemory = stack.mallocLong(1);
            result = vkAllocateMemory(DeviceManager.vkDevice, allocInfo, null, pMemory);
            if (result != VK_SUCCESS)
                throw new RuntimeException("Failed to allocate buffer memory: " + VkResult.decode(result));

            long memory = pMemory.get(0);

            // 4. Bind buffer à memória
            vkBindBufferMemory(DeviceManager.vkDevice, pBuffer.get(0), memory, 0);

            // 5. Guardar o handle de memória no PointerBuffer (usado como "allocation")
            pBufferMemory.put(0, memory);
        }
    }

    public synchronized void createBuffer(Buffer buffer, long size, int usage, int properties) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.pointers(VK_NULL_HANDLE);

            this.createBuffer(size, usage, properties, pBuffer, pAllocation);

            buffer.setId(pBuffer.get(0));
            buffer.setAllocation(pAllocation.get(0));
            buffer.setBufferSize(size);

            if ((properties & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0)
                deviceMemory += size;
            else
                nativeMemory += size;

            buffers.putIfAbsent(buffer.getId(), buffer);
        }
    }

    public synchronized void addBuffer(Buffer buffer) {
        buffers.put(buffer.getId(), buffer);
    }

    public void createImage(int width, int height, int arrayLayers, int mipLevels,
            int format, int tiling, int usage, int flags,
            int memProperties,
            LongBuffer pTextureImage, PointerBuffer pTextureImageMemory) {
        try (MemoryStack stack = stackPush()) {
            // 1. Criar imagem
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack);
            imageInfo.sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            imageInfo.imageType(VK_IMAGE_TYPE_2D);
            imageInfo.extent().width(width);
            imageInfo.extent().height(height);
            imageInfo.extent().depth(1);
            imageInfo.mipLevels(mipLevels);
            imageInfo.arrayLayers(arrayLayers);
            imageInfo.format(format);
            imageInfo.tiling(tiling);
            imageInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.usage(usage);
            imageInfo.samples(VK_SAMPLE_COUNT_1_BIT);
            imageInfo.flags(flags);
            imageInfo.pQueueFamilyIndices(
                    stack.ints(Queue.getQueueFamilies().graphicsFamily,
                            Queue.getQueueFamilies().computeFamily));

            int result = vkCreateImage(DeviceManager.vkDevice, imageInfo, null, pTextureImage);
            if (result != VK_SUCCESS)
                throw new RuntimeException("Failed to create image: " + VkResult.decode(result));

            // 2. Obter requisitos de memória
            VkMemoryRequirements memReqs = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(DeviceManager.vkDevice, pTextureImage.get(0), memReqs);

            // 3. Alocar memória
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
            allocInfo.allocationSize(memReqs.size());

            // Use lazy memory for depth/stencil formats to avoid bandwidth cost
            int memoryProperties = memProperties;
            if (isDepthFormat(format) && MemoryTypes.LAZY_DEPTH_MEM != null) {
                memoryProperties = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT;
            }

            allocInfo.memoryTypeIndex(findMemoryType(memReqs.memoryTypeBits(), memoryProperties));

            LongBuffer pMemory = stack.mallocLong(1);
            result = vkAllocateMemory(DeviceManager.vkDevice, allocInfo, null, pMemory);
            if (result != VK_SUCCESS)
                throw new RuntimeException("Failed to allocate image memory: " + VkResult.decode(result));

            long memory = pMemory.get(0);

            // 4. Bind imagem à memória
            vkBindImageMemory(DeviceManager.vkDevice, pTextureImage.get(0), memory, 0);

            pTextureImageMemory.put(0, memory);
        }
    }

    private static boolean isDepthFormat(int format) {
        return format == VK_FORMAT_D16_UNORM ||
                format == VK_FORMAT_D32_SFLOAT ||
                format == VK_FORMAT_D16_UNORM_S8_UINT ||
                format == VK_FORMAT_D24_UNORM_S8_UINT ||
                format == VK_FORMAT_D32_SFLOAT_S8_UINT;
    }

    public static void addImage(VulkanImage image) {
        images.putIfAbsent(image.getId(), image);
        deviceMemory += image.size;
    }

    public static void MapAndCopy(long allocation, Consumer<PointerBuffer> consumer) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer data = stack.mallocPointer(1);
            vkMapMemory(DeviceManager.vkDevice, allocation, 0, VK_WHOLE_SIZE, 0, data);
            consumer.accept(data);
            vkUnmapMemory(DeviceManager.vkDevice, allocation);
        }
    }

    public PointerBuffer Map(long allocation) {
        PointerBuffer data = MemoryUtil.memAllocPointer(1);
        vkMapMemory(DeviceManager.vkDevice, allocation, 0, VK_WHOLE_SIZE, 0, data);
        return data;
    }

    public static void freeBuffer(long buffer, long allocation) {
        vkDestroyBuffer(DeviceManager.vkDevice, buffer, null);
        vkFreeMemory(DeviceManager.vkDevice, allocation, null);
        buffers.remove(buffer);
    }

    private static void freeBuffer(Buffer.BufferInfo bufferInfo) {
        vkDestroyBuffer(DeviceManager.vkDevice, bufferInfo.id(), null);
        vkFreeMemory(DeviceManager.vkDevice, bufferInfo.allocation(), null);

        if (bufferInfo.type() == MemoryType.Type.DEVICE_LOCAL)
            deviceMemory -= bufferInfo.bufferSize();
        else
            nativeMemory -= bufferInfo.bufferSize();

        buffers.remove(bufferInfo.id());
    }

    public static void freeImage(long imageId, long allocation) {
        vkDestroyImage(DeviceManager.vkDevice, imageId, null);
        vkFreeMemory(DeviceManager.vkDevice, allocation, null);

        VulkanImage image = images.remove(imageId);
        if (image != null)
            deviceMemory -= image.size;
    }

    public synchronized void addToFreeable(Buffer buffer) {
        Buffer.BufferInfo bufferInfo = buffer.getBufferInfo();
        checkBuffer(bufferInfo);
        freeableBuffers[currentFrame].add(bufferInfo);

        if (DEBUG)
            stackTraces[currentFrame].add(new Throwable().getStackTrace());
    }

    public synchronized void addToFreeable(VulkanImage image) {
        freeableImages[currentFrame].add(image);
    }

    public synchronized void addFrameOp(Runnable runnable) {
        frameOps[currentFrame].add(runnable);
    }

    public void doFrameOps(int frame) {
        for (Runnable runnable : frameOps[frame])
            runnable.run();
        frameOps[frame].clear();
    }

    private void freeBuffers(int frame) {
        List<Buffer.BufferInfo> bufferList = freeableBuffers[frame];
        for (Buffer.BufferInfo bufferInfo : bufferList)
            freeBuffer(bufferInfo);
        bufferList.clear();

        if (DEBUG)
            stackTraces[frame].clear();
    }

    private void freeImages(int frame) {
        List<VulkanImage> bufferList = freeableImages[frame];
        for (VulkanImage image : bufferList)
            image.doFree();
        bufferList.clear();
    }

    private void checkBuffer(Buffer.BufferInfo bufferInfo) {
        if (buffers.get(bufferInfo.id()) == null)
            throw new RuntimeException("trying to free not present buffer");
    }

    private void freeSegments(int frame) {
        var list = segmentsToFree[frame];
        for (var pair : list)
            pair.first.setSegmentFree(pair.second);
        list.clear();
    }

    public void addToFreeSegment(AreaBuffer areaBuffer, int offset) {
        segmentsToFree[currentFrame].add(new Pair<>(areaBuffer, offset));
    }

    public int getNativeMemoryMB() {
        return bytesInMb(nativeMemory);
    }

    public int getAllocatedDeviceMemoryMB() {
        return bytesInMb(deviceMemory);
    }

    public int getDeviceMemoryMB() {
        return bytesInMb(MemoryTypes.GPU_MEM.vkMemoryHeap.size());
    }

    int bytesInMb(long bytes) {
        return (int) (bytes / BYTES_IN_MB);
    }

    public String getHeapStats() {
        // Sem VMA — reportar só o que temos em memória rastreada
        return String.format("Device Memory Usage: %d MB (tracked)", getAllocatedDeviceMemoryMB());
    }
}

package net.vulkanmod.vulkan.shader;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.BufferSlice;
import net.vulkanmod.vulkan.memory.buffer.UniformBuffer;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;

public class DescriptorSets {
    // NOTE: DEVICE is fetched lazily-safe here because DescriptorSets is only ever
    // constructed after Vulkan.init() has completed. If this assumption ever changes,
    // replace with inline Vulkan.getVkDevice() calls.
    private static final VkDevice DEVICE = Vulkan.getVkDevice();

    private final Pipeline pipeline;
    private int poolSize = 10;
    private long descriptorPool = VK_NULL_HANDLE;
    private long[] sets;
    private long currentSet;
    private int currentIdx = -1;

    private final long[] boundUBs;
    private final ImageDescriptor.State[] boundTextures;
    private final IntBuffer dynamicOffsets;

    DescriptorSets(Pipeline pipeline) {
        this.pipeline = pipeline;
        this.boundTextures = new ImageDescriptor.State[pipeline.imageDescriptors.size()];
        this.dynamicOffsets = MemoryUtil.memAllocInt(pipeline.buffers.size());
        this.boundUBs = new long[pipeline.buffers.size()];

        Arrays.setAll(boundTextures, i -> new ImageDescriptor.State(0, 0));

        try (MemoryStack stack = stackPush()) {
            this.createDescriptorPool(stack);
            this.createDescriptorSets(stack);
        }
    }

    public void bindSets(VkCommandBuffer commandBuffer, UniformBuffer uniformBuffer, int bindPoint) {
        try (MemoryStack stack = stackPush()) {

            this.updateUniforms(uniformBuffer);
            this.updateDescriptorSet(stack, uniformBuffer);

            vkCmdBindDescriptorSets(commandBuffer, bindPoint, pipeline.pipelineLayout,
                                    0, stack.longs(currentSet), dynamicOffsets);
        }
    }

    private void updateUniforms(UniformBuffer globalUB) {
        int i = 0;
        for (UBO ubo : pipeline.getBuffers()) {
            // Buffer slice not yet allocated — fall back to global UBO.
            // This is a defensive fallback; upstream code should always allocate
            // before binding. Log a warning to surface unexpected occurrences.
            if (ubo.getBufferSlice().getBuffer() == null) {
                System.err.println("[DescriptorSets] WARNING: UBO binding " + ubo.getBinding()
                    + " has no allocated buffer slice — falling back to global UBO.");
                ubo.setUseGlobalBuffer(true);
                ubo.setUpdate(true);
            }

            boolean useOwnUB = !ubo.useGlobalBuffer();

            int offset;
            if (useOwnUB) {
                BufferSlice bufferSlice = ubo.getBufferSlice();
                offset = bufferSlice.getOffset();
            }
            else {
                offset = (int) globalUB.getUsedBytes();
                int alignedSize = UniformBuffer.getAlignedSize(ubo.getSize());
                globalUB.checkCapacity(alignedSize);

                if (ubo.shouldUpdate()) {
                    ubo.update(globalUB.getPointer());
                }
                globalUB.updateOffset(alignedSize);

                BufferSlice bufferSlice = ubo.getBufferSlice();
                bufferSlice.set(globalUB, offset, alignedSize);
            }

            this.dynamicOffsets.put(i, VUtil.align16(offset));

            ++i;
        }
    }

    private boolean needsUpdate(UniformBuffer uniformBuffer) {
        if (currentIdx == -1)
            return true;

        for (int j = 0; j < pipeline.imageDescriptors.size(); ++j) {
            ImageDescriptor imageDescriptor = pipeline.imageDescriptors.get(j);
            VulkanImage image = imageDescriptor.getImage();

            if (image == null) {
                throw new IllegalStateException(
                    "ImageDescriptor at index " + j + " (binding " + imageDescriptor.getBinding()
                    + ") has a null image — was it bound before use?");
            }

            long view = imageDescriptor.getImageView(image);
            long sampler = image.getSampler();

            // Do NOT call readOnlyLayout() here — layout transitions must only happen
            // in DefaultMainPass.begin() BEFORE the render pass starts.
            // Calling it here caused layout transitions during active render passes
            // even when no descriptor update was needed → texture flickering/purple.

            if (!this.boundTextures[j].isCurrentState(view, sampler)) {
                return true;
            }
        }

        for (int j = 0; j < pipeline.buffers.size(); ++j) {
            UBO ubo = pipeline.buffers.get(j);
            Buffer uniformBufferI = ubo.getBufferSlice().getBuffer();


            if (uniformBufferI == null)
                uniformBufferI = uniformBuffer;

            if (this.boundUBs[j] != uniformBufferI.getId()) {
                return true;
            }
        }

        return false;
    }

    private void checkPoolSize(MemoryStack stack) {
        if (this.currentIdx >= this.poolSize) {
            this.poolSize *= 2;
            this.currentIdx = 0;
            this.createDescriptorPool(stack);
            this.createDescriptorSets(stack);
        }
    }

    private void updateDescriptorSet(MemoryStack stack, UniformBuffer uniformBuffer) {

        // Check if update is needed
        if (!needsUpdate(uniformBuffer))
            return;

        this.currentIdx++;

        // Check pool size — must happen BEFORE accessing this.sets[currentIdx]
        checkPoolSize(stack);

        this.currentSet = this.sets[this.currentIdx];

        VkWriteDescriptorSet.Buffer descriptorWrites = VkWriteDescriptorSet.calloc(pipeline.buffers.size() + pipeline.imageDescriptors.size(), stack);
        VkDescriptorBufferInfo.Buffer[] bufferInfos = new VkDescriptorBufferInfo.Buffer[pipeline.buffers.size()];

        //TODO maybe ubo update is not needed everytime
        int i = 0;
        for (UBO ubo : pipeline.getBuffers()) {
            Buffer ub = ubo.getBufferSlice().getBuffer();
            boundUBs[i] = ub.getId();

            bufferInfos[i] = VkDescriptorBufferInfo.calloc(1, stack);
            bufferInfos[i].buffer(boundUBs[i]);
            bufferInfos[i].range(ubo.getSize());

            VkWriteDescriptorSet descriptorWrite = descriptorWrites.get(i);
            descriptorWrite.sType$Default();
            descriptorWrite.dstBinding(ubo.getBinding());
            descriptorWrite.dstArrayElement(0);
            descriptorWrite.descriptorType(ubo.getType());
            descriptorWrite.descriptorCount(1);
            descriptorWrite.pBufferInfo(bufferInfos[i]);
            descriptorWrite.dstSet(currentSet);

            ++i;
        }

        VkDescriptorImageInfo.Buffer[] imageInfo = new VkDescriptorImageInfo.Buffer[pipeline.imageDescriptors.size()];

        for (int j = 0; j < pipeline.imageDescriptors.size(); ++j) {
            ImageDescriptor imageDescriptor = pipeline.imageDescriptors.get(j);
            VulkanImage image = imageDescriptor.getImage();

            if (image == null) {
                throw new IllegalStateException(
                    "ImageDescriptor at index " + j + " (binding " + imageDescriptor.getBinding()
                    + ") has a null image during descriptor write");
            }

            long view = imageDescriptor.getImageView(image);
            long sampler = image.getSampler();
            int layout = imageDescriptor.getLayout();

            // Layout transition handled before render pass in DefaultMainPass.begin().
            // Do NOT call readOnlyLayout() here — image barriers inside an active
            // render pass are invalid per Vulkan spec and cause purple/invisible
            // textures on Mali hardware.
            // if (imageDescriptor.isReadOnlyLayout) image.readOnlyLayout(); // REMOVED

            imageInfo[j] = VkDescriptorImageInfo.calloc(1, stack);
            imageInfo[j].imageLayout(layout);
            imageInfo[j].imageView(view);

            if (imageDescriptor.useSampler) {
                imageInfo[j].sampler(sampler);
            }

            VkWriteDescriptorSet descriptorWrite = descriptorWrites.get(i);
            descriptorWrite.sType$Default();
            descriptorWrite.dstBinding(imageDescriptor.getBinding());
            descriptorWrite.dstArrayElement(0);
            descriptorWrite.descriptorType(imageDescriptor.getType());
            descriptorWrite.descriptorCount(1);
            descriptorWrite.pImageInfo(imageInfo[j]);
            descriptorWrite.dstSet(currentSet);

            this.boundTextures[j].set(view, sampler);
            ++i;
        }

        vkUpdateDescriptorSets(DEVICE, descriptorWrites, null);
    }

    private void createDescriptorSets(MemoryStack stack) {
        // Use try/finally to guarantee memFree even if vkAllocateDescriptorSets fails
        LongBuffer layouts = MemoryUtil.memAllocLong(this.poolSize);
        try {
            for (int i = 0; i < this.poolSize; ++i) {
                layouts.put(i, pipeline.descriptorSetLayout);
            }

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
            allocInfo.sType$Default();
            allocInfo.descriptorPool(descriptorPool);
            allocInfo.pSetLayouts(layouts);

            // Not hotspot code, use heap array
            this.sets = new long[this.poolSize];

            int result = vkAllocateDescriptorSets(DEVICE, allocInfo, this.sets);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate descriptor sets. Result:" + result);
            }
        } finally {
            MemoryUtil.memFree(layouts);
        }
    }

    private void createDescriptorPool(MemoryStack stack) {
        int size = pipeline.buffers.size() + pipeline.imageDescriptors.size();

        VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(size, stack);

        int i = 0;
        for (var buffer : pipeline.getBuffers()) {
            VkDescriptorPoolSize uniformBufferPoolSize = poolSizes.get(i);
            uniformBufferPoolSize.type(buffer.getType());
            uniformBufferPoolSize.descriptorCount(this.poolSize);

            ++i;
        }

        for (var imageDescriptor : pipeline.getImageDescriptors()) {
            VkDescriptorPoolSize textureSamplerPoolSize = poolSizes.get(i);
            textureSamplerPoolSize.type(imageDescriptor.getType());
            textureSamplerPoolSize.descriptorCount(this.poolSize);

            ++i;
        }

        VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack);
        poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
        poolInfo.pPoolSizes(poolSizes);
        poolInfo.maxSets(this.poolSize);

        LongBuffer pDescriptorPool = stack.mallocLong(1);

        if (vkCreateDescriptorPool(DEVICE, poolInfo, null, pDescriptorPool) != VK_SUCCESS) {
            throw new RuntimeException("Failed to create descriptor pool");
        }

        if (this.descriptorPool != VK_NULL_HANDLE) {
            final long oldDescriptorPool = this.descriptorPool;
            MemoryManager.getInstance().addFrameOp(() -> {
                vkDestroyDescriptorPool(DEVICE, oldDescriptorPool, null);
            });
        }

        this.descriptorPool = pDescriptorPool.get(0);
    }

    public void resetIdx() {
        this.currentIdx = -1;
    }

    public void cleanUp() {
        // vkDestroyDescriptorPool implicitly frees all sets —
        // vkResetDescriptorPool beforehand is redundant but harmless.
        vkDestroyDescriptorPool(DEVICE, descriptorPool, null);

        MemoryUtil.memFree(this.dynamicOffsets);
    }

}

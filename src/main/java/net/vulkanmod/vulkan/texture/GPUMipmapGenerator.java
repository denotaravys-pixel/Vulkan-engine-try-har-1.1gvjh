package net.vulkanmod.vulkan.texture;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkOffset3D;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

// FIX: long commandBuffer → VkCommandBuffer commandBuffer
// FIX: adicionado import stackPush que estava em falta
// FIX: removido import não usado (Vulkan, DeviceManager)
public class GPUMipmapGenerator {

    // FIX: parâmetro mudado de long para VkCommandBuffer
    public static void generateMipmaps(VkCommandBuffer commandBuffer, VulkanImage image) {
        if (image.mipLevels <= 1)
            return;

        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barrierBuf = VkImageMemoryBarrier.calloc(1, stack);
            VkImageMemoryBarrier barrier = barrierBuf.get(0);
            barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            barrier.image(image.getId());
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(1);
            barrier.subresourceRange().levelCount(1);

            VkImageBlit.Buffer blitBuf = VkImageBlit.calloc(1, stack);
            VkImageBlit blit = blitBuf.get(0);
            blit.srcOffsets(0).set(0, 0, 0);
            blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            blit.srcSubresource().mipLevel(0);
            blit.srcSubresource().baseArrayLayer(0);
            blit.srcSubresource().layerCount(1);
            blit.dstOffsets(0).set(0, 0, 0);
            blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            blit.dstSubresource().baseArrayLayer(0);
            blit.dstSubresource().layerCount(1);

            int mipWidth = image.width;
            int mipHeight = image.height;

            for (int i = 1; i < image.mipLevels; i++) {
                // Transition mip i-1: TRANSFER_DST → TRANSFER_SRC
                barrier.subresourceRange().baseMipLevel(i - 1);
                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                barrier.newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);

                // FIX: commandBuffer agora é VkCommandBuffer — compila correctamente
                vkCmdPipelineBarrier(commandBuffer,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, null, null, barrierBuf);

                // Setup blit source e destination
                blit.srcOffsets(1).set(mipWidth, mipHeight, 1);
                blit.dstOffsets(1).set(
                        Math.max(mipWidth / 2, 1),
                        Math.max(mipHeight / 2, 1),
                        1);
                blit.dstSubresource().mipLevel(i);

                vkCmdBlitImage(commandBuffer,
                        image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        blitBuf, VK_FILTER_LINEAR);

                // Transition mip i-1: TRANSFER_SRC → SHADER_READ_ONLY
                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                barrier.newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                barrier.srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

                vkCmdPipelineBarrier(commandBuffer,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        0, null, null, barrierBuf);

                mipWidth = Math.max(mipWidth / 2, 1);
                mipHeight = Math.max(mipHeight / 2, 1);
            }

            // Transition último mip: TRANSFER_DST → SHADER_READ_ONLY
            barrier.subresourceRange().baseMipLevel(image.mipLevels - 1);
            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            barrier.newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            vkCmdPipelineBarrier(commandBuffer,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0, null, null, barrierBuf);
        }
    }
}

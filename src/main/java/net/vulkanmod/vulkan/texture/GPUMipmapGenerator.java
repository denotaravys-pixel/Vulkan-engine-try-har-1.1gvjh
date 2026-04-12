package net.vulkanmod.vulkan.texture;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkOffset3D;

import static org.lwjgl.vulkan.VK10.*;

public class GPUMipmapGenerator {

    public static void generateMipmaps(long commandBuffer, VulkanImage image) {
        if (image.getMipLevels() <= 1) return;

        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier barrier = VkImageMemoryBarrier.calloc(stack);
            barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            barrier.image(image.getId());
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(1);
            barrier.subresourceRange().levelCount(1);

            VkImageBlit blit = VkImageBlit.calloc(stack);
            blit.srcOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
            blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            blit.srcSubresource().mipLevel(0);
            blit.srcSubresource().baseArrayLayer(0);
            blit.srcSubresource().layerCount(1);
            blit.dstOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
            blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            blit.dstSubresource().baseArrayLayer(0);
            blit.dstSubresource().layerCount(1);

            int mipWidth = image.getWidth();
            int mipHeight = image.getHeight();

            for (int i = 1; i < image.getMipLevels(); i++) {
                // Transition i-1 to TRANSFER_SRC_OPTIMAL
                barrier.subresourceRange().baseMipLevel(i - 1);
                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                barrier.newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);

                vkCmdPipelineBarrier(commandBuffer,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                    null, null, barrier);

                // Setup blit
                blit.srcOffsets(1, VkOffset3D.calloc(stack).set(mipWidth, mipHeight, 1));
                blit.dstOffsets(1, VkOffset3D.calloc(stack).set(
                    Math.max(mipWidth / 2, 1),
                    Math.max(mipHeight / 2, 1),
                    1));
                blit.dstSubresource().mipLevel(i);

                vkCmdBlitImage(commandBuffer,
                    image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    blit, VK_FILTER_LINEAR);

                // Transition i-1 to SHADER_READ_ONLY_OPTIMAL
                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                barrier.newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                barrier.srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

                vkCmdPipelineBarrier(commandBuffer,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                    null, null, barrier);

                mipWidth = Math.max(mipWidth / 2, 1);
                mipHeight = Math.max(mipHeight / 2, 1);
            }

            // Transition last mip level to SHADER_READ_ONLY_OPTIMAL
            barrier.subresourceRange().baseMipLevel(image.getMipLevels() - 1);
            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            barrier.newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            vkCmdPipelineBarrier(commandBuffer,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                null, null, barrier);
        }
    }
}
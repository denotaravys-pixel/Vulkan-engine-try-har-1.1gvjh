package net.vulkanmod.render.scaling;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import org.lwjgl.vulkan.VkImageBlit;

import java.util.ArrayDeque;

import static org.lwjgl.vulkan.VK10.*;

public class DynamicResolutionScaler {

    private static final float TARGET_FPS = 30.0f;
    private static final float MIN_SCALE = 0.60f;
    private static final float MAX_SCALE = 1.0f;
    private static final float SCALE_STEP = 0.05f;

    private static float currentScale = 1.0f;
    private static ArrayDeque<Float> fpsHistory = new ArrayDeque<>(10);
    private static long lastTime = System.nanoTime();

    public static void updateFPS(float currentFPS) {
        fpsHistory.add(currentFPS);
        if (fpsHistory.size() > 10) {
            fpsHistory.removeFirst();
        }

        float avgFPS = (float) fpsHistory.stream().mapToDouble(f -> f).average().orElse(TARGET_FPS);

        if (avgFPS < TARGET_FPS - 5) {
            // FPS too low, reduce resolution
            currentScale = Math.max(MIN_SCALE, currentScale - SCALE_STEP);
        } else if (avgFPS > TARGET_FPS + 5) {
            // FPS good, increase resolution
            currentScale = Math.min(MAX_SCALE, currentScale + SCALE_STEP);
        }
    }

    public static float getCurrentScale() {
        return currentScale;
    }

    public static int getScaledWidth(int nativeWidth) {
        return (int) (nativeWidth * currentScale);
    }

    public static int getScaledHeight(int nativeHeight) {
        return (int) (nativeHeight * currentScale);
    }

    public static void blitToSwapchain(long commandBuffer, long scaledImage, long swapchainImage,
                                      int scaledWidth, int scaledHeight, int swapWidth, int swapHeight) {
        // TODO: Implement Vulkan image blit from scaled image to swapchain
        // This would use vkCmdBlitImage with linear filtering
    }

    public static String getScaleIndicator() {
        if (currentScale < 1.0f) {
            return String.format("[DRS: %.0f%%]", currentScale * 100);
        }
        return "";
    }
}
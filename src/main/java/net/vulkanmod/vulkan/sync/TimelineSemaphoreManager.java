package net.vulkanmod.vulkan.sync;

import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRTimelineSemaphore.*;
import static org.lwjgl.vulkan.VK10.*;

// FIX: constantes VK10 → KHR (Vulkan 1.1 usa extensão, não core 1.2)
// VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO  → VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO_KHR
// VK_SEMAPHORE_TYPE_TIMELINE                   → VK_SEMAPHORE_TYPE_TIMELINE_KHR
// VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO        → VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO_KHR
// vkWaitSemaphores                             → vkWaitSemaphoresKHR
public class TimelineSemaphoreManager {

    private static long timelineSemaphore = VK_NULL_HANDLE;
    private static long currentValue = 0;

    public static void init() {
        try (MemoryStack stack = stackPush()) {
            // FIX: usar constante KHR
            VkSemaphoreTypeCreateInfoKHR typeInfo = VkSemaphoreTypeCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO_KHR)
                .semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE_KHR)
                .initialValue(0);

            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                .pNext(typeInfo.address());

            LongBuffer pSemaphore = stack.mallocLong(1);
            int result = vkCreateSemaphore(Vulkan.getVkDevice(), semaphoreInfo, null, pSemaphore);

            if (result != VK_SUCCESS) {
                // Timeline semaphore não suportado — continuar sem ele
                System.err.println("[VULKANMOD] Timeline semaphore não disponível: " + result
                    + " — usando binary semaphores");
                timelineSemaphore = VK_NULL_HANDLE;
                return;
            }

            timelineSemaphore = pSemaphore.get(0);
            System.err.println("[VULKANMOD] Timeline semaphore criado OK");
        }
    }

    public static boolean isAvailable() {
        return timelineSemaphore != VK_NULL_HANDLE;
    }

    public static void cleanup() {
        if (timelineSemaphore != VK_NULL_HANDLE) {
            vkDestroySemaphore(Vulkan.getVkDevice(), timelineSemaphore, null);
            timelineSemaphore = VK_NULL_HANDLE;
        }
    }

    public static long getTimelineSemaphore() {
        return timelineSemaphore;
    }

    public static long getNextValue() {
        return ++currentValue;
    }

    public static long getCurrentValue() {
        return currentValue;
    }

    public static void waitForValue(long value, long timeoutNs) {
        if (timelineSemaphore == VK_NULL_HANDLE) return;

        try (MemoryStack stack = stackPush()) {
            // FIX: usar constante KHR e struct KHR
            VkSemaphoreWaitInfoKHR waitInfo = VkSemaphoreWaitInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO_KHR)
                .semaphoreCount(1)
                .pSemaphores(stack.longs(timelineSemaphore))
                .pValues(stack.longs(value));

            // FIX: vkWaitSemaphores → vkWaitSemaphoresKHR
            int result = vkWaitSemaphoresKHR(Vulkan.getVkDevice(), waitInfo, timeoutNs);

            if (result == VK_TIMEOUT) {
                System.err.println("[VULKANMOD] Timeline semaphore TIMEOUT value=" + value);
            }
        }
    }
}

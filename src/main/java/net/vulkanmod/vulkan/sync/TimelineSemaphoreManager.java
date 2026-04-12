package net.vulkanmod.vulkan.sync;

import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRTimelineSemaphore.*;
import static org.lwjgl.vulkan.VK10.*;

public class TimelineSemaphoreManager {

    private static long timelineSemaphore;
    private static long currentValue = 0;

    public static void init() {
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreTypeCreateInfo typeInfo = VkSemaphoreTypeCreateInfo.calloc(stack);
            typeInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO);
            typeInfo.semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE);
            typeInfo.initialValue(0);

            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack);
            semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            semaphoreInfo.pNext(typeInfo);

            LongBuffer pSemaphore = stack.mallocLong(1);
            int result = vkCreateSemaphore(Vulkan.getVkDevice(), semaphoreInfo, null, pSemaphore);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create timeline semaphore: " + result);
            }

            timelineSemaphore = pSemaphore.get(0);
        }
    }

    public static void cleanup() {
        if (timelineSemaphore != VK_NULL_HANDLE) {
            vkDestroySemaphore(Vulkan.getVkDevice(), timelineSemaphore, null);
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
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreWaitInfo waitInfo = VkSemaphoreWaitInfo.calloc(stack);
            waitInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO);
            waitInfo.semaphoreCount(1);
            waitInfo.pSemaphores(stack.longs(timelineSemaphore));
            waitInfo.pValues(stack.longs(value));

            vkWaitSemaphores(Vulkan.getVkDevice(), waitInfo, timeoutNs);
        }
    }
}
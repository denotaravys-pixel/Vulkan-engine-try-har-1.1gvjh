package net.vulkanmod.render.backend;

public class RenderBackendManager {

    private static RenderBackend vulkanBackend;
    private static RenderBackend glesBackend;
    private static RenderBackend currentBackend;
    private static boolean vulkanFailed = false;

    public static void init() {
        vulkanBackend = new VulkanBackend();
        glesBackend = new GLESBackend();

        vulkanBackend.setFallback(glesBackend);
        glesBackend.setFallback(vulkanBackend);

        // Try Vulkan first
        try {
            vulkanBackend.init();
            currentBackend = vulkanBackend;
        } catch (Exception e) {
            System.err.println("Vulkan initialization failed, falling back to GL ES: " + e.getMessage());
            vulkanFailed = true;
            glesBackend.init();
            currentBackend = glesBackend;
        }
    }

    public static RenderBackend getBackend() {
        return currentBackend;
    }

    public static RenderBackend getBackendFor(RenderType type) {
        if (vulkanFailed) {
            return glesBackend;
        }

        switch (type) {
            case GUI:
            case TEXT:
            case OVERLAY_2D:
            case PARTICLES:
                return glesBackend;
            case CHUNK:
            case ENTITY:
            case WATER:
            case SKY:
            default:
                return vulkanBackend;
        }
    }

    public static void setVulkanFailed() {
        vulkanFailed = true;
        currentBackend = glesBackend;
    }

    public enum RenderType {
        CHUNK, ENTITY, WATER, GUI, TEXT, PARTICLES, SKY, OVERLAY_2D
    }
}
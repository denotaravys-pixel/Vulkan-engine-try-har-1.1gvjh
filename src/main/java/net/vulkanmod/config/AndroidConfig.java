package net.vulkanmod.config;

/**
 * Configuração Android ARM64 — Mali-G52
 * Arquitectura híbrida: Vulkan para mundo, OpenGL ES para GUI/HUD
 */
public class AndroidConfig {
    public static final boolean IS_ANDROID;

    static {
        boolean android = false;
        try {
            Class.forName("android.os.Build");
            android = true;
        } catch (ClassNotFoundException ignored) {}
        IS_ANDROID = android;
    }

    // Vulkan renderiza: chunks, terrain, água, transparências
    public static final boolean VULKAN_RENDER_WORLD     = IS_ANDROID;

    // OpenGL ES renderiza: GUI, HUD, inventário, texto, entidades simples
    public static final boolean GLES_RENDER_GUI         = IS_ANDROID;

    // Se Vulkan falhar completamente → tudo migra para GLES
    public static boolean vulkanFailed = false;

    // Render distance reduzida no Android
    public static final int DEFAULT_RENDER_DISTANCE     = IS_ANDROID ? 6 : 12;

    // Sem multiDrawIndirect no Mali-G52
    public static final boolean USE_INDIRECT_DRAW       = false;

    // Timeouts seguros (nunca Long.MAX_VALUE)
    public static final long FENCE_TIMEOUT_NS           = 3_000_000_000L; // 3 segundos

    // UBO alignment Mali-G52
    public static final int UBO_ALIGNMENT               = 16;

    public static boolean useVulkan() {
        return VULKAN_RENDER_WORLD && !vulkanFailed;
    }

    public static void markVulkanFailed(String reason) {
        System.err.println("[VULKANMOD] ❌ Vulkan falhou: " + reason);
        System.err.println("[VULKANMOD] → Fallback para OpenGL ES");
        vulkanFailed = true;
    }
}
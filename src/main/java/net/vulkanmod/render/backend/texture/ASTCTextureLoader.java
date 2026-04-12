package net.vulkanmod.render.backend.texture;

import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;

import java.nio.ByteBuffer;

public class ASTCTextureLoader {

    private static boolean astcSupported = false;
    private static TextureFormat preferredFormat;

    public static void init() {
        VkPhysicalDeviceFeatures features = DeviceManager.device.availableFeatures.features();
        astcSupported = features.textureCompressionASTC_LDR();

        if (astcSupported) {
            preferredFormat = TextureFormat.ASTC_6x6; // Good balance for block textures
        } else {
            preferredFormat = TextureFormat.ETC2_RGB;
        }
    }

    public static TextureFormat getPreferredFormat() {
        return preferredFormat;
    }

    public static boolean isASTCSupported() {
        return astcSupported;
    }

    public static ByteBuffer convertToASTC(ByteBuffer pngData, int width, int height) {
        // TODO: Implement PNG to ASTC conversion
        // This would use a library like astc-encoder or similar
        // For now, return the original data
        return pngData;
    }

    public static int getBlockSize(TextureFormat format) {
        switch (format) {
            case ASTC_4x4:
                return 16; // 4x4 block = 16 bytes
            case ASTC_6x6:
                return 16; // 6x6 block = 16 bytes
            case ASTC_8x8:
                return 16; // 8x8 block = 16 bytes
            case ETC2_RGB:
                return 8; // ETC2 = 8 bytes per 4x4 block
            case ETC2_RGBA:
                return 16; // ETC2 EAC = 16 bytes per 4x4 block
            default:
                return 16;
        }
    }
}
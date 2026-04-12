package net.vulkanmod.render.backend.texture;

public enum TextureFormat {
    ASTC_4x4("ASTC_4x4_UNORM_BLOCK", 0x93B0),
    ASTC_6x6("ASTC_6x6_UNORM_BLOCK", 0x93B4),
    ASTC_8x8("ASTC_8x8_UNORM_BLOCK", 0x93B7),
    ETC2_RGB("COMPRESSED_RGB8_ETC2", 0x9274),
    ETC2_RGBA("COMPRESSED_RGBA8_ETC2_EAC", 0x9278);

    private final String vkFormat;
    private final int glFormat;

    TextureFormat(String vkFormat, int glFormat) {
        this.vkFormat = vkFormat;
        this.glFormat = glFormat;
    }

    public String getVkFormat() {
        return vkFormat;
    }

    public int getGlFormat() {
        return glFormat;
    }
}
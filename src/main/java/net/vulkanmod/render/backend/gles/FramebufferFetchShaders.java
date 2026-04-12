package net.vulkanmod.render.backend.gles;

public class FramebufferFetchShaders {

    public static final String TRANSPARENCY_FRAGMENT_SHADER =
        "#version 300 es\n" +
        "#extension GL_ARM_shader_framebuffer_fetch : enable\n" +
        "precision mediump float;\n" +
        "\n" +
        "in vec4 vColor;\n" +
        "in vec2 vTexCoord;\n" +
        "\n" +
        "uniform sampler2D uTexture;\n" +
        "\n" +
        "layout(location = 0) inout vec4 fragColor;\n" +
        "\n" +
        "void main() {\n" +
        "    vec4 texColor = texture(uTexture, vTexCoord);\n" +
        "    vec4 newColor = vColor * texColor;\n" +
        "    \n" +
        "    // Alpha blending using framebuffer fetch\n" +
        "    fragColor = mix(fragColor, newColor, newColor.a);\n" +
        "}\n";

    public static final String FOG_FRAGMENT_SHADER =
        "#version 300 es\n" +
        "#extension GL_ARM_shader_framebuffer_fetch : enable\n" +
        "precision mediump float;\n" +
        "\n" +
        "in vec4 vColor;\n" +
        "in vec2 vTexCoord;\n" +
        "in float vFogDistance;\n" +
        "\n" +
        "uniform sampler2D uTexture;\n" +
        "uniform vec3 uFogColor;\n" +
        "uniform float uFogStart;\n" +
        "uniform float uFogEnd;\n" +
        "\n" +
        "layout(location = 0) inout vec4 fragColor;\n" +
        "\n" +
        "void main() {\n" +
        "    vec4 texColor = texture(uTexture, vTexCoord);\n" +
        "    vec4 baseColor = vColor * texColor;\n" +
        "    \n" +
        "    // Apply fog directly to framebuffer\n" +
        "    float fogFactor = clamp((vFogDistance - uFogStart) / (uFogEnd - uFogStart), 0.0, 1.0);\n" +
        "    vec4 foggedColor = mix(baseColor, vec4(uFogColor, baseColor.a), fogFactor);\n" +
        "    \n" +
        "    fragColor = mix(fragColor, foggedColor, foggedColor.a);\n" +
        "}\n";
}
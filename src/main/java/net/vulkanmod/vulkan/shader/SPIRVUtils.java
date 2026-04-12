package net.vulkanmod.vulkan.shader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

public class SPIRVUtils {
    private static final boolean IS_ANDROID;

    static {
        boolean android = false;
        try {
            Class.forName("android.os.Build");
            android = true;
        } catch (ClassNotFoundException ignored) {}
        IS_ANDROID = android;
    }

    public static ByteBuffer compileShader(String name, String source, int kind) {
        if (IS_ANDROID) {
            System.err.println("[VULKANMOD] compileShader() chamado no Android — usando loadSPV()");
            return loadSPV(name);
        }
        // PC: tentar shaderc (pode não estar disponível)
        return null;
    }

    public static ByteBuffer loadSPV(String name) {
        // Remover extensão se presente
        String baseName = name.replaceAll("\\.(vert|frag|comp|geom|glsl)$", "");

        // Caminhos a tentar por ordem
        String[] paths = {
            "/assets/vulkanmod/shaders/" + baseName + ".spv",
            "/assets/vulkanmod/shaders/" + name + ".spv",
            "/vulkanmod/shaders/" + baseName + ".spv",
            "/shaders/" + baseName + ".spv"
        };

        for (String path : paths) {
            try (InputStream is = SPIRVUtils.class.getResourceAsStream(path)) {
                if (is == null) continue;

                byte[] bytes = is.readAllBytes();

                // Validar magic number SPIR-V: 0x07230203
                if (bytes.length < 4) {
                    System.err.println("[VULKANMOD] SPV muito pequeno: " + path);
                    continue;
                }

                int magic = ((bytes[0] & 0xFF))
                          | ((bytes[1] & 0xFF) << 8)
                          | ((bytes[2] & 0xFF) << 16)
                          | ((bytes[3] & 0xFF) << 24);

                if (magic != 0x07230203) {
                    System.err.println("[VULKANMOD] SPV magic inválido em: " + path
                        + " (got 0x" + Integer.toHexString(magic) + ")");
                    continue;
                }

                ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
                buffer.put(bytes);
                buffer.flip();
                System.err.println("[VULKANMOD] SPV carregado: " + path
                    + " (" + bytes.length + " bytes)");
                return buffer;

            } catch (IOException e) {
                // continuar para próximo path
            }
        }

        System.err.println("[VULKANMOD] ❌ SPV não encontrado para: " + name);
        System.err.println("[VULKANMOD]    Paths tentados: " + String.join(", ", paths));
        return null;
    }

    public static void free(ByteBuffer buffer) {
        if (buffer != null) {
            MemoryUtil.memFree(buffer);
        }
    }
}

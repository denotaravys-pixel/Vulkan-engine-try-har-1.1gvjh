package net.vulkanmod.vulkan.shader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

public class SPIRVUtils {

    public static final boolean IS_ANDROID;
    static {
        boolean a = false;
        try {
            Class.forName("android.os.Build");
            a = true;
        } catch (ClassNotFoundException ignored) {
        }
        IS_ANDROID = a;
    }

    public enum ShaderKind {
        VERTEX_SHADER,
        FRAGMENT_SHADER,
        GEOMETRY_SHADER,
        COMPUTE_SHADER,
        TESS_CONTROL_SHADER,
        TESS_EVALUATION_SHADER;
    }

    public static class SPIRV implements AutoCloseable {
        private final ByteBuffer bytecode;
        private final String name;

        public SPIRV(ByteBuffer bytecode, String name) {
            this.bytecode = bytecode;
            this.name = name;
        }

        public ByteBuffer bytecode() {
            return bytecode;
        }

        public String name() {
            return name;
        }

        @Override
        public void close() {
            if (bytecode != null) {
                MemoryUtil.memFree(bytecode);
            }
        }
    }

    public static SPIRV compileShaderFile(String shaderFile, ShaderKind kind) {
        if (IS_ANDROID) {
            ByteBuffer buf = loadSPV(shaderFile);
            if (buf == null) return null;
            return new SPIRV(buf, shaderFile);
        }
        return null;
    }

    public static SPIRV compileShader(String name, CharSequence source, ShaderKind kind) {
        if (IS_ANDROID) {
            ByteBuffer buf = loadSPV(name);
            if (buf == null) return null;
            return new SPIRV(buf, name);
        }
        return null;
    }

    public static SPIRV compileShader(String name, String source, ShaderKind kind) {
        return compileShader(name, (CharSequence) source, kind);
    }

    public static ByteBuffer compileShaderRaw(String name, CharSequence source, int kind) {
        if (IS_ANDROID) return loadSPV(name);
        return null;
    }

    public static SPIRV compileShaderAbsolutePath(String name, CharSequence source, ShaderKind kind) {
        return compileShader(name, source, kind);
    }

    public static SPIRV compileShaderAbsolutePath(String name, String source, ShaderKind kind) {
        return compileShader(name, source, kind);
    }

    public static ByteBuffer loadSPV(String name) {
        System.err.println("[VULKANMOD] loadSPV: " + name);

        java.util.List<String> paths = new java.util.ArrayList<>();

        if (name.contains("/")) {
            String base = name.startsWith("/") ? name : "/" + name;
            paths.add(base + ".spv");
            String noExt = base.replaceAll("\\.(vsh|fsh|vert|frag|comp|geom|tesc|tese|glsl)$", "");
            if (!noExt.equals(base)) {
                paths.add(noExt + ".spv");
            }
        } else {
            String noExt = name.replaceAll("\\.(vsh|fsh|vert|frag|comp|geom|tesc|tese|glsl)$", "");
            paths.add("/assets/vulkanmod/shaders/" + name + ".spv");
            if (!noExt.equals(name)) {
                paths.add("/assets/vulkanmod/shaders/" + noExt + ".spv");
            }
        }

        for (String path : paths) {
            ByteBuffer buf = tryLoad(path);
            if (buf != null) {
                System.err.println("[VULKANMOD] ✅ SPV OK: " + path);
                return buf;
            }
        }

        System.err.println("[VULKANMOD] ❌ SPV não encontrado: " + name);
        System.err.println("[VULKANMOD]    Tentados: " + paths);
        return null;
    }

    private static ByteBuffer tryLoad(String path) {
        try (InputStream is = SPIRVUtils.class.getResourceAsStream(path)) {
            if (is == null) return null;
            byte[] b = is.readAllBytes();
            if (b.length < 4) return null;
            int magic = (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
            if (magic != 0x07230203) {
                System.err.println("[VULKANMOD] Magic inválido: 0x" + Integer.toHexString(magic) + " em " + path);
                return null;
            }
            ByteBuffer buf = MemoryUtil.memAlloc(b.length);
            buf.put(b).flip();
            return buf;
        } catch (IOException e) {
            return null;
        }
    }

    public static void listAvailableSPVs() {
        String[] check = {
            "/assets/vulkanmod/shaders/terrain.vsh.spv",
            "/assets/vulkanmod/shaders/terrain.fsh.spv",
            "/assets/vulkanmod/shaders/basic.vsh.spv",
            "/assets/vulkanmod/shaders/basic.fsh.spv",
            "/assets/vulkanmod/shaders/position.vsh.spv",
            "/assets/vulkanmod/shaders/position.fsh.spv",
        };
        for (String p : check) {
            boolean ok = SPIRVUtils.class.getResourceAsStream(p) != null;
            System.err.println("[VULKANMOD] " + (ok ? "✅" : "❌") + " " + p);
        }
    }

    public static void free(ByteBuffer buf) {
        if (buf != null) MemoryUtil.memFree(buf);
    }
}

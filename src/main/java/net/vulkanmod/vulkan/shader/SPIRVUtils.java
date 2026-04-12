package net.vulkanmod.vulkan.shader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

public class SPIRVUtils {

    public static final boolean IS_ANDROID;
    static {
        // Class.forName falha no Knot classloader — usar system properties
        // Zalith/PojavLauncher define -Dos.version=Android-XX e POJAV_RENDERER
        String osVersion = System.getProperty("os.version", "");
        String pojav     = System.getProperty("pojav.path.minecraft", "");
        String renderer  = System.getenv("POJAV_RENDERER") != null
                           ? System.getenv("POJAV_RENDERER") : "";
        boolean a = osVersion.contains("Android")
                     || pojav.contains("pojav")
                     || renderer.contains("vulkan_zink")
                     || renderer.contains("vulkan");
        IS_ANDROID = a;
        System.err.println("[VULKANMOD] IS_ANDROID=" + IS_ANDROID
                           + " os.version=" + osVersion
                           + " pojav=" + (!pojav.isEmpty())
                           + " renderer=" + renderer);
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
            ByteBuffer buf = loadSPV(shaderFile, kind);
            if (buf == null) return null;
            return new SPIRV(buf, shaderFile);
        }
        return null;
    }

    public static SPIRV compileShader(String name, CharSequence source, ShaderKind kind) {
        if (IS_ANDROID) {
            ByteBuffer buf = loadSPV(name, kind);
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
        return loadSPV(name, null);
    }

    // -------------------------------------------------------------------------
    // loadSPV — corrigido para estrutura real de subdirectórios do JAR
    //
    // Estrutura confirmada por: jar tf VulkanMod-Android-ARM64-v1.0.jar | grep .spv
    //   assets/vulkanmod/shaders/basic/{name}/{name}.vert.spv
    //   assets/vulkanmod/shaders/basic/{name}/{name}.frag.spv
    //   assets/vulkanmod/shaders/core/{name}/{name}.vert.spv
    //   assets/vulkanmod/shaders/post/{name}/{name}.vert.spv
    //
    // Ordem: subdirectório {basic,core,post} primeiro, depois fallback plano
    // -------------------------------------------------------------------------
    public static ByteBuffer loadSPV(String name, ShaderKind kind) {
        System.err.println("[VULKANMOD] loadSPV: name=" + name + " kind=" + kind);

        // Normalizar: remover extensão GLSL e extrair só o nome base (sem path)
        String base = name.replaceAll("\\.(vsh|fsh|vert|frag|comp|geom|tesc|tese|glsl)$", "");
        int lastSlash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (lastSlash >= 0) base = base.substring(lastSlash + 1);

        java.util.List<String> paths = new java.util.ArrayList<>();
        String[] categories = {"basic", "core", "post"};

        if (kind == ShaderKind.VERTEX_SHADER) {
            // Estrutura subdirectório (real do JAR)
            for (String cat : categories) {
                paths.add("/assets/vulkanmod/shaders/" + cat + "/" + base + "/" + base + ".vert.spv");
                paths.add("/assets/vulkanmod/shaders/" + cat + "/" + base + "/" + base + ".vsh.spv");
            }
            // Fallback plano
            paths.add("/assets/vulkanmod/shaders/" + base + ".vert.spv");
            paths.add("/assets/vulkanmod/shaders/" + base + ".vsh.spv");
            paths.add("/assets/vulkanmod/shaders/" + base + ".spv");

        } else if (kind == ShaderKind.FRAGMENT_SHADER) {
            // Estrutura subdirectório (real do JAR)
            for (String cat : categories) {
                paths.add("/assets/vulkanmod/shaders/" + cat + "/" + base + "/" + base + ".frag.spv");
                paths.add("/assets/vulkanmod/shaders/" + cat + "/" + base + "/" + base + ".fsh.spv");
            }
            // Fallback plano
            paths.add("/assets/vulkanmod/shaders/" + base + ".frag.spv");
            paths.add("/assets/vulkanmod/shaders/" + base + ".fsh.spv");
            paths.add("/assets/vulkanmod/shaders/" + base + ".spv");

        } else {
            // kind null ou outro: tenta tudo
            for (String cat : categories) {
                paths.add("/assets/vulkanmod/shaders/" + cat + "/" + base + "/" + base + ".vert.spv");
                paths.add("/assets/vulkanmod/shaders/" + cat + "/" + base + "/" + base + ".frag.spv");
                paths.add("/assets/vulkanmod/shaders/" + cat + "/" + base + "/" + base + ".comp.spv");
            }
            paths.add("/assets/vulkanmod/shaders/" + base + ".spv");
            paths.add("/assets/vulkanmod/shaders/" + base + ".vert.spv");
            paths.add("/assets/vulkanmod/shaders/" + base + ".vsh.spv");
            paths.add("/assets/vulkanmod/shaders/" + base + ".frag.spv");
            paths.add("/assets/vulkanmod/shaders/" + base + ".fsh.spv");
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

    // -------------------------------------------------------------------------
    // tryLoad — tenta dois classloaders
    // A: SPIRVUtils.class (path com leading /)
    // B: Thread context classloader do Knot (path sem leading /)
    // -------------------------------------------------------------------------
    private static ByteBuffer tryLoad(String path) {
        // Tentativa A: classloader da classe
        InputStream is = SPIRVUtils.class.getResourceAsStream(path);

        // Tentativa B: context classloader do Fabric Knot
        if (is == null) {
            String relative = path.startsWith("/") ? path.substring(1) : path;
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null) {
                is = ctx.getResourceAsStream(relative);
            }
        }

        if (is == null) return null;

        try {
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
        } finally {
            try { is.close(); } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // listAvailableSPVs — diagnóstico com paths reais do JAR
    // -------------------------------------------------------------------------
    public static void listAvailableSPVs() {
        String[] check = {
            "/assets/vulkanmod/shaders/basic/terrain/terrain.vert.spv",
            "/assets/vulkanmod/shaders/basic/terrain/terrain.frag.spv",
            "/assets/vulkanmod/shaders/basic/terrain_earlyz/terrain_earlyz.vert.spv",
            "/assets/vulkanmod/shaders/basic/terrain_earlyz/terrain_earlyz.frag.spv",
            "/assets/vulkanmod/shaders/basic/blit/blit.vert.spv",
            "/assets/vulkanmod/shaders/basic/blit/blit.frag.spv",
            "/assets/vulkanmod/shaders/basic/clouds/clouds.vert.spv",
            "/assets/vulkanmod/shaders/basic/clouds/clouds.frag.spv",
            "/assets/vulkanmod/shaders/core/screenquad/screenquad.vert.spv",
            "/assets/vulkanmod/shaders/post/blit/blit.vert.spv",
            "/assets/vulkanmod/shaders/post/blit/blur.vert.spv",
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

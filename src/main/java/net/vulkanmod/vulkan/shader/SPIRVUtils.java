package net.vulkanmod.vulkan.shader;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.config.Platform;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.NativeResource;
import org.lwjgl.util.shaderc.ShadercIncludeResolveI;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultReleaseI;
import org.lwjgl.vulkan.VK11;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memASCII;
import static org.lwjgl.util.shaderc.Shaderc.*;
import org.lwjgl.system.MemoryUtil;

public class SPIRVUtils {
    private static final boolean DEBUG = true;
    private static final boolean OPTIMIZATIONS = false;

    private static long compiler;
    private static long options;

    private static final ShaderIncluder SHADER_INCLUDER = new ShaderIncluder();
    private static final ShaderReleaser SHADER_RELEASER = new ShaderReleaser();
    private static final long pUserData = 0;

    private static ObjectArrayList<String> includePaths;

    static {
        // FIX #1: guard Android — libshaderc.so não existe em Android ARM64
        // Sem este guard, o JVM crasha ao tentar carregar a biblioteca nativa.
        if (!Platform.isAndroid()) {
            initCompiler();
        }
    }

    private static void initCompiler() {
        compiler = shaderc_compiler_initialize();

        if (compiler == NULL) {
            throw new RuntimeException("Failed to create shader compiler");
        }

        options = shaderc_compile_options_initialize();

        if (options == NULL) {
            throw new RuntimeException("Failed to create compiler options");
        }

        if (OPTIMIZATIONS)
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

        if (DEBUG)
            shaderc_compile_options_set_generate_debug_info(options);

        shaderc_compile_options_set_target_env(options, shaderc_env_version_vulkan_1_1, VK11.VK_API_VERSION_1_1);
        shaderc_compile_options_set_include_callbacks(options, SHADER_INCLUDER, SHADER_RELEASER, pUserData);

        includePaths = new ObjectArrayList<>();
        addIncludePath("/assets/vulkanmod/shaders/include/");
    }

    public static void addIncludePath(String path) {
        URL url = SPIRVUtils.class.getResource(path);

        if (url != null)
            includePaths.add(url.toExternalForm());
    }

    /**
     * Loads pre-compiled SPIR-V bytecode from Android package resources.
     * Used on Android ARM64 where libshaderc is not available.
     * 
     * @param resourcePath Path to the .spv file (e.g., "/assets/vulkanmod/shaders/basic/color.vert.spv")
     * @return SPIRV object with bytecode from resource
     * @throws RuntimeException if the .spv file cannot be loaded
     */
    public static SPIRV loadPrecompiledSPV(String resourcePath) {
        if (!resourcePath.endsWith(".spv")) {
            throw new IllegalArgumentException("Resource path must end with .spv: " + resourcePath);
        }

        try (var is = SPIRVUtils.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new RuntimeException("Pre-compiled SPIR-V not found: " + resourcePath);
            }
            
            byte[] bytesArray = is.readAllBytes();
            ByteBuffer bytecode = MemoryUtil.memAlloc(bytesArray.length);
            bytecode.put(bytesArray).flip();
            
            // For pre-compiled SPV, we use a dummy handle of 0 since we don't have the compilation result
            return new SPIRV(0L, bytecode);
            
        } catch (IOException e) {
            throw new RuntimeException("Failed to load pre-compiled SPIR-V: " + resourcePath, e);
        }
    }

    public static SPIRV compileShader(String filename, String source, ShaderKind shaderKind) {
        // FIX #1: Android uses pre-compiled SPIR-V — never compile at runtime.
        // Returns pre-compiled bytecode from package resources.
        if (Platform.isAndroid()) {
            String baseName = filename.replace(".vsh", "").replace(".fsh", "");
            String ext = shaderKind == ShaderKind.VERTEX_SHADER ? ".vert.spv" : ".frag.spv";

            // Tentar todos os caminhos possíveis onde os .spv podem estar
            String[] candidates = {
                "/assets/vulkanmod/shaders/basic/" + baseName + "/" + baseName + ext,
                "/assets/vulkanmod/shaders/core/" + baseName + "/" + baseName + ext,
                "/assets/vulkanmod/shaders/post/blit/" + baseName + ext,
                "/assets/vulkanmod/shaders/" + baseName + "/" + baseName + ext,
                "/assets/vulkanmod/shaders/basic/" + baseName + ext,
                "/assets/vulkanmod/shaders/" + baseName + ext,
            };

            for (String spvPath : candidates) {
                try (var is = SPIRVUtils.class.getResourceAsStream(spvPath)) {
                    if (is == null) continue;

                    byte[] bytes = is.readAllBytes();

                    // Validar magic SPIR-V: 0x07230203 (little-endian)
                    if (bytes.length < 20) continue; // menor que header mínimo

                    int magic = (bytes[0] & 0xFF)
                      | ((bytes[1] & 0xFF) << 8)
                      | ((bytes[2] & 0xFF) << 16)
                      | ((bytes[3] & 0xFF) << 24);
                    if (magic != 0x07230203) continue;

                    // Verificar que tem instruções reais (não é placeholder de 20 bytes)
                    if (bytes.length < 1000) {
                        // Log de aviso mas tentar usar mesmo assim
                        // (pode ser um shader muito simples)
                    }

                    ByteBuffer bytecode = MemoryUtil.memAlloc(bytes.length);
                    bytecode.put(bytes).flip();
                    return new SPIRV(0L, bytecode);

                } catch (IOException e) {
                    // tentar próximo candidato
                }
            }

            throw new RuntimeException(
                "[AndroidFix] SPV nao encontrado para shader: " + filename + " tipo=" + shaderKind
                + ". Candidatos tentados: " + java.util.Arrays.toString(candidates)
            );
        }

        if (source == null) {
            throw new NullPointerException("source for %s.%s is null".formatted(filename, shaderKind));
        }

        long result = shaderc_compile_into_spv(compiler, source, shaderKind.kind, filename, "main", options);

        if (result == NULL) {
            throw new RuntimeException("Failed to compile shader " + filename + " into SPIR-V");
        }

        if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
            String errorMessage = shaderc_result_get_error_message(result);
            throw new RuntimeException("Failed to compile shader %s into SPIR-V:\n\t%s".formatted(filename, errorMessage));
        }

        return new SPIRV(result, shaderc_result_get_bytes(result));
    }

    public enum ShaderKind {
        VERTEX_SHADER(shaderc_glsl_vertex_shader),
        GEOMETRY_SHADER(shaderc_glsl_geometry_shader),
        FRAGMENT_SHADER(shaderc_glsl_fragment_shader),
        COMPUTE_SHADER(shaderc_glsl_compute_shader);

        private final int kind;

        ShaderKind(int kind) {
            this.kind = kind;
        }
    }

    private static class ShaderIncluder implements ShadercIncludeResolveI {

        private static final int MAX_PATH_LENGTH = 4096;

        @Override
        public long invoke(long user_data, long requested_source, int type, long requesting_source, long include_depth) {
            var requesting = memASCII(requesting_source);
            var requested = memASCII(requested_source);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                Path path;

                for (String includePath : includePaths) {
                    path = Paths.get(new URI(String.format("%s%s", includePath, requested)));

                    if (Files.exists(path)) {
                        byte[] bytes = Files.readAllBytes(path);

                        return ShadercIncludeResult.malloc(stack)
                                                   .source_name(stack.ASCII(requested))
                                                   .content(stack.bytes(bytes))
                                                   .user_data(user_data).address();
                    }
                }
            } catch (IOException | URISyntaxException e) {
                throw new RuntimeException(e);
            }

            throw new RuntimeException(String.format("%s: Unable to find %s in include paths", requesting, requested));
        }
    }

    private static class ShaderReleaser implements ShadercIncludeResultReleaseI {

        @Override
        public void invoke(long user_data, long include_result) {
        }
    }

    public static final class SPIRV implements NativeResource {

        private final long handle;
        private ByteBuffer bytecode;

        public SPIRV(long handle, ByteBuffer bytecode) {
            this.handle = handle;
            this.bytecode = bytecode;
        }

        public ByteBuffer bytecode() {
            return bytecode;
        }

        @Override
        public void free() {
            bytecode = null;
        }
    }
}

package net.vulkanmod.vulkan.shader;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class SPIRVUtils {

        public static final boolean IS_ANDROID = detectAndroid();

            private static boolean detectAndroid() {
                        try {
                                        Class.forName("android.os.Build");
                                                    System.err.println("[VULKANMOD] Platform: ANDROID");
                                                                return true;
                        } catch (ClassNotFoundException e) {
                                        System.err.println("[VULKANMOD] Platform: PC");
                                                    return false;
                        }
            }

                public static SPIRV loadSPV(String resourcePath) {
                            InputStream is = SPIRVUtils.class.getResourceAsStream(resourcePath);
                                    if (is == null) {
                                                    System.err.println("[VULKANMOD] SHADER MISSING: " + resourcePath);
                                                                return null;
                                    }
                                            try {
                                                            byte[] bytes = is.readAllBytes();
                                                                        is.close();
                                                                                    if (!validateSPIRVMagic(bytes, resourcePath)) return null;
                                                                                                ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
                                                                                                            buffer.put(bytes).flip();
                                                                                                                        System.err.println("[VULKANMOD] SHADER OK: " + resourcePath);
                                                                                                                                    return new SPIRV(MemoryUtil.memAddress(buffer), buffer);
                                            } catch (IOException e) {
                                                            System.err.println("[VULKANMOD] SHADER IO ERROR: " + resourcePath);
                                                                        return null;
                                            }
                }

                    public static String spvPath(String name, String stage) {
                                return "/assets/vulkanmod/shaders/" + name + "." + stage + ".spv";
                    }

                        private static boolean validateSPIRVMagic(byte[] bytes, String path) {
                                    if (bytes.length < 4) {
                                                    System.err.println("[VULKANMOD] INVALID SPIRV (too small): " + path);
                                                                return false;
                                    }
                                            boolean valid = (bytes[0] & 0xFF) == 0x03
                                                                 && (bytes[1] & 0xFF) == 0x02
                                                                                      && (bytes[2] & 0xFF) == 0x23
                                                                                                           && (bytes[3] & 0xFF) == 0x07;
                                                                                                                   if (!valid) System.err.println("[VULKANMOD] INVALID SPIRV MAGIC: " + path);
                                                                                                                           return valid;
                        }

                            @Deprecated
                                public static SPIRV compileShaderAbsoluteFile(String f, ShaderKind k) {
                                            System.err.println("[VULKANMOD] compileShaderAbsoluteFile BLOQUEADO: " + f);
                                                    return null;
                                }

                                    @Deprecated
                                        public static SPIRV compileShader(String f, String src, ShaderKind k) {
                                                    System.err.println("[VULKANMOD] compileShader BLOQUEADO: " + f);
                                                            return null;
                                        }

                                            public enum ShaderKind {
                                                        VERTEX_SHADER(0), GEOMETRY_SHADER(1),
                                                                FRAGMENT_SHADER(2), COMPUTE_SHADER(3);
                                                                        public final int kind;
                                                                                ShaderKind(int kind) { this.kind = kind; }
                                            }

                                                public static final class SPIRV implements NativeResource {
                                                            private final long handle;
                                                                    private ByteBuffer bytecode;
                                                                            public SPIRV(long handle, ByteBuffer bytecode) {
                                                                                            this.handle = handle;
                                                                                                        this.bytecode = bytecode;
                                                                            }
                                                                                    public ByteBuffer bytecode() { return bytecode; }
                                                                                            public long handle() { return handle; }
                                                                                                    @Override
                                                                                                            public void free() {
                                                                                                                            if (bytecode != null) {
                                                                                                                                                MemoryUtil.memFree(bytecode);
                                                                                                                                                                bytecode = null;
                                                                                                                            }
                                                                                                            }
                                                }
}

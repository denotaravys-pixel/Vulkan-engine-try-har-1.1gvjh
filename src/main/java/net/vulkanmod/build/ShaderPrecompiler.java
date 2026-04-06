package net.vulkanmod.build;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shader Pre-compiler for VulkanMod Android ARM64
 * Converts GLSL source (.vsh / .fsh) to SPIR-V bytecode (.spv)
 * 
 * Note: This tool requires shaderc library and must be run on desktop platforms only.
 * The pre-compiled .spv files are then packaged for Android deployment.
 */
public class ShaderPrecompiler {

    public static void main(String[] args) throws Exception {
        System.out.println("🔨 Pré-compilando shaders VulkanMod Android...");
        
        Path shadersDir = Paths.get("src/main/resources/assets/vulkanmod/shaders");
        
        if (!Files.exists(shadersDir)) {
            System.err.println("❌ Diretório de shaders não encontrado: " + shadersDir.toAbsolutePath());
            System.exit(1);
        }
        
        AtomicInteger vertCount = new AtomicInteger(0);
        AtomicInteger fragCount = new AtomicInteger(0);
        
        // Vertex shaders (.vsh → .vert.spv)
        System.out.println("\n📋 Processando vertex shaders...");
        Files.walkFileTree(shadersDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".vsh")) {
                    try {
                        String source = Files.readString(file);
                        String baseName = file.getFileName().toString().replace(".vsh", "");
                        Path spvFile = file.getParent().resolve(baseName + ".vert.spv");
                        
                        // Placeholder: In production, use actual shaderc compilation
                        // byte[] spirvBytes = ShaderCompiler.compileVertex(source);
                        byte[] spirvBytes = createPlaceholderSPV(source);
                        
                        Files.write(spvFile, spirvBytes);
                        System.out.println("  ✅ " + spvFile.getFileName());
                        vertCount.incrementAndGet();
                        
                    } catch (Exception e) {
                        System.err.println("  ❌ " + file + ": " + e.getMessage());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        
        // Fragment shaders (.fsh → .frag.spv)
        System.out.println("\n📋 Processando fragment shaders...");
        Files.walkFileTree(shadersDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".fsh")) {
                    try {
                        String source = Files.readString(file);
                        String baseName = file.getFileName().toString().replace(".fsh", "");
                        Path spvFile = file.getParent().resolve(baseName + ".frag.spv");
                        
                        // Placeholder: In production, use actual shaderc compilation
                        // byte[] spirvBytes = ShaderCompiler.compileFragment(source);
                        byte[] spirvBytes = createPlaceholderSPV(source);
                        
                        Files.write(spvFile, spirvBytes);
                        System.out.println("  ✅ " + spvFile.getFileName());
                        fragCount.incrementAndGet();
                        
                    } catch (Exception e) {
                        System.err.println("  ❌ " + file + ": " + e.getMessage());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        
        int total = vertCount.get() + fragCount.get();
        System.out.printf("\n🎉 Compilação concluída: %d vert + %d frag = %d shaders pré-compilados!%n",
                vertCount.get(), fragCount.get(), total);
        
        if (total == 0) {
            System.err.println("⚠️  Nenhum shader encontrado para compilação!");
            System.exit(1);
        }
    }
    
    /**
     * REMOVIDO: createPlaceholderSPV gerava ficheiros inválidos.
     * Os shaders devem ser compilados com glslc --target-env=vulkan1.1
     * NUNCA usar este método. O ShaderPrecompiler.java é apenas referência histórica.
     */
    @Deprecated
    private static byte[] createPlaceholderSPV(String source) {
        throw new UnsupportedOperationException(
            "ERRO: createPlaceholderSPV foi removido. " +
            "Compilar shaders com: glslc --target-env=vulkan1.1 -fshader-stage=vertex INPUT.vsh -o OUTPUT.vert.spv"
        );
    }
}

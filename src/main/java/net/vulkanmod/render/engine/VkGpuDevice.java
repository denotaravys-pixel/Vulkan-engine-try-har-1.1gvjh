package net.vulkanmod.render.engine;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.*;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Platform;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.interfaces.shader.ExtendedRenderPipeline;
import net.vulkanmod.render.shader.ShaderLoadUtil;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.converter.GLSLParser;
import net.vulkanmod.vulkan.shader.converter.Lexer;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.Collections;
import java.util.function.BiFunction;
import java.util.function.Supplier;

@SuppressWarnings("NullableProblems")
public class VkGpuDevice implements GpuDevice {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final VkCommandEncoder encoder;
    private final VkDebugLabel debugLabels;
    private final int maxSupportedTextureSize;
    private final int uniformOffsetAlignment;
    private final BiFunction<ResourceLocation, ShaderType, String> defaultShaderSource;
    private final Map<RenderPipeline, GlRenderPipeline> pipelineCache = new IdentityHashMap<>();
    private final Map<ShaderCompilationKey, GlShaderModule> shaderCache = new HashMap<>();
    private final Set<String> enabledExtensions = new HashSet<>();
    private final Map<ShaderCompilationKey, String> shaderSrcCache = new HashMap<>();

    // FIX: Rastreia pipelines que já falharam para evitar flood de logs e
    // tentativas repetidas a cada frame. Usa IdentityHashMap para comparação
    // por referência (igual ao pipelineCache acima).
    private final Set<RenderPipeline> failedPipelines =
            Collections.newSetFromMap(new IdentityHashMap<>());

    public VkGpuDevice(long l, int i, boolean bl, BiFunction<ResourceLocation, ShaderType, String> shaderSource, boolean bl2) {
        this.debugLabels = VkDebugLabel.create(bl2, this.enabledExtensions);
        this.maxSupportedTextureSize = VRenderSystem.maxSupportedTextureSize();
        this.uniformOffsetAlignment = (int) DeviceManager.deviceProperties.limits().minUniformBufferOffsetAlignment();
        this.defaultShaderSource = shaderSource;
        this.encoder = new VkCommandEncoder(this);
    }

    public VkDebugLabel debugLabels() {
        return this.debugLabels;
    }

    @Override
    public CommandEncoder createCommandEncoder() {
        return this.encoder;
    }

    @Override
    public GpuTexture createTexture(@Nullable Supplier<String> supplier, int usage, TextureFormat textureFormat, int width, int height, int layers, int mipLevels) {
        return this.createTexture(this.debugLabels.exists() && supplier != null ? supplier.get() : null, usage, textureFormat, width, height, layers, mipLevels);
    }

    @Override
    public GpuTexture createTexture(@Nullable String string, int usage, TextureFormat textureFormat, int width, int height, int layers, int mipLevels) {
        if (mipLevels < 1) {
            throw new IllegalArgumentException("mipLevels must be at least 1");
        } else {
            int id = VkGlTexture.genTextureId();
            if (string == null) {
                string = String.valueOf(id);
            }

            int format = VkGpuTexture.vkFormat(textureFormat);
            int viewType = VkGpuTexture.vkImageViewType(usage);
            boolean depthFormat = VulkanImage.isDepthFormat(format);
            int attachmentUsage = depthFormat
                    ? VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
                    : VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;

            VulkanImage texture = VulkanImage.builder(width, height)
                                             .setName(string)
                                             .setFormat(format)
                                             .setArrayLayers(layers)
                                             .setMipLevels(mipLevels)
                                             .addUsage(attachmentUsage)
                                             .setViewType(viewType)
                                             .createVulkanImage();

            VkGlTexture vGlTexture = VkGlTexture.getTexture(id);
            vGlTexture.setVulkanImage(texture);
            VkGlTexture.bindTexture(id);

            VkGpuTexture glTexture = new VkGpuTexture(usage, string, textureFormat, width, height, layers, mipLevels, id, vGlTexture);
            this.debugLabels.applyLabel(glTexture);
            return glTexture;
        }
    }

    public VkGpuTexture gpuTextureFromVulkanImage(VulkanImage image) {
        int id = VkGlTexture.genTextureId();
        VkGlTexture glTexture = VkGlTexture.getTexture(id);
        glTexture.setVulkanImage(image);
        TextureFormat textureFormat = VkGpuTexture.textureFormat(image.format);
        VkGpuTexture gpuTexture = new VkGpuTexture(0, image.name, textureFormat, image.width, image.height, 1, image.mipLevels, id, glTexture);
        this.debugLabels.applyLabel(gpuTexture);
        return gpuTexture;
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture gpuTexture) {
        return this.createTextureView(gpuTexture, 0, gpuTexture.getMipLevels());
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture gpuTexture, int startLevel, int levels) {
        if (gpuTexture.isClosed()) {
            throw new IllegalArgumentException("Can't create texture view with closed texture");
        } else if (startLevel >= 0 && startLevel + levels <= gpuTexture.getMipLevels()) {
            if (gpuTexture.getClass() != VkGpuTexture.class) {
                gpuTexture = VkGpuTexture.fromGlTexture((GlTexture) gpuTexture);
            }
            return new VkTextureView((VkGpuTexture) gpuTexture, startLevel, levels);
        } else {
            throw new IllegalArgumentException(
                    levels + " mip levels starting from " + startLevel
                    + " would be out of range for texture with only " + gpuTexture.getMipLevels() + " mip levels"
            );
        }
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> supplier, int usage, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size must be greater than zero");
        } else {
            return new VkGpuBuffer(this.debugLabels, supplier, usage, size);
        }
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> supplier, int usage, ByteBuffer byteBuffer) {
        if (!byteBuffer.hasRemaining()) {
            throw new IllegalArgumentException("Buffer source must not be empty");
        } else {
            VkGpuBuffer glBuffer = new VkGpuBuffer(this.debugLabels, supplier, usage, byteBuffer.remaining());
            this.encoder.writeToBuffer(glBuffer.slice(), byteBuffer);
            return glBuffer;
        }
    }

    @Override
    public String getImplementationInformation() {
        return "Vulkan " + Vulkan.getDevice().vkVersion + ", " + Vulkan.getDevice().vendorIdString;
    }

    @Override
    public List<String> getLastDebugMessages() {
        return Collections.emptyList();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    @Override
    public String getRenderer() {
        return DeviceManager.device.deviceName;
    }

    @Override
    public String getVendor() {
        return Vulkan.getDevice().vendorIdString;
    }

    @Override
    public String getBackendName() {
        return "Vulkan";
    }

    @Override
    public String getVersion() {
        return Vulkan.getDevice().vkVersion;
    }

    private static int getMaxSupportedTextureSize() {
        int i = GlStateManager._getInteger(3379);

        for (int j = Math.max(32768, i); j >= 1024; j >>= 1) {
            GlStateManager._texImage2D(32868, 0, 6408, j, j, 0, 6408, 5121, null);
            int k = GlStateManager._getTexLevelParameter(32868, 0, 4096);
            if (k != 0) {
                return j;
            }
        }

        int jx = Math.max(i, 1024);
        LOGGER.info("Failed to determine maximum texture size by probing, trying GL_MAX_TEXTURE_SIZE = {}", jx);
        return jx;
    }

    @Override
    public int getMaxTextureSize() {
        return this.maxSupportedTextureSize;
    }

    @Override
    public int getUniformOffsetAlignment() {
        return this.uniformOffsetAlignment;
    }

    @Override
    public void clearPipelineCache() {
        for (GlRenderPipeline glRenderPipeline : this.pipelineCache.values()) {
            if (glRenderPipeline.program() != GlProgram.INVALID_PROGRAM) {
                glRenderPipeline.program().close();
            }
        }

        this.pipelineCache.clear();

        for (GlShaderModule glShaderModule : this.shaderCache.values()) {
            if (glShaderModule != GlShaderModule.INVALID_SHADER) {
                glShaderModule.close();
            }
        }

        this.shaderCache.clear();
        this.failedPipelines.clear();
    }

    @Override
    public List<String> getEnabledExtensions() {
        return new ArrayList(this.enabledExtensions);
    }

    @Override
    public void close() {
        this.clearPipelineCache();
    }

    protected GlShaderModule getOrCompileShader(
            ResourceLocation resourceLocation, ShaderType shaderType, ShaderDefines shaderDefines,
            BiFunction<ResourceLocation, ShaderType, String> biFunction
    ) {
        ShaderCompilationKey shaderCompilationKey = new ShaderCompilationKey(resourceLocation, shaderType, shaderDefines);
        return this.shaderCache.computeIfAbsent(shaderCompilationKey,
                shaderCompilationKey2 -> this.compileShader(shaderCompilationKey, biFunction));
    }

    protected String getCachedShaderSrc(
            ResourceLocation resourceLocation, ShaderType shaderType, ShaderDefines shaderDefines,
            BiFunction<ResourceLocation, ShaderType, String> shaderSourceGetter
    ) {
        ShaderCompilationKey shaderCompilationKey = new ShaderCompilationKey(resourceLocation, shaderType, shaderDefines);

        return this.shaderSrcCache.computeIfAbsent(shaderCompilationKey, compilationKey -> {
            String shaderExtension = switch (shaderType) {
                case VERTEX -> ".vsh";
                case FRAGMENT -> ".fsh";
            };

            String shaderName = resourceLocation.getPath() + shaderExtension;

            if (ShaderLoadUtil.REMAPPED_SHADERS.contains(shaderName)) {
                String src = ShaderLoadUtil.getShaderSource(resourceLocation, shaderType);

                if (src == null) {
                    // FIX: Formato correcto — a mensagem original não passava o argumento
                    throw new RuntimeException("shader: (%s) not found.".formatted(resourceLocation));
                }

                return src;
            }

            return shaderSourceGetter.apply(compilationKey.id, compilationKey.type);
        });
    }

    /**
     * FIX ANDROID — precompilePipeline
     *
     * Guard anterior: if (!Platform.isAndroid()) compilePipeline(...)
     * Resultado: em Android, NENHUM pipeline do Minecraft era criado → ecrã negro.
     *
     * Novo comportamento:
     * - Tenta criar o pipeline em qualquer plataforma
     * - Erros são apanhados e logados UMA VEZ por pipeline (sem flood)
     * - Retorna sempre VkRenderPipeline válido (MC não crasha)
     * - Se SPIRVUtils conseguir carregar SPIR-V precompilado para este shader → funciona
     * - Se não conseguir → pipeline fica null → draws são skipped graciosamente
     */
    @Override
    public CompiledRenderPipeline precompilePipeline(
            RenderPipeline renderPipeline,
            @Nullable BiFunction<ResourceLocation, ShaderType, String> shaderSourceGetter
    ) {
        if (!failedPipelines.contains(renderPipeline)
                && ExtendedRenderPipeline.of(renderPipeline).getPipeline() == null) {

            BiFunction<ResourceLocation, ShaderType, String> srcGetter =
                    shaderSourceGetter == null ? this.defaultShaderSource : shaderSourceGetter;

            try {
                compilePipelineInternal(renderPipeline, srcGetter);
            } catch (Exception e) {
                LOGGER.warn("[VkGpuDevice] precompilePipeline falhou para '{}': {}",
                        renderPipeline.getLocation(), e.getMessage());
                // Marca como falhado — não tentar de novo a cada frame
                failedPipelines.add(renderPipeline);
            }
        }

        return new VkRenderPipeline(renderPipeline);
    }

    /**
     * FIX ANDROID — compilePipeline (lazy, chamado por VkRenderPass.setPipeline)
     *
     * Guard anterior: if (Platform.isAndroid()) return;
     * Resultado: lazy compilation bloqueada em Android → pipeline nunca criado.
     *
     * Novo comportamento:
     * - Tenta compilar em qualquer plataforma
     * - Se já falhou antes → skip silencioso (sem spam de logs)
     * - Se pipeline já foi criado com sucesso → skip
     */
    public void compilePipeline(RenderPipeline renderPipeline) {
        // Já falhou antes → não repetir tentativa (evita flood de logs)
        if (failedPipelines.contains(renderPipeline)) return;

        // Já compilado com sucesso → nada a fazer
        if (ExtendedRenderPipeline.of(renderPipeline).getPipeline() != null) return;

        try {
            compilePipelineInternal(renderPipeline, this.defaultShaderSource);
        } catch (Exception e) {
            LOGGER.warn("[VkGpuDevice] compilePipeline (lazy) falhou para '{}': {}",
                    renderPipeline.getLocation(), e.getMessage());
            failedPipelines.add(renderPipeline);
        }
    }

    private GlShaderModule compileShader(
            ShaderCompilationKey shaderCompilationKey,
            BiFunction<ResourceLocation, ShaderType, String> biFunction
    ) {
        String string = biFunction.apply(shaderCompilationKey.id, shaderCompilationKey.type);
        if (string == null) {
            LOGGER.error("Couldn't find source for {} shader ({})",
                    shaderCompilationKey.type, shaderCompilationKey.id);
            return GlShaderModule.INVALID_SHADER;
        } else {
            String string2 = GlslPreprocessor.injectDefines(string, shaderCompilationKey.defines);
            int i = GlStateManager.glCreateShader(GlConst.toGl(shaderCompilationKey.type));
            GlStateManager.glShaderSource(i, string2);
            GlStateManager.glCompileShader(i);
            if (GlStateManager.glGetShaderi(i, 35713) == 0) {
                String string3 = StringUtils.trim(GlStateManager.glGetShaderInfoLog(i, 32768));
                LOGGER.error("Couldn't compile {} shader ({}): {}",
                        shaderCompilationKey.type.getName(), shaderCompilationKey.id, string3);
                return GlShaderModule.INVALID_SHADER;
            } else {
                GlShaderModule glShaderModule = new GlShaderModule(i, shaderCompilationKey.id, shaderCompilationKey.type);
                this.debugLabels.applyLabel(glShaderModule);
                return glShaderModule;
            }
        }
    }

    /**
     * Lógica central de compilação de pipeline — partilhada por precompilePipeline e compilePipeline.
     *
     * FLUXO:
     * 1. Resolve nome do config a partir do ResourceLocation
     * 2. Obtém fonte GLSL (de MC ou de REMAPPED_SHADERS do VulkanMod)
     * 3. Processa GLSL com GLSLParser → extrai UBOs, samplers
     * 4. Compila GLSL processado → SPIR-V via SPIRVUtils
     *    - Em Desktop: usa libshaderc
     *    - Em Android: SPIRVUtils tenta carregar SPIR-V precompilado do resource pack
     *      Se não existir SPV para este shader → lança excepção → apanhada pelo caller
     * 5. Cria GraphicsPipeline Vulkan
     * 6. Cria EGlProgram com UBOs/samplers do shader
     * 7. Regista pipeline e programa em ExtendedRenderPipeline (mixin)
     *
     * NOTA: Se SPIRVUtils retornar null (Android sem SPV precompilado), o Builder
     * lança IllegalArgumentException em createGraphicsPipeline(). O caller trata-o.
     */
    private void compilePipelineInternal(
            RenderPipeline renderPipeline,
            BiFunction<ResourceLocation, ShaderType, String> shaderSrcGetter
    ) {
        String locationPath = renderPipeline.getLocation().getPath();

        String configName;
        if (locationPath.contains("core")) {
            configName = locationPath.split("/")[1];
        } else {
            configName = locationPath;
        }

        Pipeline.Builder builder = new Pipeline.Builder(renderPipeline.getVertexFormat(), configName);
        ExtendedRenderPipeline extPipeline = ExtendedRenderPipeline.of(renderPipeline);

        ResourceLocation vertexShaderLocation = renderPipeline.getVertexShader();
        ResourceLocation fragmentShaderLocation = renderPipeline.getFragmentShader();
        ShaderDefines shaderDefines = renderPipeline.getShaderDefines();

        // FIX: null-check nas fontes GLSL — shader pode não estar disponível
        String vshSrc = this.getCachedShaderSrc(vertexShaderLocation, ShaderType.VERTEX, shaderDefines, shaderSrcGetter);
        String fshSrc = this.getCachedShaderSrc(fragmentShaderLocation, ShaderType.FRAGMENT, shaderDefines, shaderSrcGetter);

        if (vshSrc == null || fshSrc == null) {
            throw new RuntimeException("Shader source null para pipeline '%s' (vert=%s, frag=%s)"
                    .formatted(configName,
                               vshSrc == null ? "NULL" : "OK",
                               fshSrc == null ? "NULL" : "OK"));
        }

        vshSrc = GlslPreprocessor.injectDefines(vshSrc, shaderDefines);
        fshSrc = GlslPreprocessor.injectDefines(fshSrc, shaderDefines);

        Lexer lexer = new Lexer(vshSrc);
        GLSLParser parser = new GLSLParser();
        parser.setVertexFormat(renderPipeline.getVertexFormat());

        try {
            parser.parse(lexer, GLSLParser.Stage.VERTEX);
            lexer = new Lexer(fshSrc);
            parser.parse(lexer, GLSLParser.Stage.FRAGMENT);
        } catch (Exception e) {
            throw new RuntimeException("GLSLParser falhou para '%s'".formatted(renderPipeline), e);
        }

        UBO[] ubos = parser.createUBOs();
        String vshProcessed = parser.getOutput(GLSLParser.Stage.VERTEX);
        String fshProcessed = parser.getOutput(GLSLParser.Stage.FRAGMENT);

        builder.setUniforms(List.of(ubos), parser.getSamplerList());

        // compileShaders chama SPIRVUtils.compileShader() internamente.
        // Em Android: SPIRVUtils tenta carregar SPIR-V precompilado pelo nome.
        // Se não encontrar → retorna null → createGraphicsPipeline() lança
        // IllegalArgumentException → apanhada pelo caller (compilePipeline / precompilePipeline)
        builder.compileShaders(configName, vshProcessed, fshProcessed);

        GraphicsPipeline pipeline;
        try {
            pipeline = builder.createGraphicsPipeline();
        } catch (Exception e) {
            throw new RuntimeException("createGraphicsPipeline falhou para '%s'".formatted(renderPipeline), e);
        }

        EGlProgram eGlProgram = new EGlProgram(1, configName);
        eGlProgram.setupUniforms(pipeline, renderPipeline.getUniforms(), renderPipeline.getSamplers());
        extPipeline.setProgram(eGlProgram);
        extPipeline.setPipeline(pipeline);

        LOGGER.debug("[VkGpuDevice] Pipeline compilado com sucesso: {}", configName);
    }

    @Environment(EnvType.CLIENT)
    record ShaderCompilationKey(ResourceLocation id, ShaderType type, ShaderDefines defines) {

        public String toString() {
            String string = this.id + " (" + this.type + ")";
            return !this.defines.isEmpty() ? string + " with " + this.defines : string;
        }
    }

    private static class VkRenderPipeline implements CompiledRenderPipeline {
        final RenderPipeline renderPipeline;

        public VkRenderPipeline(RenderPipeline renderPipeline) {
            this.renderPipeline = renderPipeline;
        }

        @Override
        public boolean isValid() {
            return true;
        }
    }
}

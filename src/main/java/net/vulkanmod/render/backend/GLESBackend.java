package net.vulkanmod.render.backend;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import static org.lwjgl.opengl.GL11.*;

public class GLESBackend implements RenderBackend {

    private RenderBackend fallback;
    private GLCapabilities caps;

    @Override
    public void init() {
        caps = GL.createCapabilities();
        // TODO: initialize GL ES context
    }

    @Override
    public void cleanup() {
        // TODO: cleanup GL ES resources
    }

    @Override
    public boolean isVulkan() {
        return false;
    }

    @Override
    public boolean isGLES() {
        return true;
    }

    @Override
    public void renderChunk() {
        // Fallback to Vulkan for chunks
        if (fallback != null) {
            fallback.renderChunk();
        }
    }

    @Override
    public void renderEntity() {
        // Fallback to Vulkan for entities
        if (fallback != null) {
            fallback.renderEntity();
        }
    }

    @Override
    public void renderWater() {
        // Fallback to Vulkan for water
        if (fallback != null) {
            fallback.renderWater();
        }
    }

    @Override
    public void renderGui() {
        // TODO: implement GUI rendering
    }

    @Override
    public void renderText() {
        // TODO: implement text rendering
    }

    @Override
    public void renderParticles() {
        // TODO: implement particle rendering
    }

    @Override
    public void renderSky() {
        // TODO: implement sky rendering
    }

    @Override
    public void renderOverlays() {
        // TODO: implement overlay rendering
    }

    @Override
    public void beginFrame() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    @Override
    public void endFrame() {
        // TODO: end GL ES frame
    }

    @Override
    public void swapBuffers() {
        // TODO: swap GL ES buffers
    }

    @Override
    public boolean supportsFeature(String feature) {
        switch (feature) {
            case "framebuffer_fetch":
                return caps.GL_ARM_shader_framebuffer_fetch;
            case "pixel_local_storage":
                return caps.GL_EXT_shader_pixel_local_storage;
            default:
                return false;
        }
    }

    @Override
    public void setFallback(RenderBackend fallback) {
        this.fallback = fallback;
    }
}
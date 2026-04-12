package net.vulkanmod.render.backend;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;

public class VulkanBackend implements RenderBackend {

    private RenderBackend fallback;

    @Override
    public void init() {
        Vulkan.initVulkan(0); // TODO: pass window handle
        Renderer.initRenderer();
    }

    @Override
    public void cleanup() {
        // TODO: cleanup Vulkan resources
    }

    @Override
    public boolean isVulkan() {
        return true;
    }

    @Override
    public boolean isGLES() {
        return false;
    }

    @Override
    public void renderChunk() {
        // TODO: implement chunk rendering
    }

    @Override
    public void renderEntity() {
        // TODO: implement entity rendering
    }

    @Override
    public void renderWater() {
        // TODO: implement water rendering
    }

    @Override
    public void renderGui() {
        // Fallback to GL ES for GUI
        if (fallback != null) {
            fallback.renderGui();
        }
    }

    @Override
    public void renderText() {
        // Fallback to GL ES for text
        if (fallback != null) {
            fallback.renderText();
        }
    }

    @Override
    public void renderParticles() {
        // Fallback to GL ES for particles
        if (fallback != null) {
            fallback.renderParticles();
        }
    }

    @Override
    public void renderSky() {
        // TODO: implement sky rendering
    }

    @Override
    public void renderOverlays() {
        // Fallback to GL ES for overlays
        if (fallback != null) {
            fallback.renderOverlays();
        }
    }

    @Override
    public void beginFrame() {
        // TODO: begin Vulkan frame
    }

    @Override
    public void endFrame() {
        // TODO: end Vulkan frame
    }

    @Override
    public void swapBuffers() {
        // TODO: swap Vulkan buffers
    }

    @Override
    public boolean supportsFeature(String feature) {
        // TODO: check Vulkan feature support
        return true;
    }

    @Override
    public void setFallback(RenderBackend fallback) {
        this.fallback = fallback;
    }
}
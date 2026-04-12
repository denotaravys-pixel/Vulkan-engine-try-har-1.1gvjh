package net.vulkanmod.render.backend;

public interface RenderBackend {

    void init();

    void cleanup();

    boolean isVulkan();

    boolean isGLES();

    // Methods for rendering different types
    void renderChunk();

    void renderEntity();

    void renderWater();

    void renderGui();

    void renderText();

    void renderParticles();

    void renderSky();

    void renderOverlays();

    // Frame management
    void beginFrame();

    void endFrame();

    void swapBuffers();

    // Fallback mechanism
    boolean supportsFeature(String feature);

    void setFallback(RenderBackend fallback);
}
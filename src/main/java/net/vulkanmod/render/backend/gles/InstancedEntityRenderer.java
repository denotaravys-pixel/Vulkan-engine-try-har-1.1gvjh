package net.vulkanmod.render.backend.gles;

import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL33.glVertexAttribDivisor;

public class InstancedEntityRenderer {

    private static final boolean IS_ANDROID = net.vulkanmod.vulkan.shader.SPIRVUtils.IS_ANDROID;

    private static final int MAX_INSTANCES = 1000;
    private static final int INSTANCE_MATRIX_SIZE = 16; // 4x4 matrix

    private int instanceVBO;
    private FloatBuffer instanceBuffer;
    private Map<String, List<float[]>> entityBatches;

    public InstancedEntityRenderer() {
        entityBatches = new HashMap<>();
        initBuffers();
    }

    private void initBuffers() {
        if (!IS_ANDROID) {
            instanceVBO = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, instanceVBO);

            instanceBuffer = MemoryUtil.memAllocFloat(MAX_INSTANCES * INSTANCE_MATRIX_SIZE);
            glBufferData(GL_ARRAY_BUFFER, instanceBuffer, GL_DYNAMIC_DRAW);

            // Setup vertex attributes for instance matrices
            for (int i = 0; i < 4; i++) {
                glEnableVertexAttribArray(4 + i);
                glVertexAttribPointer(4 + i, 4, GL_FLOAT, false,
                    INSTANCE_MATRIX_SIZE * 4, i * 16L);
                glVertexAttribDivisor(4 + i, 1); // Instanced
            }

            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }
    }

    public void addEntity(String entityType, float[] transformMatrix) {
        entityBatches.computeIfAbsent(entityType, k -> new ArrayList<>()).add(transformMatrix);
    }

    public void renderEntities() {
        for (Map.Entry<String, List<float[]>> entry : entityBatches.entrySet()) {
            String entityType = entry.getKey();
            List<float[]> instances = entry.getValue();

            if (instances.isEmpty()) continue;

            // Upload instance matrices
            instanceBuffer.clear();
            for (float[] matrix : instances) {
                instanceBuffer.put(matrix);
            }
            instanceBuffer.flip();

            if (!IS_ANDROID) {
                glBindBuffer(GL_ARRAY_BUFFER, instanceVBO);
                glBufferSubData(GL_ARRAY_BUFFER, 0, instanceBuffer);

                // TODO: Bind entity mesh/VAO for this type
                // Assume VAO is already bound with vertex data

                // Draw instanced
                glDrawElementsInstanced(GL_TRIANGLES, getIndexCount(entityType),
                    GL_UNSIGNED_INT, 0, instances.size());

                glBindBuffer(GL_ARRAY_BUFFER, 0);
            }
        }

        // Clear batches for next frame
        entityBatches.clear();
    }

    private int getIndexCount(String entityType) {
        // TODO: Return actual index count for entity type
        return 36; // Cube has 36 indices
    }

    public void cleanup() {
        if (!IS_ANDROID && instanceVBO != 0) {
            glDeleteBuffers(instanceVBO);
        }
        if (instanceBuffer != null) {
            MemoryUtil.memFree(instanceBuffer);
        }
    }
}
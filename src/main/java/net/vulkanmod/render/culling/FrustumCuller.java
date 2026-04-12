package net.vulkanmod.render.culling;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public class FrustumCuller {

    private static final Vector4f[] frustumPlanes = new Vector4f[6];
    private static final Matrix4f tempMatrix = new Matrix4f();

    static {
        for (int i = 0; i < 6; i++) {
            frustumPlanes[i] = new Vector4f();
        }
    }

    public static void updateFrustum(Matrix4f projectionViewMatrix) {
        // Extract frustum planes from projection-view matrix
        // Left plane
        frustumPlanes[0].set(
            projectionViewMatrix.m03() + projectionViewMatrix.m00(),
            projectionViewMatrix.m13() + projectionViewMatrix.m10(),
            projectionViewMatrix.m23() + projectionViewMatrix.m20(),
            projectionViewMatrix.m33() + projectionViewMatrix.m30()
        );

        // Right plane
        frustumPlanes[1].set(
            projectionViewMatrix.m03() - projectionViewMatrix.m00(),
            projectionViewMatrix.m13() - projectionViewMatrix.m10(),
            projectionViewMatrix.m23() - projectionViewMatrix.m20(),
            projectionViewMatrix.m33() - projectionViewMatrix.m30()
        );

        // Bottom plane
        frustumPlanes[2].set(
            projectionViewMatrix.m03() + projectionViewMatrix.m01(),
            projectionViewMatrix.m13() + projectionViewMatrix.m11(),
            projectionViewMatrix.m23() + projectionViewMatrix.m21(),
            projectionViewMatrix.m33() + projectionViewMatrix.m31()
        );

        // Top plane
        frustumPlanes[3].set(
            projectionViewMatrix.m03() - projectionViewMatrix.m01(),
            projectionViewMatrix.m13() - projectionViewMatrix.m11(),
            projectionViewMatrix.m23() - projectionViewMatrix.m21(),
            projectionViewMatrix.m33() - projectionViewMatrix.m31()
        );

        // Near plane
        frustumPlanes[4].set(
            projectionViewMatrix.m03() + projectionViewMatrix.m02(),
            projectionViewMatrix.m13() + projectionViewMatrix.m12(),
            projectionViewMatrix.m23() + projectionViewMatrix.m22(),
            projectionViewMatrix.m33() + projectionViewMatrix.m32()
        );

        // Far plane
        frustumPlanes[5].set(
            projectionViewMatrix.m03() - projectionViewMatrix.m02(),
            projectionViewMatrix.m13() - projectionViewMatrix.m12(),
            projectionViewMatrix.m23() - projectionViewMatrix.m22(),
            projectionViewMatrix.m33() - projectionViewMatrix.m32()
        );

        // Normalize planes
        for (Vector4f plane : frustumPlanes) {
            float length = (float) Math.sqrt(plane.x * plane.x + plane.y * plane.y + plane.z * plane.z);
            plane.div(length);
        }
    }

    public static boolean isChunkVisible(int chunkX, int chunkY, int chunkZ) {
        // Test chunk bounding box against all 6 frustum planes
        // Chunk is 16x16x16 blocks, test the 8 corners

        Vector3f[] corners = {
            new Vector3f(chunkX * 16, chunkY * 16, chunkZ * 16),
            new Vector3f(chunkX * 16 + 15, chunkY * 16, chunkZ * 16),
            new Vector3f(chunkX * 16, chunkY * 16 + 15, chunkZ * 16),
            new Vector3f(chunkX * 16 + 15, chunkY * 16 + 15, chunkZ * 16),
            new Vector3f(chunkX * 16, chunkY * 16, chunkZ * 16 + 15),
            new Vector3f(chunkX * 16 + 15, chunkY * 16, chunkZ * 16 + 15),
            new Vector3f(chunkX * 16, chunkY * 16 + 15, chunkZ * 16 + 15),
            new Vector3f(chunkX * 16 + 15, chunkY * 16 + 15, chunkZ * 16 + 15)
        };

        for (Vector4f plane : frustumPlanes) {
            boolean allOutside = true;

            for (Vector3f corner : corners) {
                // Distance from point to plane
                float distance = plane.x * corner.x + plane.y * corner.y +
                               plane.z * corner.z + plane.w;

                if (distance >= 0) {
                    allOutside = false;
                    break;
                }
            }

            if (allOutside) {
                return false; // Chunk is completely outside this plane
            }
        }

        return true; // Chunk is visible
    }
}
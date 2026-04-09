#version 450

layout(std140, binding = 0) uniform UBO {
    mat4 ProjMat;
    vec2 InSize;
    vec2 OutSize;
    vec2 BlurDir;
} ubo;

layout(location = 0) in vec4 Position;

layout(location = 0) out vec2 texCoord;
layout(location = 1) out vec2 sampleStep;

void main() {
    vec4 outPos = ubo.ProjMat * vec4(Position.xy * ubo.OutSize, 0.0, 1.0);
    gl_Position = vec4(outPos.xy, 0.2, 1.0);

    vec2 oneTexel = 1.0 / ubo.InSize;
    sampleStep = oneTexel * ubo.BlurDir;

    texCoord = vec2(Position.x, 1.0 - Position.y);
}

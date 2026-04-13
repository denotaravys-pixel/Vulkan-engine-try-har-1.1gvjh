import re
import shutil
import subprocess
from pathlib import Path

REPO_ROOT = Path('/workspaces/Vulkan-engine-try-har-1.1gvjh')
SPV_OUT = Path('/tmp/spv_out')
SPV_OUT.mkdir(parents=True, exist_ok=True)

# gather include files from repo root
INCLUDES = {}
for include_path in sorted(REPO_ROOT.glob('*.glsl')):
    INCLUDES[include_path.name] = include_path.read_text(errors='replace')

STUB_FOG = """
// --- stub: fog ---
float linear_fog_value(vec3 pos, float fogStart, float fogEnd) {
    return clamp((length(pos) - fogStart) / max(fogEnd - fogStart, 0.001), 0.0, 1.0);
}
float fog_spherical_distance(mat4 mv, vec3 pos) {
    return length((mv * vec4(pos, 1.0)).xyz);
}
float fog_cylindrical_distance(mat4 mv, vec3 pos) {
    return length((mv * vec4(pos, 1.0)).xz);
}
vec4 apply_fog(vec4 color, float spherDist, float cylDist,
               float envStart, float envEnd,
               float renderStart, float renderEnd, vec4 fogColor) {
    float d = min(spherDist, cylDist);
    float f = clamp((d - envStart) / max(envEnd - envStart, 0.001), 0.0, 1.0);
    return mix(color, fogColor, f * fogColor.a);
}
// --- end stub ---
"""

STUB_LIGHT_UNIFORMS = """
layout(std140, binding = 10) uniform LightBlock {
    vec3 Light0_Direction;
    float _pad0;
    vec4 Light0_Diffuse;
    vec3 Light1_Direction;
    float _pad1;
    vec4 Light1_Diffuse;
};
"""

STUB_LIGHT = """
// --- stub: light ---
const float MINECRAFT_LIGHT_POWER = 0.6;
const float MINECRAFT_AMBIENT_LIGHT = 0.4;
vec4 minecraft_mix_light(vec3 d0, vec4 c0, vec3 d1, vec4 c1,
                         vec4 normal, vec4 color) {
    float l0 = max(0.0, dot(normalize(d0), normalize(normal.xyz)));
    float l1 = max(0.0, dot(normalize(d1), normalize(normal.xyz)));
    vec3 lc = MINECRAFT_LIGHT_POWER * (c0.rgb * l0 + c1.rgb * l1) + MINECRAFT_AMBIENT_LIGHT;
    return vec4(color.rgb * lc, color.a);
}
// --- end stub ---
"""

STUB_LM2 = """
// --- stub: sample_lightmap2 ---
vec4 sample_lightmap2(ivec2 uv1, ivec2 uv2) { return vec4(1.0); }
// --- end stub ---
"""


def resolve_includes(src: str, visited=None) -> str:
    if visited is None:
        visited = set()

    def include_text(inc_name: str) -> str:
        inc_name = Path(inc_name).name
        if inc_name in visited:
            return f'// ALREADY_INCLUDED: {inc_name}'
        visited.add(inc_name)
        if inc_name in INCLUDES:
            inc_content = INCLUDES[inc_name]
        else:
            for fallback_name, fallback_text in INCLUDES.items():
                if fallback_name.endswith(inc_name) and fallback_name not in visited:
                    visited.add(fallback_name)
                    inc_content = fallback_text
                    break
            else:
                return f'// MISSING_INCLUDE: {inc_name}'
        inc_content = re.sub(r'#[ \t]*version.*\n', '', inc_content)
        inc_content = re.sub(r'#[ \t]*extension.*\n', '', inc_content)
        inc_content = re.sub(r'#[ \t]*pragma once.*\n', '', inc_content)
        return resolve_includes(inc_content, visited)

    return re.sub(
        r'#moj_import\s+[<\"]([^>\"]+)[>\"]|#include\s+"([^"]+)"',
        lambda m: include_text(m.group(1) or m.group(2)),
        src
    )


def patch(src: str) -> str:
    resolved = resolve_includes(src)
    lines = resolved.split('\n')
    in_out = []
    samplers = []
    body = []
    in_block = False
    depth = 0

    for line in lines:
        s = line.strip()
        if re.match(r'#[ \t]*version', s) or re.match(r'#[ \t]*extension', s) or re.match(r'#[ \t]*pragma once', s):
            continue
        if not in_block and re.search(r'layout\s*\(', s) and 'uniform' in s and '{' in s:
            in_block = True
            depth = s.count('{') - s.count('}')
            body.append(line)
            if depth <= 0:
                in_block = False
            continue
        if in_block:
            depth += s.count('{') - s.count('}')
            body.append(line)
            if depth <= 0:
                in_block = False
            continue
        if re.match(r'in\s+\w', s) and 'layout' not in s:
            in_out.append(('in', s))
            continue
        if re.match(r'out\s+\w', s) and 'layout' not in s:
            in_out.append(('out', s))
            continue
        m = re.match(r'uniform\s+(\S+)\s+(\w+)(\s*\[.*?\])?\s*;', s)
        if m and '{' not in s:
            utype = m.group(1) + (m.group(3) or '')
            uname = m.group(2)
            if any(utype.split('[')[0].rstrip().startswith(pref) for pref in (
                    'sampler', 'usampler', 'isampler', 'image', 'uimage',
                    'iimage', 'texture', 'utexture', 'itexture', 'subpassInput')):
                samplers.append((utype, uname))
                continue
        body.append(line)

    body_text = '\n'.join(body)
    out = ['#version 450', '']
    if any(fn in body_text for fn in ('linear_fog_value', 'fog_spherical_distance',
                                     'fog_cylindrical_distance', 'apply_fog')):
        out.append(STUB_FOG)
    has_mix_light_def = bool(re.search(r'\b(vec4|float|void)\s+minecraft_mix_light\s*\(', body_text))
    has_sample_lightmap2_def = bool(re.search(r'\bvec4\s+sample_lightmap2\s*\(', body_text))
    if 'minecraft_mix_light' in body_text and not has_mix_light_def:
        out.append(STUB_LIGHT_UNIFORMS)
        out.append(STUB_LIGHT)
    if 'sample_lightmap2' in body_text and not has_sample_lightmap2_def:
        out.append(STUB_LM2)

    for loc, (_, decl) in enumerate(in_out):
        out.append(f'layout(location = {loc}) {decl}')
    if in_out:
        out.append('')
    for idx, (utype, uname) in enumerate(samplers):
        out.append(f'layout(binding = {idx}) uniform {utype} {uname};')
    if samplers:
        out.append('')
    out.extend(body)
    return re.sub(r'\n{3,}', '\n\n', '\n'.join(out))


ext_map = {'.vsh': 'vert', '.fsh': 'frag', '.gsh': 'geom', '.csh': 'comp'}
shader_files = sorted([f for f in REPO_ROOT.glob('*') if f.suffix in ext_map and f.is_file()])
print(f'Found {len(shader_files)} root shader files to compile:')
for f in shader_files:
    print('  -', f.name)

ok = fail = 0
failures = []
for src_path in shader_files:
    stage = ext_map[src_path.suffix]
    patched = patch(src_path.read_text(errors='replace'))
    spv_path = SPV_OUT / (src_path.stem + '.' + stage + '.spv')
    debug_path = Path('/tmp') / f'{src_path.name}.debug.glsl'
    debug_path.write_text(patched)
    r = subprocess.run(
        ['glslc', f'-fshader-stage={stage}', '--target-env=vulkan1.1', '-', '-o', str(spv_path)],
        input=patched, capture_output=True, text=True
    )
    if r.returncode == 0:
        ok += 1
        print(f'  OK   {src_path.name}')
    else:
        fail += 1
        print(f'  FAIL {src_path.name}')
        print(r.stderr.strip())
        failures.append((src_path.name, r.stderr.strip()))

print(f'\nRESULT: {ok} OK / {fail} FAIL / {ok + fail} total')
if failures:
    print('\n=== FAILED SHADERS ===')
    for name, err in failures:
        print(f'-- {name} --')
        print(err)

# deploy valid SPV files
DEST1 = REPO_ROOT / 'src/main/resources/assets/vulkanmod/shaders/core'
DEST2 = REPO_ROOT / 'src/main/resources/assets/vulkanmod/shaders'
DEST1.mkdir(parents=True, exist_ok=True)
DEST2.mkdir(parents=True, exist_ok=True)
copy_count = skip_count = 0
for spv in sorted(SPV_OUT.glob('*.spv')):
    data = spv.read_bytes()
    if data[:4] not in (b'\x03\x02\x23\x07', b'\x07\x23\x02\x03'):
        print(f'SKIP bad SPIR-V: {spv.name}')
        skip_count += 1
        continue
    shutil.copy2(spv, DEST1 / spv.name.lower())
    shutil.copy2(spv, DEST2 / spv.name.lower())
    copy_count += 1
    print(f'DEPLOYED {spv.name}')

print(f'\nDeployed {copy_count} SPV files, skipped {skip_count}')
print('Destination 1:', DEST1)
print('Destination 2:', DEST2)

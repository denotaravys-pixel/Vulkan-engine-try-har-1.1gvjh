import re, subprocess
from pathlib import Path

REPO_ROOT = Path('/workspaces/Vulkan-engine-try-har-1.1gvjh')
SPV_OUT   = Path('/tmp/spv_out')
SPV_OUT.mkdir(parents=True, exist_ok=True)

# load includes
INCLUDES = {f.name: f.read_text(errors='replace')
            for f in REPO_ROOT.glob('*.glsl')}

def resolve_includes(src, visited=None):
    if visited is None: visited = set()
    def replacer(m):
        inc = Path(m.group(1)).name
        if inc in visited: return f'// ALREADY_INCLUDED: {inc}'
        visited.add(inc)
        for k,v in INCLUDES.items():
            if k == inc or k.endswith(inc):
                inc_content = v
                inc_content = re.sub(r'#[ \t]*version.*\n', '', inc_content)
                inc_content = re.sub(r'#[ \t]*extension.*\n', '', inc_content)
                inc_content = re.sub(r'#[ \t]*pragma once.*\n', '', inc_content)
                inc_content = re.sub(r'#[ \t]*define\s+MINECRAFT_LIGHT_POWER.*\n', '', inc_content)
                inc_content = re.sub(r'#[ \t]*define\s+MINECRAFT_AMBIENT_LIGHT.*\n', '', inc_content)
                return resolve_includes(inc_content, visited)
        return f'// MISSING_INCLUDE: {inc}'
    return re.sub(r'#moj_import\s+[<"]([^>"]+)[>"]', replacer, src)

OPAQUE_PREFIXES = ('sampler','usampler','isampler','image','uimage',
                   'iimage','texture','utexture','itexture','subpassInput')

def is_opaque(t):
    return any(t.split('[')[0].rstrip().startswith(p) for p in OPAQUE_PREFIXES)

# FIX A: constants come FIRST, then the stub that uses them
LIGHT_CONSTANTS = """
#ifndef MINECRAFT_LIGHT_POWER
#define MINECRAFT_LIGHT_POWER 0.6
#define MINECRAFT_AMBIENT_LIGHT 0.4
#endif
"""

STUB_LIGHT = """
// --- stub: light ---
vec4 minecraft_mix_light(vec3 d0, vec4 c0, vec3 d1, vec4 c1,
                         vec4 normal, vec4 color) {
    float l0 = max(0.0, dot(normalize(d0), normalize(normal.xyz)));
    float l1 = max(0.0, dot(normalize(d1), normalize(normal.xyz)));
    vec3 lc = MINECRAFT_LIGHT_POWER*(c0.rgb*l0 + c1.rgb*l1)
              + MINECRAFT_AMBIENT_LIGHT;
    return vec4(color.rgb * lc, color.a);
}
"""

STUB_LIGHT_UNIFORMS = """
layout(std140, binding = 10) uniform LightBlock {
    vec3 Light0_Direction; float _pad0;
    vec4 Light0_Diffuse;
    vec3 Light1_Direction; float _pad1;
    vec4 Light1_Diffuse;
};
"""

STUB_FOG = """
// --- stub: fog ---
float linear_fog_value(vec3 pos, float fogStart, float fogEnd) {
    return clamp((length(pos)-fogStart)/max(fogEnd-fogStart,0.001),0.0,1.0);
}
float fog_spherical_distance(mat4 mv, vec3 pos) {
    return length((mv*vec4(pos,1.0)).xyz);
}
float fog_cylindrical_distance(mat4 mv, vec3 pos) {
    return length((mv*vec4(pos,1.0)).xz);
}
vec4 apply_fog(vec4 color, float sd, float cd,
               float es, float ee, float rs, float re, vec4 fc) {
    float f = clamp((min(sd,cd)-es)/max(ee-es,0.001),0.0,1.0);
    return mix(color, fc, f*fc.a);
}
"""

STUB_LM2 = """
vec4 sample_lightmap2(ivec2 uv1, ivec2 uv2) { return vec4(1.0); }
"""

def patch(src):
    resolved = resolve_includes(src)
    resolved = resolved.replace('gl_VertexID', 'gl_VertexIndex')

    # FIX B: collect names already declared in existing layout blocks
    # so we don't duplicate them in VKUniforms
    already_declared = set()
    for m in re.finditer(r'layout\s*\([^)]*\)\s*uniform\s+\w*\s*\{([^}]*)\}',
                         resolved, re.DOTALL):
        block_body = m.group(1)
        for vm in re.finditer(r'\b(\w+)\s*;', block_body):
            already_declared.add(vm.group(1))

    # Also collect from stubs that will be added
    stub_uniforms = set()
    if 'minecraft_mix_light' in resolved:
        stub_uniforms.update(['Light0_Direction', 'Light0_Diffuse', 'Light1_Direction', 'Light1_Diffuse'])
    already_declared.update(stub_uniforms)

    lines = resolved.split('\n')
    in_out, samplers, ubo_members, body = [], [], [], []
    in_block = depth = 0

    for line in lines:
        s = line.strip()
        if re.match(r'#\s*version', s) or re.match(r'#\s*extension', s):
            continue

        # existing layout block — keep verbatim
        if not in_block and re.search(r'layout\s*\(', s) \
                        and 'uniform' in s and '{' in s:
            in_block = True
            depth = s.count('{') - s.count('}')
            body.append(line)
            if depth <= 0: in_block = False
            continue
        if in_block:
            depth += s.count('{') - s.count('}')
            body.append(line)
            if depth <= 0: in_block = False
            continue
        
        # FIX B: in/out that ALREADY have layout() but no location
        if re.search(r'layout\s*\(', s) and (re.search(r'\bin\s+', s) or re.search(r'\bout\s+', s)) \
                        and 'location' not in s and 'uniform' not in s:
            # strip the existing layout() and re-add with location
            s2 = re.sub(r'layout\s*\([^)]*\)\s*', '', s)
            kind = 'in' if re.search(r'\bin\s+', s2) else 'out'
            in_out.append((kind, s2)); continue
        
        # bare in / out (no layout at all)
        if re.search(r'\bin\s+', s) and 'layout' not in s:
            in_out.append(('in', s)); continue
        if re.search(r'\bout\s+', s) and 'layout' not in s:
            in_out.append(('out', s)); continue
        
        # bare uniform
        m = re.match(r'uniform\s+(\S+)\s+(\w+)(\s*\[.*?\])?\s*;', s)
        if m and '{' not in s:
            utype = m.group(1) + (m.group(3) or '')
            uname = m.group(2)
            if is_opaque(utype):
                samplers.append((utype, uname))
            elif uname not in already_declared:  # FIX A: skip duplicates
                ubo_members.append((utype, uname))
            continue
        body.append(line)
    
    body_text = '\n'.join(body)
    out = ['#version 450', '']
    
    # FIX A: always emit light constants first (harmless if not used)
    out.append(LIGHT_CONSTANTS)
    
    if any(fn in body_text for fn in
               ['linear_fog_value','fog_spherical_distance',
                'fog_cylindrical_distance','apply_fog']):
        out.append(STUB_FOG)
    if 'minecraft_mix_light' in body_text:
        out.append(STUB_LIGHT_UNIFORMS)
        out.append(STUB_LIGHT)
    if 'sample_lightmap2' in body_text:
        out.append(STUB_LM2)
    
    for loc, (_, decl) in enumerate(in_out):
        out.append(f'layout(location = {loc}) {decl}')
    if in_out: out.append('')
    
    offset = 1 if ubo_members else 0
    for idx, (utype, uname) in enumerate(samplers):
        out.append(f'layout(binding = {offset+idx}) uniform {utype} {uname};')
    if samplers: out.append('')
    
    if ubo_members:
        out.append('layout(std140, binding = 0) uniform VKUniforms {')
        for utype, uname in ubo_members:
            out.append(f'    {utype} {uname};')
        out.append('};')
        out.append('')
    
    out.extend(body)
    return re.sub(r'\n{3,}', '\n\n', '\n'.join(out))

ext_map = {'.vsh':'vert', '.fsh':'frag', '.gsh':'geom', '.csh':'comp'}
ok = fail = 0
failures = []

shader_files = [f for f in REPO_ROOT.glob('*') if f.suffix in ext_map]
for src_path in sorted(shader_files):
    stage = ext_map[src_path.suffix]
    patched = patch(src_path.read_text(errors='replace'))
    Path(f'/tmp/{src_path.name}.debug.glsl').write_text(patched)
    spv_path = SPV_OUT / (src_path.stem + '.' + stage + '.spv')
    
    r = subprocess.run(
        ['glslc', f'-fshader-stage={stage}', '--target-env=vulkan1.1',
         '-', '-o', str(spv_path)],
        input=patched, capture_output=True, text=True
    )
    if r.returncode == 0:
        ok += 1; print(f'  OK  {src_path.name}')
    else:
        fail += 1
        failures.append((src_path.name, r.stderr.strip()))
        print(f' FAIL {src_path.name}')
        for l in r.stderr.strip().split('\n')[:4]:
            print(f'       {l}')

print(f'\nRESULT: {ok} OK  /  {fail} FAIL  /  {ok+fail} total')

if failures:
    print('\n=== STILL FAILING — FULL DETAILS ===')
    for name, err in failures:
        print(f'\n--- {name} ---')
        print(err[:600])
        dbg = Path(f'/tmp/{name}.debug.glsl')
        if dbg.exists():
            lines = dbg.read_text().split('\n')
            print('\n'.join(f'{i+1:3}: {l}' for i,l in enumerate(lines[:60])))
import re, os, subprocess
from pathlib import Path

PREPROCESSED = Path('/tmp/preprocessed_shaders')
SPV_OUT      = Path('/tmp/spv_compiled')
SPV_OUT.mkdir(parents=True, exist_ok=True)

OPAQUE_PREFIXES = ('sampler','usampler','isampler','image','uimage','iimage',
                   'texture','utexture','itexture','subpassInput')

def is_opaque(t):
    return any(t.split('[')[0].rstrip().startswith(p) for p in OPAQUE_PREFIXES)
    
def patch(src):
    in_out, samplers, ubo_members, body = [], [], [], []
    in_block = depth = 0
    
    for line in src.split('\n'):
        s = line.strip()
        if re.match(r'#\s*version', s) or re.match(r'#\s*extension', s):
            continue
        if not in_block and re.search(r'layout\s*\(', s) and 'uniform' in s and '{' in s:
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
        if re.match(r'in\s+\w', s) and 'layout' not in s:
            in_out.append(('in', s)); continue
        if re.match(r'out\s+\w', s) and 'layout' not in s:
            in_out.append(('out', s)); continue
        m = re.match(r'uniform\s+(\S+)\s+(\w+)(\s*\[.*?\])?\s*;', s)
        if m and '{' not in s:
            utype = m.group(1) + (m.group(3) or '')
            uname = m.group(2)
            if is_opaque(utype):
                samplers.append((utype, uname))
            else:
                ubo_members.append((utype, uname))
            continue
        body.append(line)
        
    out = ['#version 450', '']
    for loc, (kind, decl) in enumerate(in_out):
        out.append(f'layout(location = {loc}) {decl}')
    if in_out: out.append('')
    offset = 1 if ubo_members else 0
    for idx, (utype, uname) in enumerate(samplers):
        out.append(f'layout(binding = {offset + idx}) uniform {utype} {uname};')
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

for src_path in sorted(PREPROCESSED.glob('*')):
    stage = ext_map.get(src_path.suffix)
    if not stage: continue
    patched = patch(src_path.read_text())
    spv_path = SPV_OUT / (src_path.stem + '.' + stage + '.spv')
    r = subprocess.run(
        ['glslc', f'-fshader-stage={stage}', '--target-env=vulkan1.1', '-', '-o', str(spv_path)],
        input=patched, capture_output=True, text=True
    )
    if r.returncode == 0:
        ok += 1; print(f'  OK  {src_path.name}')
    else:
        fail += 1; failures.append((src_path.name, r.stderr.strip()))
        print(f' FAIL {src_path.name}')
        for l in r.stderr.strip().split('\n')[:3]: print(f'       {l}')
        # Save debug
        debug_path = PREPROCESSED / (src_path.name + '.debug.glsl')
        with open(src_path, 'r') as f:
            lines = f.readlines()[:40]
        with open(debug_path, 'w') as f:
            f.writelines(lines)

print(f'\nRESULT: {ok} OK  /  {fail} FAIL  /  {ok+fail} total')
if failures:
    print('\n=== FAILED SHADERS ===')
    for n, e in failures:
        print(f'  {n}:\n    {e[:200]}\n')
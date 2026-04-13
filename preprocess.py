import os, re
from pathlib import Path

SHADER_ROOT = Path('src/main/resources/assets/minecraft/shaders')
INCLUDE_DIR = Path('src/main/resources/assets/vulkanmod/shaders/include')
CORE_DIR    = SHADER_ROOT / 'core'
VULKAN_DIR  = Path('src/main/resources/assets/vulkanmod/shaders')
OUT_DIR     = Path('/tmp/preprocessed_shaders')
OUT_DIR.mkdir(parents=True, exist_ok=True)

def resolve_includes(src, include_dir, visited=None):
    if visited is None:
        visited = set()
    def replacer(m):
        inc_name = m.group(1)
        print(f"Replacing {inc_name}")
        inc_path = include_dir / inc_name
        if inc_path.exists() and str(inc_path) not in visited:
            visited.add(str(inc_path))
            inc_content = inc_path.read_text()
            # Remove #version and #extension lines from includes
            inc_content = re.sub(r'#\s*version\s+.*\n', '', inc_content)
            inc_content = re.sub(r'#\s*extension\s+.*\n', '', inc_content)
            return "// REPLACED " + inc_name
        # try without subpath
        inc_path = include_dir / Path(inc_name).name
        if inc_path.exists() and str(inc_path) not in visited:
            visited.add(str(inc_path))
            inc_content = inc_path.read_text()
            # Remove #version and #extension lines from includes
            inc_content = re.sub(r'#\s*version\s+.*\n', '', inc_content)
            inc_content = re.sub(r'#\s*extension\s+.*\n', '', inc_content)
            return "// REPLACED " + inc_name
        return f'// MISSING INCLUDE: {inc_name}'
    return re.sub(r'#include\s+"([^"]+)"', replacer, src)

count = 0
for shader_dir in [CORE_DIR, VULKAN_DIR]:
    if not shader_dir.exists():
        continue
    for f in shader_dir.glob('**/*'):
        if f.suffix in ('.vsh', '.fsh', '.gsh'):
            src = f.read_text(errors='replace')
            resolved = resolve_includes(src, INCLUDE_DIR)
            print(f"Resolved start: {resolved[:200]}")
            out = OUT_DIR / f.name
            out.write_text(resolved)
            count += 1

print(f"Preprocessed {count} shaders → {OUT_DIR}")
import subprocess
result = subprocess.run(['ls', str(OUT_DIR)], capture_output=True, text=True)
print(result.stdout)
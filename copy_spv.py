import shutil
from pathlib import Path

SPV_SRC  = Path('/tmp/spv_compiled')
# VulkanMod Android resource pack destination (lowercase paths — Fabric requirement)
SPV_DEST = Path('src/main/resources/assets/vulkanmod/shaders/core')
SPV_DEST.mkdir(parents=True, exist_ok=True)

copied = 0
for spv in SPV_SRC.glob('*.spv'):
    # Validate it's a real SPIR-V binary (magic = 0x07230203)
    data = spv.read_bytes()
    if len(data) < 20 or data[:4] not in (b'\x03\x02\x23\x07', b'\x07\x23\x02\x03'):
        print(f'  SKIP (invalid SPIR-V): {spv.name}')
        continue
    dest = SPV_DEST / spv.name.lower()
    shutil.copy2(spv, dest)
    copied += 1
    print(f'  COPIED → {dest}')

print(f'\nTotal SPV files deployed: {copied}')
print(f'Destination: {SPV_DEST.resolve()}')
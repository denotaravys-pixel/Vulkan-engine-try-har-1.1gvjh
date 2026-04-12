#!/usr/bin/env python3
import os
import sys

root = os.path.dirname(os.path.abspath(__file__))
shaders = []
for dirpath, _, filenames in os.walk(os.path.join(root, 'src', 'main', 'resources')):
    for filename in filenames:
        if filename.endswith('.spv'):
            shaders.append(os.path.join(dirpath, filename))

if not shaders:
    print('❌ No SPIR-V files found under src/main/resources')
    sys.exit(1)

failed = False
for path in sorted(shaders):
    try:
        with open(path, 'rb') as f:
            data = f.read(4)
            if len(data) < 4:
                print(f'❌ {path}: too small')
                failed = True
                continue
            magic = int.from_bytes(data, 'little')
            if magic != 0x07230203:
                print(f'❌ {path}: invalid SPIR-V magic 0x{magic:08X}')
                failed = True
            else:
                print(f'✅ {path}')
    except Exception as e:
        print(f'❌ {path}: {e}')
        failed = True

if failed:
    sys.exit(1)
print(f'✅ Validated {len(shaders)} SPIR-V files')

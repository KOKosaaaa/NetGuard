"""Pack offline installers inside the APK. The app decodes them before SSH/upload."""
from pathlib import Path
import hashlib
import lzma

assets = Path(__file__).resolve().parent.parent / 'app/src/main/assets/agent'
for arch in ('amd64', 'arm64'):
    path = assets / f'netguard-agent-{arch}'
    raw = path.read_bytes()
    if raw.startswith(b'\xfd7zXZ\x00'):
        raw = lzma.decompress(raw)
    if not raw.startswith(b'\x7fELF') or len(raw) > 64 * 1024 * 1024:
        raise ValueError(f'Invalid installer: {arch}')
    packed = lzma.compress(raw, preset=6)  # 8 MiB decoder dictionary, within app's memory limit.
    assert lzma.decompress(packed) == raw
    path.write_bytes(packed)
    print(arch, 'raw:', len(raw), 'packed:', len(packed), 'sha256:', hashlib.sha256(raw).hexdigest())

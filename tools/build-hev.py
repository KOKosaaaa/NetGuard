"""Rebuild the Android TUN library with authenticated per-connection metadata.

Requires Git and Android NDK 27.2.12479018. No native binary editing.
"""
import os
import pathlib
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'third_party/hev-socks5-tunnel'
REVISION = 'e802f02bae0fc55cbf681466a60e89c2e6773401'
GIT = shutil.which('git') or 'C:/Program Files/Git/cmd/git.exe'


def git(*args, cwd=SOURCE, check=True):
    return subprocess.run([GIT, *args], cwd=cwd, check=check, capture_output=True, text=True)


if not SOURCE.exists():
    SOURCE.parent.mkdir(parents=True, exist_ok=True)
    git('clone', 'https://github.com/heiher/hev-socks5-tunnel.git', str(SOURCE), cwd=ROOT)
    git('checkout', REVISION)
    git('submodule', 'update', '--init', '--recursive')
if git('rev-parse', 'HEAD').stdout.strip() != REVISION:
    raise SystemExit('Unexpected HEV source revision; use a separate clean checkout')

patch = str(ROOT / 'tools/native/hev-app-origin.patch')
if git('apply', '--reverse', '--check', patch, check=False).returncode:
    git('apply', '--check', patch)
    git('apply', patch)

# Windows Git may check out symlinks as text. Materialize only the declared
# include links, resolving inside this pinned source tree. Idempotent.
repos = [SOURCE] + [SOURCE / p for p in ('src/core', 'third-part/hev-task-system', 'third-part/lwip', 'third-part/yaml')]
for repo in repos:
    for line in git('ls-files', '--stage', cwd=repo).stdout.splitlines():
        if not line.startswith('120000 '):
            continue
        rel = line.split('\t', 1)[1]
        path = repo / rel
        if path.is_symlink():
            continue
        link = git('show', 'HEAD:' + rel, cwd=repo).stdout.strip()
        target = (path.parent / link).resolve()
        if not target.is_relative_to(SOURCE) or not target.is_file():
            raise SystemExit('Unexpected native include link')
        path.write_bytes(target.read_bytes())

sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
ndk = pathlib.Path(os.environ.get('ANDROID_NDK_HOME') or (str(pathlib.Path(sdk) / 'ndk/27.2.12479018') if sdk else ''))
build = ndk / ('ndk-build.cmd' if os.name == 'nt' else 'ndk-build')
if not build.is_file():
    raise SystemExit('Set ANDROID_NDK_HOME or ANDROID_SDK_ROOT')
subprocess.run([str(build), 'NDK_PROJECT_PATH=.', 'APP_BUILD_SCRIPT=Android.mk',
    'NDK_APPLICATION_MK=Application.mk', 'APP_ABI=arm64-v8a', 'APP_PLATFORM=android-26',
    'APP_CFLAGS=-O3 -DPKGNAME=hev/sockstun -DCLSNAME=TProxyService', '-j4'], cwd=SOURCE, check=True)
shutil.copyfile(SOURCE / 'libs/arm64-v8a/libhev-socks5-tunnel.so',
    ROOT / 'app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so')

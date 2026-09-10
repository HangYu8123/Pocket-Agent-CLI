#!/usr/bin/env python3
"""
Fetch the Android proot build from the Termux package repository and stage it into
app/src/main/jniLibs/<abi>/ so Android extracts it into the app's nativeLibraryDir
(the only app-owned location that may hold executables on Android 10+).

Files produced per ABI:
  libproot.so           proot executable (Termux fork with Android patches)
  libproot_loader.so    proot's 64-bit loader (PROOT_LOADER)
  libproot_loader32.so  proot's 32-bit loader (PROOT_LOADER_32)
  libtalloc.so          talloc (DT_NEEDED of proot, patched name)
  libandroid-shmem.so   SysV shm emulation used by proot --sysvipc

Android only extracts files named lib*.so, so `libtalloc.so.2` is renamed to
`libtalloc.so` and proot's DT_NEEDED string is patched in place to match.

Usage: fetch_native.py [aarch64] [x86_64]   (default: both)
"""
import io, os, re, sys, tarfile, urllib.request, shutil

REPO = "https://packages.termux.dev/apt/termux-main"
PKGS = ["proot", "libtalloc", "libandroid-shmem"]
ABIS = {"aarch64": "arm64-v8a", "x86_64": "x86_64"}
HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(HERE, "_work")
JNI = os.path.join(HERE, "..", "app", "src", "main", "jniLibs")

def ar_members(data):
    assert data[:8] == b"!<arch>\n"
    off = 8
    while off < len(data):
        name = data[off:off+16].decode().strip()
        size = int(data[off+48:off+58]); off += 60
        yield name, data[off:off+size]
        off += size + (size % 2)

def fetch(url):
    print("GET", url)
    try:
        with urllib.request.urlopen(url) as r:
            return r.read()
    except Exception:
        # Some Python installs (macOS) lack CA certificates; fall back to curl.
        import subprocess
        return subprocess.check_output(["curl", "-fsSL", url])

def patch(blob, old, new):
    assert len(new) <= len(old)
    new = new + b"\0" * (len(old) - len(new))
    n = blob.count(old)
    assert n >= 1, f"pattern {old!r} not found"
    print(f"  patched {n}x {old!r} -> {new!r}")
    return blob.replace(old, new)

def stage(arch):
    abi = ABIS[arch]
    out_dir = os.path.join(JNI, abi)
    os.makedirs(out_dir, exist_ok=True)
    index = fetch(f"{REPO}/dists/stable/main/binary-{arch}/Packages").decode()
    files = {}
    for pkg in PKGS:
        m = re.search(rf"^Package: {re.escape(pkg)}\n(?:.+\n)*?Filename: (\S+)", index, re.M)
        if not m: sys.exit(f"package {pkg} not found in {arch} index")
        files[pkg] = m.group(1)
    tree = os.path.join(WORK, arch); shutil.rmtree(tree, ignore_errors=True)
    for pkg, fn in files.items():
        deb = fetch(f"{REPO}/{fn}")
        for name, blob in ar_members(deb):
            if name.startswith("data.tar"):
                tarfile.open(fileobj=io.BytesIO(blob), mode="r:*").extractall(tree)
    usr = os.path.join(tree, "data/data/com.termux/files/usr")
    def rd(p): return open(os.path.join(usr, p), "rb").read()

    proot = patch(rd("bin/proot"), b"libtalloc.so.2\0", b"libtalloc.so\0")
    talloc_real = os.path.realpath(os.path.join(usr, "lib/libtalloc.so.2"))
    talloc = patch(open(talloc_real, "rb").read(), b"libtalloc.so.2\0", b"libtalloc.so\0")
    outputs = {
        "libproot.so": proot,
        "libproot_loader.so": rd("libexec/proot/loader"),
        "libproot_loader32.so": rd("libexec/proot/loader32"),
        "libtalloc.so": talloc,
        "libandroid-shmem.so": rd("lib/libandroid-shmem.so"),
    }
    for name, blob in outputs.items():
        p = os.path.join(out_dir, name)
        open(p, "wb").write(blob); os.chmod(p, 0o755)
        print(f"wrote {p} ({len(blob)} bytes)")
    return files

def main():
    os.makedirs(WORK, exist_ok=True)
    arches = sys.argv[1:] or list(ABIS)
    with open(os.path.join(HERE, "NATIVE_VERSIONS.txt"), "a") as f:
        for arch in arches:
            for pkg, fn in stage(arch).items(): f.write(f"{arch} {pkg}: {fn}\n")

if __name__ == "__main__":
    main()

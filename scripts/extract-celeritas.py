#!/usr/bin/env python3
"""
Builds libs/celeritas-lwjgl2-java8.jar out of an Angelica release jar.

Why extract from Angelica instead of consuming celeritas-common from Maven:

  * The published celeritas-common is Java 17 bytecode and is compiled against LWJGL 3
    (GL20C/GL32C/GL43C, org.lwjgl.system.MemoryUtil). Minecraft 1.8.9 runs on Java 8 and
    LWJGL 2.9.x, which has none of those classes.
  * Angelica ships a multi-release jar. Its root entries are the same renderer already
    downgraded to class version 52 *and* built against LWJGL 2 - it references
    ContextCapabilities, KHRDebugCallback, ARBDebugOutputCallback and friends.
    That is exactly the combination 1.8.9 needs, so the root entries are taken verbatim
    and META-INF/versions/* (the Java 17 originals) are dropped.

The only LWJGL 3 users left among the root classes are com.mitchej123.lwjgl.lwjgl3.*, which
is the optional ServiceLoader backend. It is excluded here and only LWJGL2Service is
registered, so nothing ever tries to link an LWJGL 3 class.

The jvmdowngrader-java-api jar has to be downgraded before it is passed in, because its stubs
are compiled at the version they emulate (J_L_Record is class version 59, J_U_List is 53):

    java -jar jvmdowngrader-1.3.6-all.jar -c 52 \\
        downgrade --target jvmdowngrader-java-api-1.3.6.jar jvmdg-api-j8.jar
"""

import os
import sys
import zipfile

INCLUDE_PREFIXES = (
    "org/embeddedt/",          # Celeritas / Embeddium renderer
    "org/taumc/celeritas/",    # Celeritas public API
    "com/mitchej123/lwjgl/",   # LWJGL abstraction
    "com/mitchej123/glsm/",    # GL state manager abstraction
    "assets/sodium/shaders/",  # chunk shader sources used by DefaultChunkRenderer
)

# Angelica's Celeritas build reaches into GTNHLib for off-heap memory access - it is what
# LWJGL2MemoryStack is implemented on top of, since LWJGL 2 has no org.lwjgl.system.MemoryUtil.
# The bytebuf package is plain Java with no Minecraft ties, so it comes along wholesale
# rather than dragging in all of GTNHLib (which is built for 1.7.10).
GTNHLIB_PREFIXES = (
    "com/gtnewhorizon/gtnhlib/bytebuf/",
)

# Angelica's classes were downgraded from Java 17 by JvmDowngrader, so calls to Java 9+ APIs
# (ServiceLoader.stream, List.of, records, ...) were rewritten to point at jvmdg's runtime stubs.
# GTNH ships those stubs separately - GTNHLib does not carry them - so they come from
# jvmdowngrader-java-api here. That jar is multi-target and holds classes up to version 68, which
# 1.8.9's ASM 5.0.3 mod scanner cannot read, so only the <=52 ones are taken.
JVMDG_PREFIX = "xyz/wagyourtail/jvmdg/"

# LWJGL 3 backend: the one part of the root entries still bound to LWJGL 3.
EXCLUDE_PREFIXES = (
    "com/mitchej123/lwjgl/lwjgl3/",
)

SERVICES = {
    "META-INF/services/com.mitchej123.lwjgl.LWJGLService":
        "com.mitchej123.lwjgl.lwjgl2.LWJGL2Service\n",
    "META-INF/services/com.mitchej123.glsm.GLStateManagerService":
        "com.mitchej123.glsm.impl.PassThroughGLStateManager\n",
    "META-INF/services/com.mitchej123.glsm.RenderSystemService":
        "com.mitchej123.glsm.impl.PassThroughRenderSystem\n",
}


def wanted(name, prefixes):
    # META-INF/versions holds the Java 17 originals; the root entries are the downgraded ones.
    if name.startswith("META-INF/versions/"):
        return False
    if any(name.startswith(p) for p in EXCLUDE_PREFIXES):
        return False
    return any(name.startswith(p) for p in prefixes)


def copy_matching(src_path, prefixes, out, seen, bad_version):
    copied = 0
    with zipfile.ZipFile(src_path) as src:
        for info in src.infolist():
            name = info.filename
            if info.is_dir() or name in seen or not wanted(name, prefixes):
                continue
            data = src.read(name)
            if name.endswith(".class"):
                major = int.from_bytes(data[6:8], "big")
                if major > 52:
                    bad_version.append((name, major))
            out.writestr(name, data)
            seen.add(name)
            copied += 1
    return copied


def copy_jvmdg_runtime(jvmdg_api, out, seen):
    """Copies every jvmdg runtime class that targets Java 8 or lower."""
    copied = skipped = 0
    with zipfile.ZipFile(jvmdg_api) as src:
        for info in src.infolist():
            name = info.filename
            if info.is_dir() or name in seen or not name.startswith(JVMDG_PREFIX):
                continue
            if not name.endswith(".class"):
                continue
            data = src.read(name)
            if int.from_bytes(data[6:8], "big") > 52:
                skipped += 1
                continue
            out.writestr(name, data)
            seen.add(name)
            copied += 1
    return copied, skipped


def main():
    if len(sys.argv) != 5:
        sys.exit("usage: extract-celeritas.py <angelica.jar> <gtnhlib.jar> "
                 "<jvmdowngrader-java-api.jar> <output.jar>")

    angelica, gtnhlib, jvmdg_api, out_path = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    for p in (angelica, gtnhlib, jvmdg_api):
        if not os.path.isfile(p):
            sys.exit("no such file: " + p)

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)

    seen = set()
    bad_version = []

    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as out:
        n_cel = copy_matching(angelica, INCLUDE_PREFIXES, out, seen, bad_version)
        n_gtn = copy_matching(gtnhlib, GTNHLIB_PREFIXES, out, seen, bad_version)
        n_jvm, n_skip = copy_jvmdg_runtime(jvmdg_api, out, seen)
        for name, body in SERVICES.items():
            out.writestr(name, body)

    if bad_version:
        print("ERROR: %d classes are newer than Java 8:" % len(bad_version))
        for name, major in bad_version[:10]:
            print("   %s (major %d)" % (name, major))
        os.remove(out_path)
        sys.exit(1)

    size = os.path.getsize(out_path)
    print("wrote %s" % out_path)
    print("  celeritas entries: %d" % n_cel)
    print("  gtnhlib bytebuf entries: %d" % n_gtn)
    print("  jvmdg runtime entries: %d (skipped %d newer than Java 8)" % (n_jvm, n_skip))
    print("  services: %d" % len(SERVICES))
    print("  %.1f KiB, all class version <= 52" % (size / 1024.0))


if __name__ == "__main__":
    main()

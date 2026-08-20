Bundled third-party components
What's included in the code inside the distributed jar, each item's original license and where it comes from.

Celeritas — LGPL-3.0
Packages: org.embeddedt.embeddium.*, org.taumc.celeritas.*, com.mitchej123.lwjgl.*, com.mitchej123.glsm.*, assets/sodium/shaders/*

Source: https://github.com/GTNewHorizons/Celeritas (mirror) / https://git.taumc.org/embeddedt/celeritas
Celeritas is a fork of Embeddium, which itself is based on the last FOSS-licensed version of Sodium (CaffeineMC) and on Oculus 1.7.
Full license text: this repository's COPYING (GPLv3) and COPYING.LESSER (LGPLv3)
How it was obtained: The Maven celeritas-common artifact targets Java 17 bytecode and LWJGL 3, which doesn't run on 1.8.9 (Java 8 + LWJGL 2). This instead uses the root entries from an Angelica release jar — the same code, already downgraded to class version 52 and built against LWJGL 2. The extraction process lives in scripts/extract-celeritas.py and is reproducible.

Changes: The code itself was not modified. One runtime behavior is changed via a mixin — GLRenderDevice$ImmediateCommandList.copyBufferSubData is @Overwriten to fall back to a GL 1.5 round trip when the driver doesn't provide glCopyBufferSubData (GL 3.1 / ARB_copy_buffer). Drivers that do support it still take the original GPU path. (src/celeritas/java/me/natron/mixin/celeritas/MixinImmediateCommandList.java)

Angelica — LGPL-3.0
Source: https://github.com/GTNewHorizons/Angelica
None of Angelica's code is included directly. However, this project's bridge layer (src/celeritas/java/me/natron/render/*) ports Angelica's com.gtnewhorizons.angelica.rendering.celeritas package to 1.8.9's API, following its class structure and how it wires into Celeritas closely enough to reasonably be considered a derivative work — which is why this project is also LGPL-3.0.

The correspondence is noted in each source file's comments (e.g. NatronFogService ↔ AngelicaFogService).

GTNHLib (bytebuf portion) — LGPL-3.0
Package: com.gtnewhorizon.gtnhlib.bytebuf.* (19 classes)

Source: https://github.com/GTNewHorizons/GTNHLib
LWJGL 2 has no org.lwjgl.system.MemoryUtil, so Celeritas uses this for off-heap access instead. It's plain Java with no Minecraft dependency, so only that package was pulled in. Unmodified.

JvmDowngrader runtime — LGPL-2.1
Package: xyz.wagyourtail.jvmdg.* (954 classes)

Source: https://github.com/unimined/JvmDowngrader
Available under LGPLv2.1 or a commercial license; non-commercial use follows LGPLv2.1.
The classes Angelica distributes were converted Java 17 → 8 by JvmDowngrader, so calls to ServiceLoader.stream(), List.of(), and records have been rewritten as calls into jvmdg stubs. GTNH distributes those stubs separately, so jvmdowngrader-java-api is bundled here. (The stubs are compiled to the version they emulate, so they're downgraded to class version 52 first before being bundled.) Unmodified.

JOML — MIT
Package: org.joml.*

Source: https://github.com/JOML-CI/JOML
Used by Celeritas for matrix/frustum math. Only module-info.class was removed — 1.8.9 FML scans every jar with ASM 5.0.3, and a Java 9 module-info makes it ignore the whole jar outright. No other changes.

fastutil — Apache-2.0
Package: it.unimi.dsi.fastutil.*

Source: https://github.com/vigna/fastutil
Celeritas's default collections. Unmodified.

SpongePowered Mixin — MIT
Package: org.spongepowered.asm.*

Source: https://github.com/SpongePowered/Mixin
Version 0.7.11 (the last release line compatible with 1.8.9's LaunchWrapper + ASM 5)
Unmodified.

License summary
Among the bundled components, Celeritas, Angelica (derivative), and GTNHLib are LGPL-3.0. They aren't linked in as separate libraries — they ship together inside one jar, and the bridge layer is itself a derivative work of them, so this project as a whole is distributed under LGPL-3.0.

JvmDowngrader's LGPL-2.1 can be distributed alongside LGPL-3.0 (LGPLv2.1 §3's later-version clause). The MIT / Apache-2.0 items have no constraints that would affect an LGPL-3.0 distribution.

What distribution requires:

Full source availability (this repository)
Bundling COPYING and COPYING.LESSER — both inside the jar and in the repository
Documenting changes — see each component's "Changes" section above
Attribution to original authors — this file and the README
This document summarizes what each license file itself says and is not legal advice. For commercial distribution or anything with dispute potential, verify separately.

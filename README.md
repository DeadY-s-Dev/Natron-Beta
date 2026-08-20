<img src="assets/logo.png" alt="Natron logo" width="96" height="96">

# Natron

A terrain rendering optimization mod for Minecraft 1.8.9 Forge.

An unofficial port of [Angelica](https://github.com/GTNewHorizons/Angelica) and its
renderer, **[Celeritas](https://github.com/GTNewHorizons/Celeritas)**, to 1.8.9.
Celeritas is a fork of Embeddium, which itself split off the last FOSS-licensed
version of Sodium (CaffeineMC).

**Discord:** https://discord.gg/bvDjwkVqV

## License

**LGPL-3.0.** See [THIRD-PARTY.md](THIRD-PARTY.md) for bundled components and where
each one comes from.

## Downloads

**Once Modrinth distribution is up and running, it becomes the only place this ships
from.** GitHub Releases will stop getting updates from that point on — get the latest
build from Modrinth instead.

This repository keeps being maintained after that as the source mirror LGPL-3.0
requires. What that license obligates is the source, not a binary, and source is also
what anyone building it themselves or checking what changed actually needs.

## Requirements

- Minecraft **1.8.9**
- Forge **11.15.1.2318** (other 1.8.9 Forge builds should mostly work too)
- Java **8**

Drop the single jar into `mods/`. It's self-contained, nothing else to fetch.
Client-side only — don't put it on a server.

## Performance

Measured (low-spec machine, multiplayer):

| | FPS |
|---|---|
| OptiFine | 60 ~ 80 |
| Natron | **~250** |

Varies by machine and situation. This number comes from one machine — see
"Verification scope" below.

## Using it alongside OptiFine

Angelica documents OptiFine as a **permanent incompatibility**. The cause is that
1.7.10's rendering shares one global `Tessellator`, and when OptiFine and Angelica each
patch that same class independently, the JVM dies at link time with an
`IncompatibleClassChangeError`.

**That structure doesn't exist in 1.8.9.** Mojang refactored rendering after 1.7.10 into
a per-call `WorldRenderer` + `BlockRendererDispatcher`, and this port's mesher already
builds on top of that. So the specific conflict that kills Angelica does not exist on
1.8.9.

Confirmed combinations:

| Combination | Status |
|---|---|
| Launcher-bundled OptiFine (Lunar, Badlion, etc.) | **Works** |
| Live shader pack toggling via OptiFine + `F3+A` | **Works** |
| OptiFine dropped directly into `mods/` | **Entities don't render** |

### Known issue: entities don't render when OptiFine is installed as a mod

When OptiFine is loaded as a standalone Forge mod, its `OptiFineClassTransformer`
patches vanilla classes directly at the LaunchWrapper stage, including
`GlStateManager`. Launcher-bundled OptiFine doesn't go through this path, which is why
it isn't affected.

This port has Celeritas touch GL state directly and then restore vanilla's state with
`glPushAttrib`/`glPopAttrib`, and the set of state it restores was decided **based on
the fields vanilla's `GlStateManager` itself caches**. If OptiFine swaps that class out
for its own version, that premise no longer holds — this is the most likely
explanation right now, but it isn't confirmed.

**For the time being, use launcher-bundled OptiFine, or don't use OptiFine at all.**

## What it does

Celeritas takes over terrain rendering from vanilla. It intercepts and cancels
`RenderGlobal`'s `setupTerrain`, `updateChunks`, `renderBlockLayer`, and similar
methods, and routes the work through the Celeritas pipeline instead.

What Celeritas brings:

- Region-batched buffer arenas and multidraw batching
- Occlusion graph culling (runs asynchronously, off the render thread)
- Asynchronous, multithreaded chunk meshing
- A compact vertex format, translucency sorting

The geometry itself still comes from vanilla's `BlockRendererDispatcher`. Angelica had
to write its own mesher because 1.7.10 has no data-driven model system, but 1.8.9 has
baked models, so this port lets vanilla build the geometry and transcodes the result
into Celeritas's vertex format (`VanillaBufferTranscoder`). Ambient occlusion, smooth
lighting, biome tint, and Forge's model extensions all go through the unmodified
vanilla path, so modded blocks render correctly too.

## Verification scope

Stated plainly: this port has been tested by **one person, on one machine.**

Confirmed:

- Coexists with launcher-bundled OptiFine (Lunar), including live shader pack toggling
  followed by `F3+A`
- ~3x OptiFine's FPS on a low-spec machine
- Block placement/breaking, chunk streaming, lighting

Not confirmed:

- Other GPU vendors / drivers (testing has only covered one machine)
- The Nether, the End
- Environments with lots of modded blocks
- macOS — if the GPU only offers an OpenGL 2.1 context, Celeritas's shaders (GLSL 330)
  fail to compile. When GPU support falls short, it automatically falls back to the
  vanilla renderer and logs why.

## Diagnostics

On startup it writes `natron-diagnostics.log` to the game directory: GL capabilities,
which upload path got selected, and whether the renderer is actually active. Attach
this file when reporting an issue — it helps.

## Building

**The Gradle daemon needs to run on JDK 21.** Gradle 8.x can't compile the build script
under JDK 25. Compilation itself still targets JDK 8 via the toolchain.

```bash
export JAVA_HOME=/path/to/jdk-21

./gradlew build -PincludeCeleritas=true
```

Output lands at `build/libs/natron-1.8.9-<version>.jar`.

Building without `-PincludeCeleritas` produces an empty shell with no renderer — that's
only for checking the non-Celeritas half compiles during development, not something to
actually use.

Warnings about `Lists.newArrayList()` are safe to ignore (it's a Guava class with no
obfuscation mapping).

The two jars under `libs/` are required to build. Celeritas's Maven artifact targets
Java 17 + LWJGL 3, which doesn't run on 1.8.9, so this instead extracts the root entries
from an Angelica release jar (already downgraded to Java 8 + LWJGL 2). The extraction
process lives in `scripts/extract-celeritas.py` and is reproducible. JOML is the same
jar with only its `module-info` stripped, since 1.8.9 FML's ASM 5.0.3 ignores an entire
jar outright the moment it hits a Java 9 module-info.

## See also

- [THIRD-PARTY.md](THIRD-PARTY.md) — bundled components, licenses, provenance, changes

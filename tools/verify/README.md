# Verification

Repeatable checks for the transformation wiring. Nothing here is part of the build or the shipped
jar; run it with:

    sh tools/verify/run.sh

| check | what it pins down |
|---|---|
| `Check` | `LALTransformer.injectHeadVoid` pushes exactly the operands the hook descriptor declares (the pre-fix version produced a `VerifyError` on `setDirty(boolean)`), and definition hooks are not injected into a non-vanilla owner. Includes a control that fails if the defect returns. |
| `RuntimeCheck` | `Runtime.halt` is native and cannot carry a `Code` attribute, so the exit guard must skip it; the guard must still be injected into `Runtime.exit` and the rewritten class must verify. Includes a control for the native case. |
| `TargetCheck` | `LALTransformationService.transformers()` exposes the class transformer, every enumerated target name resolves, and the wired path produces a verifiable class. ModLauncher matches targets by exact name, so a typo here silently drops a hook. |
| `RealClassCheck` | Runs the wired transformer over the **real** Minecraft class files in the target set and re-defines each one, so the verifier passes judgement on the actual bytecode and hierarchy. This is what an in-game `VerifyError` would come from. Must report 19/19. |
| `DiscoveryCheck` | Reads the built jar through Forge's own `SecureJar` and prints `ModuleDescriptor.provides()`. |
| `DiscoveryCheck2` | Runs Forge's real `ModDirTransformerDiscoverer` against a game directory with the built jar in `mods/`. This is the code path a normal install takes. |

`DiscoveryCheck2` must print `RESULT: PASS`. If it fails, the mod is not discoverable from a plain
install and the ASM hook table will not run.

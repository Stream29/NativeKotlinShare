# Three-Stage Self-Hosting Fixed-Point Report (`stage1 -> stage2 -> stage3`)

- **Upstream Baseline**: `9180ce6a7a7d3627f88b424717583f28ddca052d` (`2.4.20-dev-7885`)
- **Target**: `linux_x64` (`env -i PATH=/nonexistent`, **0 JVM**)
- **Production Source Closure**: `3,430` files ([`production.sha256`](production.sha256))
- **Distribution Manifest**: `697` files ([`dist-manifest.sha256`](dist-manifest.sha256))

## Stage Equivalence Table

| Stage | Executable Size (Bytes) | Defined Symbols | KLIB ABI SHA256 (`klib dump-abi -signature-version 2`) | Bitcode SHA256 | Executable SHA256 (`konanc-cli.kexe`) |
| :--- | :---: | :---: | :--- | :--- | :--- |
| **`stage1`** | `166,199,088` | `474,762` | `5366cdb5bc967940024f28345e57a8787f9398bcbe8de9ff9d98278296cd8860` | `from_e1` | `246c38344d6d098c48f6af1cb716f362bd078ce57d3731f589f5694e650eb99d` |
| **`stage2`** | `185,383,000` | `474,762` | `5366cdb5bc967940024f28345e57a8787f9398bcbe8de9ff9d98278296cd8860` | `e3b446e358be1b6b8161b2407a05be0deeca6dbc3a765cb55361aa79c1d9ee7a` | **`cdf4849a9f9a2a4892348a8b27face322c89f78dbfc8f99ccbbc1886701d025e`** |
| **`stage3`** | `185,383,000` | `474,762` | `5366cdb5bc967940024f28345e57a8787f9398bcbe8de9ff9d98278296cd8860` | `b6d88813582f9ad2e601e54b4f808a9d66d17b11a998fde5179edb795fe7b652` | **`cdf4849a9f9a2a4892348a8b27face322c89f78dbfc8f99ccbbc1886701d025e`** |

## Differential & Regression Verification

- **`stage1 == stage2 == stage3` KLIB ABI**: `0` diff (`5366cdb5bc967940024f28345e57a8787f9398bcbe8de9ff9d98278296cd8860`).
- **`stage2 == stage3` Executable Binary**: Bit-for-bit identical (`cdf4849a9f9a2a4892348a8b27face322c89f78dbfc8f99ccbbc1886701d025e`, `185,383,000` bytes).
- **E1 8-Section CLI/Frontend/Backend/Diagnostics/CInterop Suite**: `stage2` and `stage3` match JVM oracle with `0` diff.
- **D1 7-Section Stdlib/Runtime/Coroutines/CInterop/POSIX/Zlib Corpus**: `stage2` and `stage3` match JVM oracle with `0` diff.
- **Resource Envelope**: Completed in `4min 52.935s` with `1.3 GiB` peak RSS (`0 B` swap).

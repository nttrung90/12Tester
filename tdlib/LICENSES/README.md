# Third-party components in the release

The release zip (`LICENSES/`) carries the license text of every component compiled into `libtdjni.so` or shipped
next to it. The texts are not copied into this repository by hand: the build collects them from the pinned sources,
so they always match what was built.

| Component | License | Text in the release | Where the build takes it from |
|---|---|---|---|
| TDLib (tdlib/td at `TDLIB_COMMIT`) | Boost Software License 1.0 | `LICENSES/TDLib-LICENSE_1_0.txt` | `LICENSE_1_0.txt` in the pinned TDLib checkout |
| OpenSSL 3.5 LTS (`OPENSSL_VERSION`), statically linked | Apache License 2.0 | `LICENSES/OpenSSL-LICENSE.txt` | `LICENSE.txt` in the sha256-checked OpenSSL release tarball |
| zlib (Android's system `libz.so`, linked dynamically) | zlib license | `LICENSES/zlib-LICENSE.txt` | the license notice at the top of `zlib.h` in the pinned NDK sysroot |

The generated `TdApi.java` and the bundled `Client.java` are part of TDLib and covered by its license.

This repository's own files (Dockerfile, scripts, workflows, patch) are under the Boost Software License 1.0 as
well; see `LICENSE` at the repository root.

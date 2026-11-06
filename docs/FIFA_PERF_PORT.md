# Performance work ported from the FIFA Street Android port

FableDroid and the FIFA Street (2012) Android port are both ReXGlue recompilations with the
Xenos GPU emulated over Vulkan, so they share the same SDK bottlenecks. This branch ports the
FIFA port's SDK changes that are not game-specific, plus its diagnostic logs.

Everything is in [`thirdparty/rexglue-sdk-perf-port.patch`](../thirdparty/rexglue-sdk-perf-port.patch),
applied after `rexglue-sdk-android.patch`:

```sh
git -C thirdparty/rexglue-sdk apply ../rexglue-sdk-android.patch
git -C thirdparty/rexglue-sdk apply ../rexglue-sdk-perf-port.patch
```

Each change is behind a cvar. The launcher has a new **Performance** card that writes them to
`fable_2.toml`.

**Status: compile-checked on a Linux host build of the SDK only. Not built for Android and
not run on a device yet.** The ARM64-only parts (`#if __ANDROID__` / `REX_ARCH_ARM64`
blocks) were taken unchanged from the FIFA port, where they built and ran, but they have not
been compiled against this tree. All FIFA results below were measured on a Samsung
SM-A566B (Exynos 1580, Xclipse 540) with a different game; none of them are FableDroid results.

## Optimizations

| Launcher switch | cvars | What it does | FIFA result |
|---|---|---|---|
| CPU upload cache (on) | `vulkan_cache_cpu_uploads`, `vulkan_batch_upload_cache_rearm`, `vulkan_upload_cache_from_guest`, `shared_memory_unwatch_hot_pages` | Keeps a bounded copy of each uploaded page. A page whose bytes did not change is not uploaded again; only its write watch is rearmed, in one call per run of pages. Pages that take a write-watch fault about every frame stop being watched and are compared with the copy instead (and watched again after 64 equal compares). | `mprotect` share of the GPU thread 17% → 2%; about 30–40 hot pages |
| Separate present queue (on) | `vulkan_separate_present_queue` | `vkQueuePresentKHR` goes to a second queue of the same family; all rendering stays on queue 0. Falls back to one queue if device creation fails. | queue lock wait −99%, late-match FPS 21 → 26 |
| Fast cores for GPU and audio threads (on) | `android_worker_big_cores`, `android_audio_big_cores`, `android_worker_adpf` | Pins the GPU command and audio threads to the cores faster than the slowest cluster; reports the GPU thread's busy time per frame to `APerformanceHint`. | small; kept on in FIFA's stable builds |
| Resolve-to-texture shortcut (off) | `vulkan_resolve_texture_shortcut` | A resolve of a whole 1x RGBA8 render target area keeps an image copy of it. A later 2D RGBA8 texture load from the same address, pitch and endianness copies that image instead of running the texture load shader and buffer-to-image copy. Red/blue swap is handled with a blit. Snapshots are dropped on any CPU write or other GPU write to the range. | **unverified**: in FIFA the first build captured nothing; the build with skip-reason counters has not been tested yet |
| (always on) | `posix_wait_multiple_events` | Multi-handle waits wake on a signal instead of polling every millisecond. | Audio Worker 22–70% → 8–10% of a core |
| (always on) | — | GPU register writes look up "known register" in a table instead of a 3400-case switch. | removed from the GPU thread's profile |

Already in FableDroid before this port, so not ported: the pipeline cache `fopen("a+")` fix,
the `SDL_WaitEvent` busy-spin fix, and the fast write-watch fault path (`mmio_fast_fault_path`).

Why the resolve shortcut matters here: `docs/ANDROID_PERFORMANCE.md` names the
resolve-and-reload round trip (bloom chain, scene buffers, shadow maps, 30–55 texture loads
per frame) as the main remaining full-quality cost. In FIFA that chain was about 4 ms of
9.6 ms GPU time per frame. Whether Fable II's resolves qualify (1x, RGBA8, whole area, no
exponent bias) is exactly what the skip counters in the log will show.

## Diagnostic logging

The launcher's **Diagnostic logging** switch turns all of these on (and this project's own
`log_gpu_diagnostics` GPUSTAT/GPUTIME with `log_guest_fps_seconds = 5`):

| cvar | Log lines (every ~5 s unless noted) |
|---|---|
| `log_frame_stats` | `Guest frame timing` (FPS, worst interval, intervals over 45 ms), `GPU command worker` (% waiting for the guest), `Worker wait split` (ring empty vs register poll), `Worker tuning` / `Worker actual CPU` (GPU thread CPU time per frame), `CPU upload cache`, `Hot pages` |
| `log_late_frames` | `Late frame` for every swap interval over 45 ms, with how much of it the GPU thread was waiting for the game |
| `log_thread_cpu` | `Thread CPU` — every thread above 5% of a core, with name and last CPU. `log_thread_samples` (off by default) adds SIGPROF pc/lr samples of the busiest thread |
| `log_thermal` | `Thermal` — Android thermal status and headroom, CPU policy current/cap/max MHz, readable GPU clocks (Adreno kgsl and Samsung paths) and thermal zones |
| `log_present_paints` | `Present paints` — paints and presents per second and from which thread. More paints than guest frames means repaints of the same frame |
| `log_audio_output` | `Audio output` — frames played, silence inserted (underruns), lowest queue depth |
| `log_resolve_texture_loads` | `Resolve texture loads` — texture loads that read memory a recent resolve wrote, by format and size. With the shortcut on: `Resolve texture shortcut` hits, misses and skip reasons |
| `log_gpu_diagnostics` (GPUTIME) | now also splits `resolve`, `rt_dump`, `texload` and `texsnap` segments out of `out` |
| always | `ImGui dialog added/removed` (a registered dialog makes the presenter repaint every display tick), `Present queue plan/ready/routing`, unhandled-fault host backtrace (arm64), failed and ≥1 MiB guest allocations |

`battery.log` (written by the launcher's game activity every 15 s while diagnostic logging is
on) has battery temperature, charge, power saver and thermal status.

## Suggested first test

1. Default settings, Diagnostic logging on, 10 minutes of normal play from a cold device.
2. Same route with the CPU upload cache off, then with the separate present queue off — one
   change per run.
3. Resolve shortcut on, short session: check the image (bloom, shadows, menus) and the
   `Resolve texture shortcut` line. If images look wrong, turn it off.

## Render resolution presets (720p / 540p / 480p / 360p)

Launcher: Graphics → **Render resolution** (default 720p). Writes `fable2_render_height`
(0 = native). The `RenderConfigInit_8238DE58` hotfunc overwrites the main render size the game
stores (offsets 148/152, next to the MSAA mode): 1120×720 → 832×544, 736×480 or 576×368; in the
game's 960-wide case → 736×544, 640×480, 480×368. Width is a multiple of 32 and height of 16, the
same alignment as 1120×720. (The first version rounded to 8, giving 744×480, and the game crashed
right after the size change: a tiled-layout vector grew to 308 MB and a guest allocation failed.) The game's own path scales that to the
1280×720 front buffer. Unlike FIFA's 480p/360p this changes the guest render size, not only the
output. The log line `Fable II render size ... (fable2_render_height)` confirms it applied.

**Unverified on device.** Check: whole picture visible (no crop or quarter-screen image), HUD and
menus, bloom/shadows, and compare GPU time per frame against 720p from a cold start.

## First device results (SM-A566B, Exynos 1580 / Xclipse 540, system driver)

Same save and spot, 480p preset, power saver on (not changed between runs), 2–3 minutes each.
Thermal status differed between runs (0 → 1–3), so the later minutes are not comparable.

| Run | Low latency | FIFA-port options | Guest FPS (first 90 s / rest) | GPU worker idle waiting for guest |
|---|---|---|---|---|
| A | on | on | 5–10 | 40–95% |
| B | off | off | 25.6 / 17.7 | 0–5% |
| C | off | on | 21.3 / 19.7 | 0–5% |

- **Low latency (`guest_max_frames_ahead = 1`) caused the 7 FPS.** In run A the guest waited in
  `VdSwap` for about 98 ms per frame on average (`Late frame … guest_wait`), i.e. the swap-pacing
  wait ran into its 100 ms timeout almost every frame while the CP sat idle with an empty ring.
  The launcher now defaults it to off. Why the CP does not catch up within the wait on this
  device is not known yet.
- **FIFA-port options: no measurable effect either way** (B vs C, within thermal noise).
  `posix_wait_multiple_events` stayed on in all three runs.
- **Next limit: the GPU command thread**, about 47–50 ms of thread CPU per guest frame and almost
  never idle. The launcher's *Thread sampling* option (with diagnostic logging) logs where it
  spends that time (`Thread samples … GPU Commands`).
- Resolve-to-texture shortcut (run C only) hit for the first time in Fable (hits=301,
  misses=1340 by the end); image check pending.

## Guest global lock (`guest_global_lock`, launcher: *Lock-free guest atomics*)

Run r10 (`log_global_lock`) showed guest threads queueing on the global critical region at
guest atomics: GameThread @ SpinLock_Acquire ~712 ms, XThreads @ AddAtomic / ProcessGlobalCounter
310–580 ms per 18 s, partly behind the GPU thread holding the lock in
`PhysicalHeap::EnableAccessCallbacks`.

- The codegen turns the guest's `mfmsr` / `mtmsrd r13` interrupt-disable sequence into
  `REX_CHECK/ENTER/LEAVE_GLOBAL_LOCK`. All 8406 such sections in Fable II wrap a single
  `lwarx` … `stwcx.` pair (2–4 instructions, checked by script over `generated/default`), and
  `stwcx.` is emitted as `compare_exchange_strong`, so the lock adds no atomicity.
- `fable_2_manifest.toml` already asks for `skip_msr = true` (and `skip_lr`, `*_as_local`, the
  same block as UnleashedRecomp's `SWA.toml`), but the block sits after the last
  `[[entrypoint.midasm_hook]]` header, so TOML puts it inside that hook table and the codegen,
  which reads the root table, never sees it. The generated code confirms it (`ctx.lr = …`
  stores and `REX_ENTER_GLOBAL_LOCK` are emitted). Left unchanged for now.
- References: UnleashedRecomp builds with `skip_msr = true` (no lock at all); current Xenia
  Canary's `mtmsrd` only stores `ctx.msr` and takes no lock.
- `guest_global_lock` (default true, restart): false makes the three macros no-ops (`mfmsr`
  reads 0x8000). It is copied once in `Runtime::Setup` before guest code runs. The hand-tuned
  hotfuncs that take the same mutex follow it; `ConstructRefCounted` then increments the
  live-object counter at 0x83496EB8 with a CAS instead of a plain store, because its other
  writer (generated `DestructRefCounted`) is no longer serialised by the lock.
- Host code (`global_critical_region::Acquire`, interrupt dispatch, GPU access callbacks) is
  unaffected.

### First device result (r11, SM-A566B, 480p, power saver on, 2026-11-06)

Two short runs of the same build; only `guest_global_lock` differs. Gameplay window = 43–73 s
after start (same save; the exact spot is not known to be identical).

| | 012: lock on | 014: lock off |
|---|---|---|
| Battery temp at start | 31.8 °C | 23.4 °C |
| Guest FPS, 5 s averages | 10.5, 22.7, 20.4, 15.3, 16.1, 19.1 (mean 17.4) | 18.2, 17.1, 19.5, 20.1, 26.7, 24.0, 23.5 (mean 21.3) |
| Global lock waits, all threads | ~4.9 s per 5 s | 0.3–0.6 s per 5 s |
| GameThread lock waits | ~1.05 s per 5 s | 6–9 ms per 5 s |
| Thread CPU (threads ≥ 5%) | ~176% of a core | ~269% of a core |
| GameThread / GPU Commands / 3D Engine CPU | 37% / 40% / 37% | 61% / 59% / 49% |

- The lock waits are gone as expected. The remaining GPU Commands waits are in
  `SharedMemory::RequestRanges` behind 3D Engine's `MMIOHandler::ExceptionCallback`.
- CPU rose more than FPS. GameThread samples in 014 show no spinning in `SpinLock_Acquire`
  (top entries: nanosleep 18%, game functions), but the samples are few.
- Heat cannot be compared: different starting temperatures and only ~75 s of play.
- No hang or crash during play. Run 013 (lock off) crashed 19 s in after the app went to the
  background (`onPause`, then "Failed to get Vulkan surface capabilities"): the known
  background-resume crash, not this option.
- Not yet a measured speed-up: needs a longer pair of runs from a cold start.

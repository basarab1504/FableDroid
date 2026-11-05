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

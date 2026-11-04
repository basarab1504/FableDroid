// Hand-written override: "Disable MSAA" (Xenia patch be8 0x8238DF3F = 0x01).
// The first line makes tools/make_hotfunc_overrides.py skip this file.
//
// sub_8238DE58 initialises the renderer's back-buffer description. Its tail
// (0x8238DF30-0x8238DF68) stores the main render size and multisample mode:
//   li r9,2         (0x8238DF3C)  multisample mode: 2 = 2x MSAA, 1 = none
//   li r10,720  ->  stw r10,152(r31)          height
//   li r11,1120 (960 when a flag is clear) -> stw r11,148(r31)   width
//   stw r9,156(r31)                           multisample mode
// r31 == r3 (this) for the whole function. The Xenia patch rewrites the
// immediate at 0x8238DF3C; the addresses match this build exactly. Here we
// call the original and then overwrite the stored mode, which has the same
// effect (the field is written only here and r9 is dead afterwards).
//
// Without MSAA the 1120x720 colour (8 bytes per sample on host, 4 on guest)
// plus depth fits in one 10 MB EDRAM tile, so the game no longer replays the
// scene once per predicated tile, and the host main pass has half the samples.
//
// Toggle: cvar fable2_disable_msaa (default false = original 2x MSAA).
//
// Render height presets (cvar fable2_render_height, 0 = native 720): the same
// tail stores the main render width/height, so we overwrite them after the
// original runs. The width is scaled from whatever the game stored (1120, or
// 960 in its 4:3 case) to keep the aspect, rounded to a multiple of 8. Only
// heights below 720 are applied (360, 480, 540 in the launcher); the game's
// own path then scales this smaller render to the 1280x720 front buffer.
// Unverified on device: if a pass assumes 720 lines it will show up as a
// misplaced/cropped image, so the default stays native.
#include "fable_2_pch.h"
#include <rex/cvar.h>
#include <rex/logging.h>

REXCVAR_DEFINE_BOOL(fable2_disable_msaa, false, "GPU",
                    "Render Fable II without its 2x MSAA (Xenia 'Disable MSAA' patch)");
REXCVAR_DEFINE_UINT32(fable2_render_height, 0, "GPU",
                      "Fable II main render height below the native 720 (e.g. 360, 480, 540); "
                      "0 keeps the native 1120x720. Width keeps the game's aspect");

extern "C" void __imp__sub_8238DE58(PPCContext& __restrict ctx, uint8_t* base);

namespace {
constexpr uint32_t kMultisampleModeOffset = 156;
constexpr uint32_t kMultisampleNone = 1;
constexpr uint32_t kWidthOffset = 148;
constexpr uint32_t kHeightOffset = 152;
constexpr uint32_t kMinRenderHeight = 240;
}  // namespace

extern "C" void sub_8238DE58(PPCContext& __restrict ctx, uint8_t* base) {
  const uint32_t self = ctx.r3.u32;
  __imp__sub_8238DE58(ctx, base);
  if (REXCVAR_GET(fable2_disable_msaa)) {
    REX_STORE_U32(self + kMultisampleModeOffset, kMultisampleNone);
  }
  const uint32_t target_height = REXCVAR_GET(fable2_render_height);
  if (target_height >= kMinRenderHeight) {
    const uint32_t width = REX_LOAD_U32(self + kWidthOffset);
    const uint32_t height = REX_LOAD_U32(self + kHeightOffset);
    if (height > target_height && width != 0) {
      uint32_t new_width = (uint32_t)((uint64_t(width) * target_height + height / 2) / height);
      new_width = (new_width + 4) & ~7u;
      const uint32_t new_height = target_height & ~7u;
      REX_STORE_U32(self + kWidthOffset, new_width);
      REX_STORE_U32(self + kHeightOffset, new_height);
      REXLOG_INFO("Fable II render size {}x{} -> {}x{} (fable2_render_height)", width, height,
                  new_width, new_height);
    }
  }
}

/*
 * Flat C shim over libwebp 1.6.0 (vendor/libwebp). Keeping the WebPConfig / WebPPicture structs on the
 * C side means the Rust code never has to mirror their layouts.
 *
 * License: GPL-3.0-or-later for this file; libwebp itself is BSD-3-Clause (vendor/libwebp/COPYING).
 */
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "src/webp/decode.h"
#include "src/webp/encode.h"

/* Mirrors `WebpParams` in src/webp.rs (all 32-bit fields, no padding). */
typedef struct {
  int32_t lossless;
  float quality;
  int32_t method;
  int32_t sharp_yuv;
  int32_t alpha_quality;
  int32_t near_lossless;
  int32_t exact;
  int32_t thread_level;
  int32_t pass;
  int32_t segments;
  int32_t sns_strength;
  int32_t filter_strength;
  int32_t filter_sharpness;
  int32_t filter_type;
  int32_t autofilter;
  int32_t alpha_filtering;
  int32_t alpha_compression;
  int32_t preprocessing;
  int32_t partitions;
  int32_t partition_limit;
} SsivWebpParams;

#define SSIV_ERR_BAD_ARGS 100
#define SSIV_ERR_BAD_CONFIG 101
#define SSIV_ERR_IMPORT 102

/* Returns 0 on success (`*out` / `*out_size` set, release with ssiv_webp_free), otherwise an error code:
 * 1..N = WebPEncodingError, 100+ = shim errors. `rgba` is straight (non-premultiplied) RGBA. */
int ssiv_webp_encode(const uint8_t* rgba, int width, int height, int stride, int has_alpha,
                     const SsivWebpParams* p, uint8_t** out, size_t* out_size) {
  WebPConfig config;
  WebPPicture pic;
  WebPMemoryWriter writer;
  int ok;
  int code = 0;

  if (rgba == NULL || p == NULL || out == NULL || out_size == NULL) return SSIV_ERR_BAD_ARGS;
  *out = NULL;
  *out_size = 0;
  if (width <= 0 || height <= 0 || width > WEBP_MAX_DIMENSION || height > WEBP_MAX_DIMENSION ||
      stride < width * 4) {
    return SSIV_ERR_BAD_ARGS;
  }
  if (!WebPConfigInit(&config)) return SSIV_ERR_BAD_CONFIG;
  config.lossless = p->lossless;
  config.quality = p->quality;
  config.method = p->method;
  config.use_sharp_yuv = p->sharp_yuv;
  config.alpha_quality = p->alpha_quality;
  config.near_lossless = p->near_lossless;
  config.exact = p->exact;
  config.thread_level = p->thread_level;
  config.pass = p->pass;
  config.segments = p->segments;
  config.sns_strength = p->sns_strength;
  config.filter_strength = p->filter_strength;
  config.filter_sharpness = p->filter_sharpness;
  config.filter_type = p->filter_type;
  config.autofilter = p->autofilter;
  config.alpha_filtering = p->alpha_filtering;
  config.alpha_compression = p->alpha_compression;
  config.preprocessing = p->preprocessing;
  config.partitions = p->partitions;
  config.partition_limit = p->partition_limit;
  if (!WebPValidateConfig(&config)) return SSIV_ERR_BAD_CONFIG;

  if (!WebPPictureInit(&pic)) return SSIV_ERR_BAD_CONFIG;
  pic.width = width;
  pic.height = height;
  /* ARGB mode: WebPEncode converts to YUV itself, using SharpYUV when use_sharp_yuv is set. */
  pic.use_argb = 1;
  ok = has_alpha ? WebPPictureImportRGBA(&pic, rgba, stride) : WebPPictureImportRGBX(&pic, rgba, stride);
  if (!ok) {
    WebPPictureFree(&pic);
    return SSIV_ERR_IMPORT;
  }
  WebPMemoryWriterInit(&writer);
  pic.writer = WebPMemoryWrite;
  pic.custom_ptr = &writer;
  ok = WebPEncode(&config, &pic);
  if (!ok) {
    code = (int)pic.error_code;
    if (code == 0) code = SSIV_ERR_IMPORT;
  } else if (writer.mem == NULL || writer.size == 0) {
    code = SSIV_ERR_IMPORT;
  } else {
    *out = writer.mem;
    *out_size = writer.size;
    writer.mem = NULL;
  }
  WebPMemoryWriterClear(&writer);
  WebPPictureFree(&pic);
  return code;
}

void ssiv_webp_free(void* ptr) { WebPFree(ptr); }

/* Decodes to straight RGBA. Returns 0 on success; release with ssiv_webp_free. Used by tests. */
int ssiv_webp_decode_rgba(const uint8_t* data, size_t size, int* width, int* height, uint8_t** out) {
  uint8_t* px;
  if (data == NULL || width == NULL || height == NULL || out == NULL) return SSIV_ERR_BAD_ARGS;
  px = WebPDecodeRGBA(data, size, width, height);
  if (px == NULL) return SSIV_ERR_IMPORT;
  *out = px;
  return 0;
}

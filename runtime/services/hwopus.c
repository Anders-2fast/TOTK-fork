// hwopus: the Switch's Opus decoder service (nn::codec::HardwareOpusDecoder). TOTK opens it at boot - BOTW never did -
// and sized its sound heap from the work buffer sizes the logging stub left at 0: an allocation from it came back null
// and the game wrote through it. Decoding goes through FFmpeg's Opus decoder (the app's libavcodec, as hle/movie.c).
//
// A packet as the game sends it: a big-endian header {u32 size; u32 final_range} and then size bytes of Opus. The
// output is 16-bit PCM, channels interleaved, at the decoder's sample rate (48000, or 24000 / 16000 / 12000 / 8000:
// Opus decodes at 48 kHz and every 48000 / rate samples are averaged into one).
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/mem.h>
#include <stdlib.h>
#include <string.h>

#include "../hle.h"
#include "../ipc.h"

#define R_CODEC_INVALID_ARGUMENT (111 | 32 << 9)  // nn::codec (module 111), description 32
#define MAX_FRAME_48K 5760                // 120 ms, the longest Opus frame

typedef struct {
    AVCodecContext *ctx;
    AVPacket *pkt;
    AVFrame *frame;
    int rate, channels, step;  // step: 48 kHz samples per output sample
    uint8_t *in;               // the packet, with the padding FFmpeg reads past its end
    size_t in_cap;
    int errors;
    FILE *raw;                 // LARECOMP_OPUS_RAW=<folder>: what it decoded, opus_<n>.raw (s16, its channels and rate)
} OpusDec;

// Work buffer sizes, after the console's own decoder state: Opus' SILK and CELT decoders per channel plus the frames
// it keeps. Only the game's heaps depend on them (its work buffer is never read here), so they're generous, not exact.
static uint32_t celt_size(uint32_t ch) { return (0x2030 + 120 * 4) * ch + 21 * 16 + 0x50; }
static uint32_t work_buffer_size(uint32_t ch, int large_frame) {
    if (ch < 1 || ch > 255) return 0;
    uint32_t decoder = (0x4c + 0x2160 + celt_size(ch < 2 ? ch : 2) + 15) & ~15u;
    return decoder * ((ch + 1) / 2) + 0x600 + (large_frame ? MAX_FRAME_48K : 1920) * ch * 4;
}

static void opus_close(Service *s) {
    OpusDec *d = s->state;
    if (!d) return;
    avcodec_free_context(&d->ctx);
    av_packet_free(&d->pkt);
    av_frame_free(&d->frame);
    av_free(d->in);
    if (d->raw) fclose(d->raw);
    free(d);
    s->state = NULL;
}

static OpusDec *opus_open(uint32_t rate, uint32_t channels) {
    if (channels < 1 || channels > 2 || !rate || 48000 % rate) {
        LOG("[hwopus] decoder %u Hz x%u: not supported\n", rate, channels);
        return NULL;
    }
    OpusDec *d = calloc(1, sizeof *d);
    d->rate = (int)rate, d->channels = (int)channels, d->step = 48000 / (int)rate;
    // (FFmpeg's Opus decoder needs libswresample: android/deps/build_ffmpeg.sh's FFmpeg has both, LASRecompAndroid's
    // in android/deps/out neither. Without it the decoder gives silence of the packets' length: a failed open made
    // TOTK abort when its title screen opened one.)
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_OPUS);
    d->ctx = codec ? avcodec_alloc_context3(codec) : NULL;
    if (!d->ctx) {
        static int once;
        if (!once++) LOG("[hwopus] no Opus decoder in FFmpeg: Opus audio is silent\n");
        return d;
    }
    d->ctx->sample_rate = 48000;
    av_channel_layout_default(&d->ctx->ch_layout, (int)channels);
    if (avcodec_open2(d->ctx, codec, NULL) < 0) {
        LOG("[hwopus] FFmpeg's Opus decoder didn't open (%u channels)\n", channels);
        avcodec_free_context(&d->ctx);
        free(d);
        return NULL;
    }
    d->pkt = av_packet_alloc();
    d->frame = av_frame_alloc();
    static int opened;
    const char *dir = getenv("LARECOMP_OPUS_RAW");
    if (dir) {
        char path[512];
        snprintf(path, sizeof path, "%s/opus_%d.raw", dir, opened);
        d->raw = fopen(path, "wb");
    }
    opened++;
    return d;
}

// Samples per channel at 48 kHz in an Opus packet, from its TOC byte (RFC 6716 3.1): the frame duration of its
// configuration times its frame count; 0 if malformed
static int opus_packet_samples(const uint8_t *p, uint32_t size) {
    if (!size) return 0;
    static const int dur[32] = {480, 960, 1920, 2880, 480, 960, 1920, 2880, 480, 960, 1920, 2880,  // SILK
                                480, 960, 480, 960,                                                    // hybrid
                                120, 240, 480, 960, 120, 240, 480, 960, 120, 240, 480, 960, 120, 240, 480, 960};  // CELT
    int code = p[0] & 3, frames = code == 0 ? 1 : code < 3 ? 2 : size > 1 ? p[1] & 0x3F : 0;
    int n = dur[p[0] >> 3] * frames;
    return n <= MAX_FRAME_48K ? n : 0;
}

static inline uint32_t be32(const uint8_t *p) { return (uint32_t)p[0] << 24 | p[1] << 16 | p[2] << 8 | p[3]; }
static inline int16_t to_s16(float v) {
    v *= 32768.0f;
    return v >= 32767.0f ? 32767 : v <= -32768.0f ? -32768 : (int16_t)v;
}

// One packet into out (out_size bytes): the bytes it took and the samples per channel it gave, or a result code
static uint32_t opus_decode(OpusDec *d, const uint8_t *in, uint64_t in_size, int16_t *out, uint64_t out_size,
                            uint32_t *consumed, uint32_t *samples) {
    *consumed = *samples = 0;
    if (in_size < 8 || 8 + (uint64_t)be32(in) > in_size) return R_CODEC_INVALID_ARGUMENT;
    uint32_t size = be32(in);
    if (!d->ctx) {  // (no decoder: silence)
        uint64_t n = (uint64_t)opus_packet_samples(in + 8, size) / d->step;
        if (n * d->channels * 2 > out_size) n = out_size / 2 / d->channels;
        memset(out, 0, n * d->channels * 2);
        *consumed = 8 + size;
        *samples = (uint32_t)n;
        return 0;
    }
    if (d->in_cap < size + AV_INPUT_BUFFER_PADDING_SIZE) {
        av_free(d->in);
        d->in_cap = size + AV_INPUT_BUFFER_PADDING_SIZE;
        d->in = av_malloc(d->in_cap);
    }
    memcpy(d->in, in + 8, size);
    memset(d->in + size, 0, AV_INPUT_BUFFER_PADDING_SIZE);
    d->pkt->data = d->in;
    d->pkt->size = (int)size;
    *consumed = 8 + size;
    int r = avcodec_send_packet(d->ctx, d->pkt);
    if (r >= 0) r = avcodec_receive_frame(d->ctx, d->frame);
    if (r < 0) {  // (a packet the decoder refuses: no samples, as a lost packet would give)
        if (d->errors++ < 8) LOG("[hwopus] packet of %u bytes not decoded (%d)\n", size, r);
        return 0;
    }
    AVFrame *f = d->frame;
    int ch = d->channels, n = f->nb_samples / d->step;
    if ((uint64_t)n * ch * 2 > out_size) n = (int)(out_size / 2 / ch);
    int planar = f->format == AV_SAMPLE_FMT_FLTP;
    for (int i = 0; i < n; i++)
        for (int c = 0; c < ch; c++) {
            float sum = 0;
            for (int k = 0; k < d->step; k++) {
                int j = i * d->step + k;
                sum += planar ? ((const float *)f->extended_data[c])[j] : ((const float *)f->data[0])[j * ch + c];
            }
            out[i * ch + c] = to_s16(sum / (float)d->step);
        }
    av_frame_unref(f);
    *samples = (uint32_t)n;
    if (d->raw) fwrite(out, 2, (size_t)n * ch, d->raw);
    // LARECOMP_LOG_OPUS=1: every 200th packet decoded, with its loudest sample (sound or silence, without listening)
    static int log_opus = -1, decoded;
    if (log_opus < 0) log_opus = getenv("LARECOMP_LOG_OPUS") != NULL;
    if (log_opus && decoded++ % 200 == 0) {
        int peak = 0;
        for (int i = 0; i < n * ch; i++) peak = abs(out[i]) > peak ? abs(out[i]) : peak;
        LOG("[hwopus] packet %d: %u bytes -> %d samples x%d at %d Hz, peak %d\n", decoded, size, n, ch, d->rate, peak);
    }
    return 0;
}

// IHardwareOpusDecoder
static void opus_decoder(Service *s, IpcReq *req, IpcResp *resp) {
    OpusDec *d = s->state;
    switch (req->cmd) {
    case 0: case 4: case 6: case 8: {  // DecodeInterleavedOld / WithPerfOld / WithPerfAndResetOld / DecodeInterleaved
        if ((req->cmd == 6 || req->cmd == 8) && IN(uint8_t, 0) && d->ctx) avcodec_flush_buffers(d->ctx);  // reset
        IpcBuf *in = in_buf(req, 0), *out = out_buf(req, 0);
        uint32_t consumed = 0, samples = 0;
        resp->result = in && out ? opus_decode(d, (const uint8_t *)(uintptr_t)in->addr, in->size,
                                               (int16_t *)(uintptr_t)out->addr, out->size, &consumed, &samples)
                                 : R_CODEC_INVALID_ARGUMENT;
        OUT(uint32_t, 0) = consumed;
        OUT(uint32_t, 4) = samples;
        OUT(uint64_t, 8) = 0;  // time taken (the WithPerf variants)
        resp_size(resp, req->cmd == 0 ? 8 : 16);
        break;
    }
    case 1: break;  // SetContext
    default:
        LOG("[hwopus] IHardwareOpusDecoder cmd %u (multistream?): not done\n", req->cmd);
        resp->result = R_CODEC_INVALID_ARGUMENT;
    }
}

static void return_decoder(IpcResp *resp, uint32_t rate, uint32_t channels) {
    OpusDec *d = opus_open(rate, channels);
    if (!d) {
        resp->result = R_CODEC_INVALID_ARGUMENT;
        return;
    }
    Service *svc = service_new("IHardwareOpusDecoder", opus_decoder, d);
    svc->on_close = opus_close;
    ipc_return_object(resp, svc);
    static int n;
    if (n++ < 16) LOG("[hwopus] decoder %u Hz x%u\n", rate, channels);
}

// Multistream parameters (an input buffer): sample rate, channels, streams, stereo streams, the channel mapping
static uint32_t multistream_size(IpcReq *req, int large_frame) {
    IpcBuf *b = in_buf(req, 0);
    if (!b || b->size < 16) return 0;
    const uint32_t *p = (const uint32_t *)(uintptr_t)b->addr;
    uint32_t streams = p[2], stereo = p[3];
    return work_buffer_size(2, large_frame) * stereo + work_buffer_size(1, large_frame) * (streams - stereo);
}

// IHardwareOpusDecoderManager. The parameters: {u32 sample_rate, u32 channel_count} (+ u32 flags, bit 0 the large
// frame size, in the Ex versions)
SERVICE("hwopus", hwopus) {
    (void)s;
    uint32_t rate = IN(uint32_t, 0), channels = IN(uint32_t, 4);
    switch (req->cmd) {
    case 0: return_decoder(resp, rate, channels); break;  // OpenHardwareOpusDecoder (+ work size, transfer memory)
    case 4: return_decoder(resp, rate, channels); break;  // OpenHardwareOpusDecoderEx
    case 1:                                               // GetWorkBufferSize
        OUT(uint32_t, 0) = work_buffer_size(channels, 0);
        resp_size(resp, 4);
        break;
    case 5: case 8:  // GetWorkBufferSizeEx / GetWorkBufferSizeExEx
        OUT(uint32_t, 0) = work_buffer_size(channels, IN(uint32_t, 8) & 1);
        resp_size(resp, 4);
        break;
    case 3: case 7: case 9:  // GetWorkBufferSizeForMultiStream / Ex / ExEx
        OUT(uint32_t, 0) = multistream_size(req, req->cmd != 3);
        resp_size(resp, 4);
        break;
    default:  // OpenHardwareOpusDecoderForMultiStream / Ex
        LOG("[hwopus] cmd %u (multistream decoder): not done\n", req->cmd);
        resp->result = R_CODEC_INVALID_ARGUMENT;
    }
}

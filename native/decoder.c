#define _FILE_OFFSET_BITS 64
#define _POSIX_C_SOURCE 200809L
#include "decoder.h"
#include <opusfile.h>
#include <unistd.h>
#include <errno.h>
#include <stdlib.h>
#include <limits.h>
#include <time.h>

struct VnDecoder {
    OggOpusFile *file;
    int fd;
    int64_t offset, length, position;
    float stereo[5760 * 2];
    int failure, checks;
    int64_t call_bytes, total_bytes, deadline_ns;
    VnCancelCheck cancelled;
    void *cancel_opaque;
};
/* Bound work even inside synchronous libopusfile header/chain parsing. Limits apply
   before allocation-driving reads; malformed giant tags cannot bypass the PCM cap. */
#define MAX_INPUT_BYTES (64LL * 1024 * 1024)
#define MAX_CALL_BYTES (2LL * 1024 * 1024)
#define MAX_TOTAL_BYTES (128LL * 1024 * 1024)
static int64_t now_ns(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts)) return INT64_MAX;
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}
static void begin_call(VnDecoder *d) {
    d->call_bytes = 0; d->checks = 0;
    int64_t now = now_ns();
    d->deadline_ns = now > INT64_MAX - 2000000000LL ? INT64_MAX : now + 2000000000LL;
}
static int abort_work(VnDecoder *d) {
    if (d->failure) return 1;
    if (d->cancelled && d->cancelled(d->cancel_opaque)) d->failure = VN_CANCELLED;
    else if (++d->checks > 8192 || now_ns() >= d->deadline_ns) d->failure = VN_LIMIT;
    return d->failure != 0;
}
static int read_cb(void *opaque, unsigned char *ptr, int n) {
    VnDecoder *d = opaque;
    if (n < 0 || abort_work(d)) return -1;
    if (d->length >= 0 && n > d->length - d->position) n = (int)(d->length - d->position);
    if (n > MAX_CALL_BYTES - d->call_bytes || n > MAX_TOTAL_BYTES - d->total_bytes) {
        d->failure = VN_LIMIT; return -1;
    }
    ssize_t got;
    do { got = pread(d->fd, ptr, (size_t)n, d->offset + d->position); } while (got < 0 && errno == EINTR);
    if (got > 0) { d->position += got; d->call_bytes += got; d->total_bytes += got; }
    if (abort_work(d)) return -1;
    return (int)got;
}
static int seek_cb(void *opaque, opus_int64 delta, int whence) {
    VnDecoder *d = opaque;
    if (abort_work(d)) return -1;
    int64_t base;
    if (whence == SEEK_SET) base = 0;
    else if (whence == SEEK_CUR) base = d->position;
    else if (whence == SEEK_END && d->length >= 0) base = d->length;
    else return -1;
    if ((delta > 0 && base > INT64_MAX - delta) || (delta < 0 && delta < -base)) return -1;
    int64_t pos = base + delta;
    if (pos < 0 || (d->length >= 0 && pos > d->length) || pos > INT64_MAX - d->offset) return -1;
    d->position = pos;
    return 0;
}
static opus_int64 tell_cb(void *opaque) { return ((VnDecoder *)opaque)->position; }
VnDecoder *vn_open(int fd, int64_t offset, int64_t length, int *error) {
    return vn_open_controlled(fd, offset, length, error, NULL, NULL);
}
VnDecoder *vn_open_controlled(int fd, int64_t offset, int64_t length, int *error,
                            VnCancelCheck cancelled, void *opaque) {
    if (error) *error = OP_EINVAL;
    if (fd < 0 || offset < 0 || length < -1 || (length >= 0 && length > INT64_MAX - offset)) return NULL;
    VnDecoder *d = calloc(1, sizeof(*d));
    if (!d) { if (error) *error = OP_EFAULT; return NULL; }
    d->fd = dup(fd);
    if (d->fd < 0) { free(d); return NULL; }
    d->offset = offset; d->length = length; d->cancelled = cancelled; d->cancel_opaque = opaque;
    begin_call(d);
    if (abort_work(d)) { if (error) *error = d->failure; close(d->fd); free(d); return NULL; }
    /* SAF pipes cannot be positional-read. Reject them cleanly for MediaCodec fallback. */
    unsigned char probe;
    if (pread(d->fd, &probe, 1, offset) != 1) { close(d->fd); free(d); return NULL; }
    const OpusFileCallbacks cb = { read_cb, seek_cb, tell_cb, NULL };
    /* Budgeted partial open identifies Opus before applying its byte-size policy.
       A large WAV/MP3 must still reach MediaCodec, not be rejected as unsafe Opus. */
    d->file = op_test_callbacks(d, &cb, NULL, 0, error);
    int open_error = 0;
    if (d->file && !abort_work(d)) {
        if (length > MAX_INPUT_BYTES) d->failure = VN_LIMIT;
        else open_error = op_test_open(d->file);
    }
    if (!d->file || abort_work(d) || open_error < 0) {
        if (error && open_error < 0) *error = open_error;
        if (error && d->failure) *error = d->failure;
        if (d->file) op_free(d->file);
        close(d->fd); free(d); return NULL;
    }
    return d;
}
int vn_read(VnDecoder *d, float *pcm, int capacity) {
    if (!d || !pcm || capacity < 1 || capacity > 5760) return OP_EINVAL;
    begin_call(d);
    if (abort_work(d)) return d->failure;
    int n = op_read_float_stereo(d->file, d->stereo, capacity * 2);
    if (abort_work(d)) return d->failure;
    if (n <= 0) return n; /* Holes and malformed packets fail closed to the Android path. */
    for (int i = 0; i < n; ++i) pcm[i] = 0.5f * (d->stereo[2*i] + d->stereo[2*i+1]);
    return n;
}
int64_t vn_total(VnDecoder *d) { return d ? op_pcm_total(d->file, -1) : -1; }
void vn_close(VnDecoder *d) {
    if (!d) return;
    op_free(d->file); close(d->fd); free(d);
}

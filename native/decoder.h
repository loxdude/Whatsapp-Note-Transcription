#ifndef VN_DECODER_H
#define VN_DECODER_H
#include <stdint.h>
typedef struct VnDecoder VnDecoder;
/* Owns a duplicate fd; offset/length describe a SAF asset (length -1 means unknown).
   Positional reads never move the caller's shared descriptor offset. */
enum { VN_LIMIT = -10001, VN_CANCELLED = -10002 };
typedef int (*VnCancelCheck)(void *opaque);
VnDecoder *vn_open(int fd, int64_t offset, int64_t length, int *error);
VnDecoder *vn_open_controlled(int fd, int64_t offset, int64_t length, int *error,
                            VnCancelCheck cancelled, void *opaque);
/* 48 kHz mono float PCM, header gain applied, pre-skip/end trim handled by opusfile. */
int vn_read(VnDecoder *decoder, float *pcm, int capacity);
int64_t vn_total(VnDecoder *decoder);
void vn_close(VnDecoder *decoder);
#endif

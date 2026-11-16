"""Integration tests for the native core shipped on Android (ffmpeg supplies fixtures/reference).
Run: python3 native/tests/test_decoder.py build/native-host/libvoicenotes_opus.so
"""
import ctypes as C
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import array
import math

LIBRARY = Path(sys.argv.pop(1)) if len(sys.argv) > 1 else Path('build/native-host/libvoicenotes_opus.so')


def load_library(path=LIBRARY):
    lib = C.CDLL(str(path.resolve()))
    lib.vn_open.argtypes = [C.c_int, C.c_longlong, C.c_longlong, C.POINTER(C.c_int)]
    lib.vn_open.restype = C.c_void_p
    lib.vn_read.argtypes = [C.c_void_p, C.POINTER(C.c_float), C.c_int]
    lib.vn_read.restype = C.c_int
    lib.vn_total.argtypes = [C.c_void_p]
    lib.vn_total.restype = C.c_longlong
    lib.vn_close.argtypes = [C.c_void_p]
    return lib


def decode_file(lib, path, offset=0, length=None):
    with path.open('rb') as source:
        error = C.c_int()
        handle = lib.vn_open(source.fileno(), offset, path.stat().st_size if length is None else length, C.byref(error))
        if not handle: raise ValueError(f'open error {error.value}')
        out = array.array('f')
        try:
            pcm = (C.c_float * 5760)()
            while True:
                n = lib.vn_read(handle, pcm, len(pcm))
                if n < 0: raise ValueError(f'read error {n}')
                if n == 0: break
                out.extend(pcm[:n])
        finally: lib.vn_close(handle)
        return out


class DecoderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR'))
        cls.root = Path(cls.tmp.name)
        cls.audio = cls.root / 'test.opus'
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i',
                        'sine=frequency=440:sample_rate=48000:duration=1.317',
                        '-c:a', 'libopus', str(cls.audio)], check=True)
        if not LIBRARY.is_file(): raise AssertionError('native Opus implementation has not been built')
        cls.lib = load_library()

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    def test_matches_reference_and_preserves_sample_count(self):
        out = decode_file(self.lib, self.audio)
        ref = array.array('f')
        ref.frombytes(subprocess.check_output(['ffmpeg', '-v', 'error', '-c:a', 'libopus', '-i', str(self.audio),
                                               '-f', 'f32le', '-ac', '1', '-ar', '48000', '-']))
        self.assertEqual(len(out), len(ref), 'must handle pre-skip and end-granule trimming')
        rms = math.sqrt(sum((a-b)**2 for a,b in zip(out, ref))/len(ref))
        self.assertLess(rms, 0.0001)

    def test_saf_asset_offset_and_length_are_respected(self):
        wrapped = self.root / 'wrapped'
        raw = self.audio.read_bytes()
        wrapped.write_bytes(b'prefix' * 73 + raw + b'suffix' * 123)
        out = decode_file(self.lib, wrapped, 6*73, len(raw))
        self.assertEqual(out, decode_file(self.lib, self.audio))

    def test_shared_file_offset_is_not_changed(self):
        with self.audio.open('rb') as source:
            source.seek(23)
            handle = self.lib.vn_open(source.fileno(), 0, self.audio.stat().st_size, None)
            self.assertTrue(handle)
            try:
                self.assertEqual(source.tell(), 23)
                self.assertGreater(self.lib.vn_total(handle), 0)
            finally: self.lib.vn_close(handle)
            self.assertEqual(source.tell(), 23)
            self.assertEqual(len(source.read(1)), 1, 'caller fd must stay open')

    def test_stereo_uses_arithmetic_mono_mix(self):
        path = self.root / 'stereo.opus'
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i',
                        'aevalsrc=0.2*sin(2*PI*440*t)|0.3*sin(2*PI*660*t):s=48000:d=0.731',
                        '-c:a', 'libopus', str(path)], check=True)
        stereo = array.array('f')
        stereo.frombytes(subprocess.check_output(['ffmpeg', '-v', 'error', '-c:a', 'libopus', '-i', str(path), '-f', 'f32le', '-ac', '2', '-']))
        out = decode_file(self.lib, path)
        ref = [(a+b)*0.5 for a,b in zip(stereo[::2], stereo[1::2])]
        self.assertEqual(len(out), len(ref))
        self.assertLess(max(abs(a-b) for a,b in zip(out,ref)), 0.0001)

    def test_chained_streams_reset_preskip_per_link(self):
        path = self.root / 'chained.opus'
        raw = self.audio.read_bytes()
        # Re-encode for a distinct Ogg stream serial, as a valid chained file requires.
        second = self.root / 'second.opus'
        subprocess.run(['ffmpeg', '-v', 'error', '-c:a', 'libopus', '-i', str(self.audio), '-c:a', 'libopus', str(second)], check=True)
        path.write_bytes(raw + second.read_bytes())
        self.assertEqual(decode_file(self.lib, path), decode_file(self.lib, self.audio) + decode_file(self.lib, second))

    def test_non_opus_and_invalid_ranges_fail_cleanly(self):
        path = self.root / 'bad'
        path.write_bytes(b'not an opus file' * 100)
        with path.open('rb') as source:
            for offset,length in [(0,path.stat().st_size),(-1,100),(0,-2),(2**63-2,100)]:
                self.assertFalse(self.lib.vn_open(source.fileno(), offset, length, None))

    def test_pipe_requests_android_fallback(self):
        r,w = os.pipe()
        try:
            os.write(w, b'OggS')
            self.assertFalse(self.lib.vn_open(r,0,-1,None))
        finally: os.close(r);os.close(w)

    def test_decoder_owns_a_duplicate_descriptor(self):
        source = self.audio.open('rb')
        handle = self.lib.vn_open(source.fileno(),0,self.audio.stat().st_size,None)
        source.close()
        try:
            pcm = (C.c_float * 5760)()
            self.assertGreater(self.lib.vn_read(handle,pcm,5760),0)
            self.assertLess(self.lib.vn_read(handle,pcm,5761),0)
        finally: self.lib.vn_close(handle)

    def test_oversized_continued_tags_are_bounded_before_audio(self):
        import struct
        raw = self.audio.read_bytes()
        first_size = 27 + raw[26] + sum(raw[27:27+raw[26]])
        serial = struct.unpack_from('<I', raw, 14)[0]
        payload = b'OpusTags' + struct.pack('<I', 3*1024*1024) + b'x'*(3*1024*1024) + struct.pack('<I', 0)
        # Build valid CRC Ogg pages with a continued comment packet, not a corrupt-header rejection.
        def page(body, laces, sequence, continued):
            header = b'OggS\x00' + bytes([int(continued)]) + struct.pack('<QII', 2**64-1, serial, sequence) + b'\0'*4 + bytes([len(laces)]) + bytes(laces)
            data = bytearray(header+body); crc = 0
            for b in data:
                crc ^= b << 24
                for _ in range(8): crc = ((crc << 1) ^ (0x04c11db7 if crc & 0x80000000 else 0)) & 0xffffffff
            struct.pack_into('<I',data,22,crc)
            return data
        pages = bytearray(raw[:first_size]);offset=0;sequence=1
        while offset < len(payload):
            n=min(255*255,len(payload)-offset)
            laces=[255]*(n//255)
            if n < 255*255: laces.append(n%255)
            pages.extend(page(payload[offset:offset+n],laces,sequence,offset>0));offset+=n;sequence+=1
        path=self.root/'huge-tags.opus';path.write_bytes(pages)
        with path.open('rb') as source:
            error=C.c_int()
            handle=self.lib.vn_open(source.fileno(),0,path.stat().st_size,C.byref(error))
            if handle:self.lib.vn_close(handle)
            self.assertFalse(handle)
            self.assertEqual(error.value,-10001,'must stop on work budget, not finish allocating the oversized tags')

    def test_cancellation_during_open_is_distinct_from_corrupt_input(self):
        check_type=C.CFUNCTYPE(C.c_int,C.c_void_p)
        calls=0
        def cancelled(_):
            nonlocal calls
            calls+=1
            return int(calls >= 3)
        callback=check_type(cancelled)
        self.lib.vn_open_controlled.argtypes=[C.c_int,C.c_longlong,C.c_longlong,C.POINTER(C.c_int),check_type,C.c_void_p]
        self.lib.vn_open_controlled.restype=C.c_void_p
        with self.audio.open('rb') as source:
            error=C.c_int()
            handle=self.lib.vn_open_controlled(source.fileno(),0,self.audio.stat().st_size,C.byref(error),callback,None)
            if handle:self.lib.vn_close(handle)
            self.assertFalse(handle)
            self.assertEqual(-10002,error.value)
            self.assertGreaterEqual(calls,3)

    def test_cancellation_is_checked_inside_native_read(self):
        check_type=C.CFUNCTYPE(C.c_int,C.c_void_p)
        cancelled=False
        callback=check_type(lambda _:int(cancelled))
        self.lib.vn_open_controlled.argtypes=[C.c_int,C.c_longlong,C.c_longlong,C.POINTER(C.c_int),check_type,C.c_void_p]
        self.lib.vn_open_controlled.restype=C.c_void_p
        with self.audio.open('rb') as source:
            handle=self.lib.vn_open_controlled(source.fileno(),0,self.audio.stat().st_size,None,callback,None)
        self.assertTrue(handle)
        try:
            cancelled=True
            pcm=(C.c_float*5760)()
            self.assertEqual(-10002,self.lib.vn_read(handle,pcm,5760))
        finally:self.lib.vn_close(handle)

    def test_large_non_opus_file_remains_eligible_for_android_fallback(self):
        import struct
        path=self.root/'large.wav'
        length=48_000*2*14*60
        header=b'RIFF'+struct.pack('<I',36+length)+b'WAVEfmt '+struct.pack('<IHHIIHH',16,1,1,48000,96000,2,16)+b'data'+struct.pack('<I',length)
        with path.open('wb') as out:
            out.write(header);out.truncate(44+length)
        with path.open('rb') as source:
            error=C.c_int()
            handle=self.lib.vn_open(source.fileno(),0,path.stat().st_size,C.byref(error))
            if handle:self.lib.vn_close(handle)
            self.assertFalse(handle)
            self.assertNotEqual(-10001,error.value,'non-Opus must reach Android fallback regardless of byte size')

    def test_repeated_invalid_open_does_not_leak_descriptors(self):
        path = self.root / 'bad2';path.write_bytes(b'not opus')
        before = len(os.listdir('/proc/self/fd'))
        with path.open('rb') as source:
            for _ in range(100): self.assertFalse(self.lib.vn_open(source.fileno(),0,8,None))
        self.assertEqual(before,len(os.listdir('/proc/self/fd')))


if __name__ == '__main__': unittest.main()

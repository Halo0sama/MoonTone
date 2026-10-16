#!/usr/bin/env python3
"""
MoonTone mic uplink receiver.

Protocol (MoonTone uplink MVP):
  [magic "MTUP" 4B][seq BE 4B][timestamp ms BE 8B][ssrc BE 4B][Opus frame]

Features:
  - validates magic
  - keeps a 3-packet jitter buffer before decoding
  - decodes Opus (48k mono, 10ms) with opuslib if installed
  - writes decoded PCM to WAV; optionally plays with sounddevice
"""
import argparse
import collections
import socket
import struct
import time

MAGIC = b"MTUP"
HEADER_SIZE = 20
SAMPLE_RATE = 48000
FRAME_SIZE = 480  # 10 ms


class PacketJitterBuffer:
    """Bounded reordering/PLC for unsigned 32-bit sequence numbers."""
    MASK = 0xFFFFFFFF

    def __init__(self, prebuffer=3, max_gap=50):
        if not 1 <= prebuffer <= 64 or not prebuffer <= max_gap <= 1000:
            raise ValueError("invalid jitter buffer bounds")
        self.prebuffer = prebuffer
        self.max_gap = max_gap
        self.pending = {}
        self.expected = None
        self.started = False
        self.lost = 0
        self.duplicates_or_late = 0
        self.resyncs = 0

    def reset_session(self):
        self.pending.clear()
        self.expected = None
        self.started = False
        self.resyncs += 1

    def push(self, sequence, payload):
        sequence &= self.MASK
        if self.expected is None:
            self.expected = sequence
        if sequence in self.pending:
            self.duplicates_or_late += 1
            return False
        distance = (sequence - self.expected) & self.MASK
        if distance >= 0x80000000:
            behind = (self.expected - sequence) & self.MASK
            if not self.started and behind <= self.max_gap:
                self.expected = sequence
            else:
                self.duplicates_or_late += 1
                return False
        elif distance > self.max_gap:
            # A restarted sender or corrupt jump must not allocate/loop billions
            # of concealment frames. Resume at the new sequence instead.
            self.pending.clear()
            self.expected = sequence
            self.started = False
            self.resyncs += 1
        if len(self.pending) >= 64:
            self.pending.clear()
            self.expected = sequence
            self.started = False
            self.resyncs += 1
        self.pending[sequence] = payload
        return True

    def pop(self, flush=False):
        if not self.pending or (not flush and len(self.pending) < self.prebuffer):
            return None
        self.started = True
        sequence = self.expected
        payload = self.pending.pop(sequence, None)
        self.expected = (sequence + 1) & self.MASK
        if payload is None:
            self.lost += 1
        return sequence, payload


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=48100)
    ap.add_argument("--out", default="/tmp/moontone_mic.wav")
    ap.add_argument("--duration", type=float, default=30)
    ap.add_argument("--prebuffer", type=int, default=3)
    ap.add_argument("--play", action="store_true", help="try sounddevice playback")
    args = ap.parse_args()
    if not 1 <= args.port <= 65535 or args.duration <= 0 or not 1 <= args.prebuffer <= 64:
        ap.error("port must be 1..65535, duration positive, and prebuffer 1..64")

    try:
        import opuslib
    except ImportError:
        opuslib = None
    player = None
    wav = None
    decoder = None
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    packets = bytes_total = 0
    jitter = PacketJitterBuffer(args.prebuffer, max(50, args.prebuffer))
    sender = None
    retired_senders = collections.deque(maxlen=8)
    start = time.monotonic()

    def decode(payload):
        if decoder is None:
            return
        try:
            pcm = decoder.decode(payload if payload is not None else b"", FRAME_SIZE)
        except Exception:
            pcm = b"\x00\x00" * FRAME_SIZE
        wav.writeframesraw(pcm)
        if player:
            player.write(pcm)

    try:
        sock.bind(("0.0.0.0", args.port))
        sock.settimeout(min(1.0, args.duration))
        if opuslib is not None:
            import wave
            decoder = opuslib.Decoder(SAMPLE_RATE, 1)
            wav = wave.open(args.out, "wb")
            wav.setparams((1, 2, SAMPLE_RATE, 0, "NONE", "not compressed"))
            print(f"Opus decoder ready, saving to {args.out}")
            if args.play:
                try:
                    import sounddevice as sd
                    player = sd.RawOutputStream(samplerate=SAMPLE_RATE, channels=1, dtype="int16")
                    player.start()
                except Exception as e:
                    print(f"sounddevice unavailable ({e}); WAV only")
                    if player:
                        player.close()
                    player = None
        else:
            print("opuslib not installed; only counting packets (pip install opuslib)")
        while time.monotonic() - start < args.duration:
            try:
                data, addr = sock.recvfrom(2000)
            except socket.timeout:
                continue
            if len(data) <= HEADER_SIZE or data[:4] != MAGIC:
                continue
            seq, ts, ssrc = struct.unpack(">IQI", data[4:20])
            identity = (addr[0], ssrc)
            if sender is None:
                sender = identity
            if identity != sender:
                if addr[0] != sender[0] or identity in retired_senders:
                    continue
                # A phone starts with a new SSRC (and UDP source port) after
                # toggling the microphone. Keep the receiver usable across it.
                while (item := jitter.pop(flush=True)) is not None:
                    decode(item[1])
                retired_senders.append(sender)
                sender = identity
                jitter.reset_session()
                if decoder is not None:
                    decoder = opuslib.Decoder(SAMPLE_RATE, 1)
            if not jitter.push(seq, data[HEADER_SIZE:]):
                continue
            packets += 1
            bytes_total += len(data) - HEADER_SIZE
            while (item := jitter.pop()) is not None:
                decode(item[1])
            if packets % 200 == 0:
                dt = time.monotonic() - start
                print(f"{packets} pkts, {bytes_total * 8 / max(dt, 0.001) / 1000:.1f} kbps, lost={jitter.lost}")
    except KeyboardInterrupt:
        pass
    finally:
        sock.close()
        while (item := jitter.pop(flush=True)) is not None:
            decode(item[1])
        if wav:
            wav.close()
            print(f"WAV saved: {args.out}")
        if player:
            player.stop()
            player.close()
    dt = time.monotonic() - start
    print(f"Done: {packets} packets, {bytes_total} bytes, "
          f"{bytes_total * 8 / max(dt, 0.001) / 1000:.1f} kbps, lost={jitter.lost}, "
          f"late/duplicates={jitter.duplicates_or_late}, resyncs={jitter.resyncs}")


if __name__ == "__main__":
    main()

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
import sys
import time

MAGIC = b"MTUP"
HEADER_SIZE = 20
SAMPLE_RATE = 48000
FRAME_SIZE = 480  # 10 ms


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=48100)
    ap.add_argument("--out", default="/tmp/moontone_mic.wav")
    ap.add_argument("--duration", type=float, default=30)
    ap.add_argument("--prebuffer", type=int, default=3)
    ap.add_argument("--play", action="store_true", help="try sounddevice playback")
    args = ap.parse_args()

    try:
        import opuslib
        HAVE_OPUS = True
    except Exception:
        HAVE_OPUS = False

    player = None
    wav = None
    decoder = None
    if HAVE_OPUS:
        decoder = opuslib.Decoder(SAMPLE_RATE, 1)
        wav = open(args.out, "wb")
        # write WAV header placeholder; fill at end
        wav.write(b"\0" * 44)
        print(f"Opus decoder ready, saving to {args.out}")
        if args.play:
            try:
                import sounddevice as sd
                player = sd.RawOutputStream(
                    samplerate=SAMPLE_RATE, channels=1, dtype="int16"
                )
                player.start()
                print("sounddevice output started")
            except Exception as e:
                print(f"sounddevice unavailable ({e}); WAV only")
                player = None
    else:
        print("opuslib not installed; only counting packets (pip install opuslib)")

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", args.port))
    sock.settimeout(1)

    jitter = collections.deque(maxlen=64)
    packets = 0
    bytes_total = 0
    lost = 0
    last_seq = -1
    expected_seq = -1
    start = time.time()
    pcm_total = b""

    try:
        while time.time() - start < args.duration:
            try:
                data, addr = sock.recvfrom(2000)
            except socket.timeout:
                continue
            if len(data) < HEADER_SIZE or data[:4] != MAGIC:
                continue
            seq, ts, ssrc = struct.unpack(">IQI", data[4:20])
            opus = data[HEADER_SIZE:]
            packets += 1
            bytes_total += len(opus)
            if last_seq >= 0 and seq != (last_seq + 1) & 0xFFFFFFFF:
                lost += (seq - last_seq - 1) & 0xFFFFFFFF
            last_seq = seq
            jitter.append((seq, opus))

            # Pop and decode once prebuffer is reached, in seq order
            while len(jitter) >= args.prebuffer:
                item_seq, item_opus = jitter.popleft()
                # PLC: fill any missing packets with silence (or opuslib PLC if supported)
                if expected_seq >= 0 and item_seq != expected_seq:
                    missing = (item_seq - expected_seq) & 0xFFFFFFFF
                    for _ in range(missing):
                        if HAVE_OPUS:
                            try:
                                plc = decoder.decode(b"", FRAME_SIZE)
                            except Exception:
                                plc = b"\x00\x00" * FRAME_SIZE
                        else:
                            plc = b"\x00\x00" * FRAME_SIZE
                        pcm_total += plc
                        if player:
                            player.write(plc)
                expected_seq = (item_seq + 1) & 0xFFFFFFFF
                if HAVE_OPUS:
                    try:
                        pcm = decoder.decode(item_opus, FRAME_SIZE)
                        pcm_total += pcm
                        if player:
                            player.write(pcm)
                    except Exception as e:
                        print("decode error:", e)

            if packets % 200 == 0:
                dt = time.time() - start
                kbps = bytes_total * 8 / dt / 1000 if dt > 0 else 0
                print(f"{packets} pkts, {kbps:.1f} kbps, lost={lost}")
    except KeyboardInterrupt:
        pass
    finally:
        sock.close()
        if wav:
            # Write WAV header
            data_len = len(pcm_total)
            wav.seek(0)
            wav.write(b"RIFF")
            wav.write(struct.pack("<I", 36 + data_len))
            wav.write(b"WAVEfmt ")
            wav.write(struct.pack("<IHHIIHH", 16, 1, 1, SAMPLE_RATE, SAMPLE_RATE * 2, 2, 16))
            wav.write(b"data")
            wav.write(struct.pack("<I", data_len))
            wav.write(pcm_total)
            wav.close()
            print(f"WAV saved: {args.out}")
        if player:
            player.stop()

    dt = time.time() - start
    print(f"Done: {packets} packets, {bytes_total} bytes, "
          f"{bytes_total * 8 / max(dt, 0.001) / 1000:.1f} kbps, lost={lost}")


if __name__ == "__main__":
    main()

package com.limelight.binding.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.os.Process;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

import java.util.ArrayDeque;

/**
 * Moonlight-Android's stock audio renderer with a MoonTone jitter buffer.
 *
 * Upstream feeds AudioTrack directly from the decode callback, so the buffer
 * against network gaps is only whatever clock drift happens to accumulate
 * (measured 25-135ms on a campus WiFi network with 100-500ms airtime bursts),
 * which produces constant audible stutter no matter how high the drop
 * threshold is set — the threshold caps the queue but nothing ever FILLS it.
 *
 * This renderer adds a proper pre-roll jitter buffer:
 *  - decoded PCM is queued, and a writer thread only starts playback after
 *    the queue holds targetBufferMs of audio;
 *  - after a stall drains the queue, it re-primes with a shorter depth
 *    (RE_PRIME_MS) so playback resumes stably instead of ticking;
 *  - targetBufferMs is driven by AdaptiveLatencyController (40-600ms);
 *  - retains local mute and reconnect-safe null guards (MoonTone reuses the
 *    renderer across sessions, upstream assumes a one-shot lifecycle).
 */
public class AndroidAudioRenderer implements AudioRenderer {

    private final Context context;
    private final boolean enableAudioFx;

    private AudioTrack track;

    // ── Jitter buffer state ─────────────────────────────────
    private final Object queueLock = new Object();
    private final ArrayDeque<short[]> queue = new ArrayDeque<>();
    private long queuedFrames = 0;
    private int sampleRateHz = 48000;
    private int channelCount = 2;
    private volatile boolean running = false;
    private volatile boolean primed = false;
    private Thread writerThread;

    // Coverage target in ms (AdaptiveLatencyController, 40-600).
    private volatile int targetBufferMs = 600;
    // Depth to accumulate before (re)starting playback. Full target on session
    // start; a shorter depth after a stall so audio returns quickly.
    private volatile int primeDepthMs = 600;
    // Re-prime depth after a stall: shorter so audio returns quickly.
    private static final int RE_PRIME_MS = 200;
    // Absolute queue bound (memory safety) — 1s of 48kHz stereo 16-bit ≈ 190KB.
    private static final int HARD_MAX_BUFFER_MS = 1000;

    // Local mute: keeps consuming/decoding audio but silences the AudioTrack.
    private volatile boolean muted = false;

    public AndroidAudioRenderer(Context context, boolean enableAudioFx) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
    }

    public void setPendingThresholdMs(int ms) {
        this.targetBufferMs = ms;
    }

    /** Current jitter-buffer depth in ms (real gap coverage). */
    public int getQueuedMs() {
        synchronized (queueLock) {
            return (int) (queuedFrames * 1000L / sampleRateHz);
        }
    }

    /** Frames dropped by the queue hard cap since the last call. */
    public int getAndResetDropCount() {
        synchronized (queueLock) {
            int v = (int) dropCount;
            dropCount = 0;
            return v;
        }
    }

    /** Returns AudioTrack underruns + stall events since the last call. */
    public int getAndResetUnderrunCount() {
        long stalls;
        synchronized (queueLock) {
            stalls = stallCount;
            stallCount = 0;
        }
        long trackUnderruns = 0;
        if (track != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            long current = track.getUnderrunCount();
            trackUnderruns = current - lastUnderrunTotal;
            lastUnderrunTotal = current;
        }
        return (int) (stalls + trackUnderruns);
    }

    private long stallCount = 0;
    private long dropCount = 0;
    private long lastUnderrunTotal = 0;

    public void setMuted(boolean muted) {
        this.muted = muted;
        if (track != null) {
            track.setVolume(muted ? 0f : 1f);
        }
    }

    private AudioTrack createAudioTrack(int channelConfig, int sampleRate, int bufferSize, boolean lowLatency) {
        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME);
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Use FLAG_LOW_LATENCY on L through N
            if (lowLatency) {
                attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                    .setAudioFormat(format)
                    .setAudioAttributes(attributesBuilder.build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize);

            // Use PERFORMANCE_MODE_LOW_LATENCY on O and later
            if (lowLatency) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }

            return trackBuilder.build();
        }
        else {
            return new AudioTrack(attributesBuilder.build(),
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        }
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        int channelConfig;
        int bytesPerFrame;

        switch (audioConfiguration.channelCount)
        {
            case 2:
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                break;
            case 4:
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
                break;
            case 6:
                channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                break;
            case 8:
                // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND isn't available until Android 6.0,
                // yet the CHANNEL_OUT_SIDE_LEFT and CHANNEL_OUT_SIDE_RIGHT constants were added
                // in 5.0, so just hardcode the constant so we can work on Lollipop.
                channelConfig = 0x000018fc; // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                break;
            default:
                LimeLog.severe("Decoder returned unhandled channel count");
                return -1;
        }

        LimeLog.info("Audio channel config: "+String.format("0x%X", channelConfig));

        channelCount = audioConfiguration.channelCount;
        sampleRateHz = sampleRate;
        bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2;

        // We're not supposed to request less than the minimum
        // buffer size for our buffer, but it appears that we can
        // do this on many devices and it lowers audio latency.
        // We'll try the small buffer size first and if it fails,
        // use the recommended larger buffer size.

        for (int i = 0; i < 4; i++) {
            boolean lowLatency;
            int bufferSize;

            // We will try:
            // 1) Small buffer, low latency mode
            // 2) Large buffer, low latency mode
            // 3) Small buffer, standard mode
            // 4) Large buffer, standard mode

            switch (i) {
                case 0:
                case 1:
                    lowLatency = true;
                    break;
                case 2:
                case 3:
                    lowLatency = false;
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            switch (i) {
                case 0:
                case 2:
                    // Upstream uses bytesPerFrame * 2 (~10 ms), which leaves no
                    // scheduling slack; *4 keeps latency low while stable.
                    bufferSize = bytesPerFrame * 4;
                    break;

                case 1:
                case 3:
                    // Try the larger buffer size (~40 ms)
                    bufferSize = Math.max(AudioTrack.getMinBufferSize(sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT),
                            bytesPerFrame * 8);

                    // Round to next frame
                    bufferSize = (((bufferSize + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame);
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            // Skip low latency options if hardware sample rate doesn't match the content
            if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate && lowLatency) {
                continue;
            }

            // Skip low latency options when using audio effects, since low latency mode
            // precludes the use of the audio effect pipeline (as of Android 13).
            if (enableAudioFx && lowLatency) {
                continue;
            }

            try {
                track = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency);
                track.play();

                // Successfully created working AudioTrack. We're done here.
                LimeLog.info("Audio track configuration: "+bufferSize+" "+lowLatency);
                break;
            } catch (Exception e) {
                // Try to release the AudioTrack if we got far enough
                e.printStackTrace();
                try {
                    if (track != null) {
                        track.release();
                        track = null;
                    }
                } catch (Exception ignored) {}
            }
        }

        if (track == null) {
            // Couldn't create any audio track for playback
            return -2;
        }

        track.setVolume(muted ? 0f : 1f);
        lastUnderrunTotal = 0;

        // Start the jitter-buffer writer (pre-rolls before first write).
        synchronized (queueLock) {
            queue.clear();
            queuedFrames = 0;
            primed = false;
            primeDepthMs = targetBufferMs;
            stallCount = 0;
        }
        startWriter();

        return 0;
    }

    private void startWriter() {
        // Defensive: never allow two writer threads to interleave writes on
        // the same AudioTrack (that also manifests as loud static).
        if (writerThread != null && writerThread.isAlive()) {
            running = false;
            try { writerThread.join(500); } catch (InterruptedException ignored) {}
        }
        running = true;
        writerThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            LimeLog.info("Jitter buffer writer started, target=" + targetBufferMs + " ms");
            while (running) {
                short[] item;
                synchronized (queueLock) {
                    double depthMs = queuedFrames * 1000.0 / sampleRateHz;
                    if (!primed && depthMs < primeDepthMs) {
                        // Pre-roll (initial or after a stall): keep collecting.
                        try { queueLock.wait(20); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    primed = true;
                    item = queue.pollFirst();
                    if (item == null) {
                        // Queue ran dry while playing: stall. Re-prime before
                        // resuming so we don't tick frame-by-frame.
                        primed = false;
                        primeDepthMs = Math.min(targetBufferMs, RE_PRIME_MS);
                        stallCount++;
                        LimeLog.info("Audio jitter buffer starved; re-priming to " + primeDepthMs + " ms");
                        try { queueLock.wait(20); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    queuedFrames -= item.length / channelCount;
                }
                track.write(item, 0, item.length);
            }
        }, "MoonTone-AudioWriter");
        writerThread.start();
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        if (!running || track == null) {
            // The renderer can be cleaned up while a reconnect is in progress;
            // ignore any audio that arrives after the track has been released.
            return;
        }

        synchronized (queueLock) {
            // Hard memory bound only. The depth is real gap coverage: late
            // burst packets refill it for free, so never tie this cap to the
            // (decaying) adaptive threshold.
            if (queuedFrames * 1000.0 / sampleRateHz > HARD_MAX_BUFFER_MS) {
                dropCount++;
                LimeLog.info("Jitter buffer full, dropping frame");
                return;
            }
            // IMPORTANT: moonlight-common-c decodes into ONE reused global
            // jshortArray (DecodedAudioBuffer in callbacks.c) and hands the
            // same array to every callback. Deferring the AudioTrack.write
            // therefore requires our own copy, or every queued frame reads
            // whatever the decoder wrote most recently (harsh static).
            queue.add(audioData.clone());
            queuedFrames += audioData.length / channelCount;
            queueLock.notifyAll();
        }
    }

    @Override
    public void start() {
        if (enableAudioFx && track != null) {
            // Open an audio effect control session to allow equalizers to apply audio effects
            Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME);
            context.sendBroadcast(i);
        }
    }

    @Override
    public void stop() {
        if (enableAudioFx && track != null) {
            // Close our audio effect control session when we're stopping
            Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME);
            context.sendBroadcast(i);
        }
    }

    @Override
    public void cleanup() {
        running = false;
        synchronized (queueLock) {
            queue.clear();
            queuedFrames = 0;
            queueLock.notifyAll();
        }
        try {
            if (writerThread != null) {
                writerThread.join(500);
            }
        } catch (InterruptedException ignored) {}
        writerThread = null;

        if (track == null) {
            return;
        }

        // Immediately drop all pending data
        track.pause();
        track.flush();

        track.release();
        track = null;
    }
}

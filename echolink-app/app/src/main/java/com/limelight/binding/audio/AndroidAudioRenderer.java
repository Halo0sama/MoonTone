package com.limelight.binding.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.os.Build;

import com.halo.moontone.log.MoonToneLog;
import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

public class AndroidAudioRenderer implements AudioRenderer {

    private final Context context;
    private final boolean enableAudioFx;

    private AudioTrack track;

    // Adaptive audio control (updated by AdaptiveAudioController)
    private volatile int pendingThresholdMs = 80;
    private long lastUnderrunCount = 0;

    // Local mute: keeps consuming/decoding audio but silences the AudioTrack.
    private volatile boolean muted = false;

    // Environment/jitter metrics observed from the decoded-audio delivery cadence.
    private volatile long lastFrameTimeNanos = 0;
    private volatile double jitterMs = 0;
    private volatile long gapEventCount = 0;
    private volatile double expectedFrameMs = 5;

    public AndroidAudioRenderer(Context context, boolean enableAudioFx) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
    }

    public void setPendingThresholdMs(int ms) {
        this.pendingThresholdMs = ms;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        if (track != null) {
            track.setVolume(muted ? 0f : 1f);
        }
    }

    public double getJitterMs() {
        return jitterMs;
    }

    public long getAndResetGapEventCount() {
        long v = gapEventCount;
        gapEventCount = 0;
        return v;
    }

    public int getPendingThresholdMs() {
        return pendingThresholdMs;
    }

    /** Returns how many AudioTrack underruns happened since the last call. */
    public long getAndResetUnderrunCount() {
        if (track == null) return 0;
        long current = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            current = track.getUnderrunCount();
        }
        long delta = current - lastUnderrunCount;
        lastUnderrunCount = current;
        return delta;
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
        expectedFrameMs = samplesPerFrame * 1000.0 / sampleRate;
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
                    // ~40ms of audio: small enough for low latency, large enough
                    // to survive WiFi jitter without stuttering.
                    bufferSize = bytesPerFrame * 4;
                    break;

                case 1:
                case 3:
                    // Try the larger buffer size (~80ms)
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
                track.setVolume(muted ? 0f : 1f);

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
            MoonToneLog.INSTANCE.e("AudioTrack", "all buffer size/latency combinations failed");
            return -2;
        }

        MoonToneLog.INSTANCE.i("AudioTrack",
                "created: channels=" + audioConfiguration.channelCount +
                " rate=" + sampleRate + " framesPerPacket=" + samplesPerFrame);
        return 0;
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        // Measure delivery cadence: how far each decoded frame is from the
        // expected 5ms/10ms interval. This becomes the live "jitter" signal
        // used by AdaptiveAudioController.
        long now = System.nanoTime();
        if (lastFrameTimeNanos != 0) {
            long intervalMs = (now - lastFrameTimeNanos) / 1000000L;
            double diff = Math.abs(intervalMs - expectedFrameMs);
            if (intervalMs - expectedFrameMs > 15) {
                gapEventCount++;
            }
            jitterMs = jitterMs == 0 ? diff : jitterMs * 0.9 + diff * 0.1;
        }
        lastFrameTimeNanos = now;

        // The renderer can be cleaned up while a reconnect is in progress;
        // ignore any audio that arrives after the track has been released.
        if (track == null) {
            return;
        }

        // Adaptive threshold controlled by AdaptiveAudioController. The native
        // queue often settles at 80-90ms on WiFi due to small clock drift;
        // raising the threshold avoids stutter, lowering it reduces latency.
        if (MoonBridge.getPendingAudioDuration() < pendingThresholdMs) {
            // This will block until the write is completed. That can cause a backlog
            // of pending audio data, so we do the above check to be able to bound
            // latency at 120 ms in that situation.
            track.write(audioData, 0, audioData.length);
        }
        else {
            LimeLog.info("Too much pending audio data: " + MoonBridge.getPendingAudioDuration() +" ms");
            MoonToneLog.INSTANCE.w("AudioTrack", "too much pending audio: " + MoonBridge.getPendingAudioDuration() + " ms, dropping frame");
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
            context.sendBroadcast(i);
        }
    }

    @Override
    public void cleanup() {
        // Immediately drop all pending data
        if (track != null) {
            track.pause();
            track.flush();
            track.release();
            track = null;
        }
    }
}


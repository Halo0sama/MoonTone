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

import com.halo.moontone.audio.AdaptivePcmBuffer;
import com.halo.moontone.audio.PlaybackBufferTuner;

/** Direct playback with an adaptive, time-bounded decoded PCM queue. */
public class AndroidAudioRenderer implements AudioRenderer {

    private final Context context;
    private final boolean enableAudioFx;

    private volatile AudioTrack track;
    private final Object queueLock = new Object();
    private volatile AdaptivePcmBuffer pcmBuffer = new AdaptivePcmBuffer(48000, 2);
    private volatile boolean running;
    private Thread writerThread;
    private long lastUnderrunTotal;

    // Local mute: keeps consuming/decoding audio but silences the AudioTrack.
    private volatile boolean muted = false;

    public AndroidAudioRenderer(Context context, boolean enableAudioFx) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
    }

    public int getTargetBufferMs() { return pcmBuffer.getTargetMs(); }
    public int getQueuedMs() { return pcmBuffer.getQueuedMs(); }
    public int getAndResetDropCount() { return pcmBuffer.takeDropCount(); }

    public int getAndResetUnderrunCount() {
        int stalls = pcmBuffer.takeStallCount();
        synchronized (queueLock) {
            if (track != null) {
                long current = track.getUnderrunCount();
                stalls += (int) Math.max(0, current - lastUnderrunTotal);
                lastUnderrunTotal = current;
            }
        }
        return stalls;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        synchronized (queueLock) {
            if (track != null) track.setVolume(muted ? 0f : 1f);
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
        cleanup();
        int channelConfig;
        int bytesPerFrame;
        PlaybackBufferTuner tuner = null;

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

        pcmBuffer = new AdaptivePcmBuffer(sampleRate, audioConfiguration.channelCount);
        bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2;

        // Reserve recovery capacity. PlaybackBufferTuner limits effective
        // write-ahead independently, so capacity does not imply 120ms latency.

        for (int i = 0; i < 2; i++) {
            boolean lowLatency = i == 0;
            int bufferSize = Math.max(AudioTrack.getMinBufferSize(sampleRate, channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT), sampleRate * audioConfiguration.channelCount * 2 * 120 / 1000);
            bufferSize = ((bufferSize + bytesPerFrame - 1) / bytesPerFrame) * bytesPerFrame;

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
                tuner = new PlaybackBufferTuner(track, sampleRate);
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

        startWriter(track, pcmBuffer, tuner);
        return 0;
    }

    private void startWriter(final AudioTrack sessionTrack, final AdaptivePcmBuffer buffer, final PlaybackBufferTuner tuner) {
        running = true;
        writerThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            try {
                while (running && track == sessionTrack) {
                    short[] item = buffer.poll();
                    if (item == null) {
                        Thread.sleep(2);
                        continue;
                    }
                    int written = 0;
                    while (written < item.length && running && track == sessionTrack) {
                        int count = sessionTrack.write(item, written, item.length - written);
                        if (count <= 0) throw new IllegalStateException("AudioTrack write failed: " + count);
                        written += count;
                        tuner.onWrite();
                    }
                }
            } catch (InterruptedException ignored) {
                // Normal shutdown while waiting for decoded audio.
            } catch (IllegalStateException e) {
                if (running && track == sessionTrack) LimeLog.severe("Audio writer failed: " + e);
            }
        }, "MoonTone-AudioWriter");
        writerThread.start();
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        synchronized (queueLock) {
            if (running && track != null) pcmBuffer.offer(audioData);
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
        final AudioTrack oldTrack;
        synchronized (queueLock) {
            running = false;
            oldTrack = track;
            track = null;
        }
        // Wake a blocking write before joining its owner thread.
        if (oldTrack != null) {
            try { oldTrack.pause(); oldTrack.flush(); } catch (IllegalStateException ignored) {}
        }
        if (writerThread != null) {
            writerThread.interrupt();
            try { writerThread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            writerThread = null;
        }
        if (oldTrack != null) oldTrack.release();
    }
}

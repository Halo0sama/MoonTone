package com.limelight.nvstream.av.video;

// Dummy video decoder for audio-only mode
public interface VideoDecoderRenderer {
    int setup(int videoFormat, int width, int height, int redrawRate);
    void start();
    void stop();
    void cleanup();
    int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                         int frameNumber, int frameType, char frameHostProcessingLatency,
                         long receiveTimeMs, long enqueueTimeMs);
}

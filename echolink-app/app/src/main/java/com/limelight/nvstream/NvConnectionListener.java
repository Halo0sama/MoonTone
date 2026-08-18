package com.limelight.nvstream;

// Minimal listener for audio-only streaming
public interface NvConnectionListener {
    void stageStarting(String stage);
    void stageComplete(String stage);
    void stageFailed(String stage, int portFlags, int errorCode);
    void connectionStarted();
    void connectionTerminated(int errorCode);
    void connectionStatusUpdate(int connectionStatus);
    void displayMessage(String message);
    void displayTransientMessage(String message);
    void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor);
    void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger);
    void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz);
    void setControllerLED(short controllerNumber, byte r, byte g, byte b);
    void setHdrMode(boolean enabled, byte[] hdrMetadata);
}

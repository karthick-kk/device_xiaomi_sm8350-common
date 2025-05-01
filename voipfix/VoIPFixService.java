/*
 * Copyright (C) 2023 The PixelOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pixelexperience.xiaomi.voipfix;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;

/**
 * VoIPFixService - automatically triggers volume adjustments during VoIP calls
 * to resolve muted audio issues on Xiaomi SM8350 devices
 */
public class VoIPFixService extends Service {
    private static final String TAG = "VoIPFixService";
    private static final boolean DEBUG = true;

    private AudioManager mAudioManager;
    private TelephonyManager mTelephonyManager;
    private Handler mHandler;
    
    // Track active VoIP state
    private boolean mVoIPCallActive = false;
    private boolean mSpeakerActive = false;
    private boolean mIsFixApplied = false;
    private long mLastSpeakerChange = 0;
    private boolean mPendingSpeakerFix = false;
    
    // Add tracking for new call setup
    private boolean mIsNewCallSetup = false;
    private long mCallStartTime = 0;
    
    // Add tracking for media playback and proximity
    private boolean mMediaPlaybackActive = false;
    private SensorManager mSensorManager;
    private Sensor mProximitySensor;
    private boolean mProximityNear = false;
    private boolean mLastProximityNear = false;
    
    // Track permission state for injecting key events
    private boolean mHaveWakePermission = false;

    // Add proximity sensor listener
    private final SensorEventListener mProximitySensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
                mProximityNear = event.values[0] < event.sensor.getMaximumRange();
                
                // Only handle transitions
                if (mProximityNear != mLastProximityNear) {
                    log("Proximity changed to: " + (mProximityNear ? "near" : "far"));
                    mLastProximityNear = mProximityNear;
                    
                    // If media is playing, handle the proximity change
                    if (mMediaPlaybackActive) {
                        handleMediaProximityChange(mProximityNear);
                    }
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Not needed
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            
            if (AudioManager.STREAM_DEVICES_CHANGED_ACTION.equals(action)) {
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                
                // When any audio routing changes during a VoIP call, prepare to apply fix
                if (mVoIPCallActive) {
                    boolean currentSpeakerState = mAudioManager.isSpeakerphoneOn();
                    
                    // Check if speaker state has changed
                    if (mSpeakerActive != currentSpeakerState) {
                        mSpeakerActive = currentSpeakerState;
                        log("Speaker mode changed to: " + mSpeakerActive);
                        
                        // Set flag for pending speaker fix
                        mPendingSpeakerFix = true;
                        mLastSpeakerChange = System.currentTimeMillis();
                        mIsFixApplied = false;
                        
                        // Schedule multiple fix attempts to ensure it catches
                        scheduleMultipleFixes();
                    }
                }
            } else if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) {
                // Check for incoming call state changes
                String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
                    // Call was just answered
                    mVoIPCallActive = true;
                    mIsNewCallSetup = true;
                    mCallStartTime = System.currentTimeMillis();
                    mSpeakerActive = mAudioManager.isSpeakerphoneOn();
                    log("Call is active, initializing audio path for call setup");
                    
                    // Apply immediate fixes for initial call setup
                    initializeCallAudioPath();
                    
                    // Schedule multiple fixes during call setup to ensure audio initializes
                    scheduleInitialCallFixes();
                } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                    // Call ended
                    mVoIPCallActive = false;
                    mIsNewCallSetup = false;
                    mIsFixApplied = false;
                    mPendingSpeakerFix = false;
                    log("Call ended, resetting VoIP fix state");
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        log("VoIPFix Service starting");
        
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mTelephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        mHandler = new Handler(Looper.getMainLooper());
        
        // Check and try to acquire wake lock permission
        mHaveWakePermission = checkCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK) 
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        log("Have WAKE_LOCK permission: " + mHaveWakePermission);
        
        // Initialize sensor manager and proximity sensor
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mProximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        
        // Register proximity sensor
        if (mProximitySensor != null) {
            mSensorManager.registerListener(mProximitySensorListener, 
                    mProximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            log("Proximity sensor registered");
        } else {
            log("Proximity sensor not available");
        }
        
        // Register for broadcasts related to call state and audio routing changes
        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction(AudioManager.STREAM_DEVICES_CHANGED_ACTION);
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        filter.addAction(AudioManager.STREAM_MUTE_CHANGED_ACTION);
        // Add action for detecting media playback
        filter.addAction("android.intent.action.MEDIA_BUTTON");
        filter.addAction("android.media.VOLUME_CHANGED_ACTION");
        registerReceiver(mReceiver, filter);
        
        // Start a background monitoring task to detect VoIP streams, speaker changes, and media playback
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkVoIPAndSpeakerState();
                checkMediaPlaybackState();
                mHandler.postDelayed(this, 500); // Check every 500ms
            }
        }, 500);
    }
    
    private void checkVoIPAndSpeakerState() {
        // Check audio mode to detect VoIP calls
        int mode = mAudioManager.getMode();
        if (mode == AudioManager.MODE_IN_COMMUNICATION) {
            if (!mVoIPCallActive) {
                log("VoIP activity detected via audio mode");
                mVoIPCallActive = true;
                mIsNewCallSetup = true;
                mCallStartTime = System.currentTimeMillis();
                mSpeakerActive = mAudioManager.isSpeakerphoneOn();
                
                // Initialize audio path for new calls
                initializeCallAudioPath();
                
                // Schedule fixes for call setup
                scheduleInitialCallFixes();
            } else {
                // During active call, continuously check speaker state
                boolean currentSpeakerState = mAudioManager.isSpeakerphoneOn();
                if (mSpeakerActive != currentSpeakerState) {
                    log("Speaker change detected in polling: " + currentSpeakerState);
                    mSpeakerActive = currentSpeakerState;
                    mIsFixApplied = false;
                    mPendingSpeakerFix = true;
                    mLastSpeakerChange = System.currentTimeMillis();
                    scheduleMultipleFixes();
                }
                
                // If we have a pending speaker fix and enough time has passed, apply it
                if (mPendingSpeakerFix && 
                    System.currentTimeMillis() - mLastSpeakerChange > 300 &&
                    !mIsFixApplied) {
                    applyVolumeButtonFix();
                }
            }
        } else if (mVoIPCallActive && mode != AudioManager.MODE_IN_CALL) {
            // Call ended
            mVoIPCallActive = false;
            mIsNewCallSetup = false;
            mIsFixApplied = false;
            mPendingSpeakerFix = false;
            log("VoIP activity ended, resetting fix state");
        }
    }
    
    private void scheduleMultipleFixes() {
        // Schedule multiple volume adjustment attempts to ensure it works
        mHandler.postDelayed(() -> {
            if (mPendingSpeakerFix && !mIsFixApplied) {
                log("Applying first scheduled fix after speaker change");
                applyVolumeButtonFix();
            }
        }, 300);
        
        mHandler.postDelayed(() -> {
            if (mPendingSpeakerFix && !mIsFixApplied) {
                log("Applying second scheduled fix after speaker change");
                applyVolumeButtonFix();
            }
        }, 600);
        
        mHandler.postDelayed(() -> {
            if (mPendingSpeakerFix && !mIsFixApplied) {
                log("Applying third scheduled fix after speaker change");
                applyVolumeButtonFix();
            }
        }, 1000);
    }
    
    // Schedule multiple initial call fixes to ensure audio path is established
    private void scheduleInitialCallFixes() {
        // Multiple attempts at different intervals to ensure audio path is initialized
        for (int delay : new int[]{300, 600, 1000, 2000, 3000}) {
            mHandler.postDelayed(() -> {
                if (mVoIPCallActive && mIsNewCallSetup && 
                    System.currentTimeMillis() - mCallStartTime < 5000) {
                    log("Applying scheduled initial call fix at " + 
                        (System.currentTimeMillis() - mCallStartTime) + "ms");
                    applyAggressiveVolumeButtonFix();
                }
            }, delay);
        }
        
        // After 5 seconds, we no longer consider this a new call setup
        mHandler.postDelayed(() -> {
            mIsNewCallSetup = false;
            log("Initial call setup phase complete");
        }, 5000);
    }
    
    // More aggressive volume fix for initial call setup
    private void applyAggressiveVolumeButtonFix() {
        if (!mVoIPCallActive) {
            return;
        }
        
        log("Applying aggressive volume fix for initial call audio");
        
        // Get current volume
        int currentVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
        int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
        
        // Force volume to be audible if it's too low
        int targetVolume = Math.max(currentVolume, maxVolume / 2);
        
        // Make significant volume change then restore
        // First silence completely
        mAudioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0);
        
        // Then go to high volume
        mHandler.postDelayed(() -> {
            mAudioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0);
            
            // Then restore to target volume
            mHandler.postDelayed(() -> {
                mAudioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetVolume, 0);
                log("Aggressive volume fix complete, restored to: " + targetVolume);
                
                // Mark as fixed, but keep new call setup flag for additional fixes if needed
                mIsFixApplied = true;
                mPendingSpeakerFix = false;
            }, 100);
        }, 100);
    }
    
    private void applyVolumeButtonFix() {
        if (!mVoIPCallActive) {
            return;
        }
        
        log("Applying volume button fix for VoIP audio");
        
        // Get current volume
        int currentVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
        
        // Apply fix to voice call stream
        applyVolumeFixToStream(AudioManager.STREAM_VOICE_CALL, currentVolume);
        
        // Mark as fixed
        mIsFixApplied = true;
        mPendingSpeakerFix = false;
    }

    // Initialize audio path for new calls with more aggressive approach
    private void initializeCallAudioPath() {
        log("Initializing call audio path with aggressive hardware-level approach");
        
        // Force audio mode to reset routing
        int currentMode = mAudioManager.getMode();
        mAudioManager.setMode(AudioManager.MODE_NORMAL);
        
        // Force set voice call volume to ensure it's not muted
        int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
        int targetVolume = Math.max(maxVolume / 2, 1); // At least 50% or 1
        mAudioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetVolume, 0);
        
        // Slight delay before setting to communication mode
        mHandler.postDelayed(() -> {
            mAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            
            // Apply extremely aggressive fixes
            mHandler.postDelayed(() -> {
                // 1. Try direct AudioSystem force routing (lower level than AudioManager)
                try {
                    AudioSystem.setForceUse(AudioSystem.FOR_COMMUNICATION, 
                            mSpeakerActive ? AudioSystem.FORCE_SPEAKER : AudioSystem.FORCE_NONE);
                    log("Applied direct AudioSystem force routing");
                } catch (Exception e) {
                    log("Failed to apply direct AudioSystem routing: " + e);
                }
                
                // 2. Force direct volume key simulation - this is what the user does manually
                simulateVolumeKeyPresses();
                
                // 3. Toggle speaker rapidly to kick the audio HAL
                toggleSpeakerFast();
                
                // 4. Schedule multiple volume key simulations at different intervals
                scheduleRepeatedVolumeKeySimulation();
            }, 100);
        }, 100);
    }
    
    // Aggressively simulate the user pressing volume keys repeatedly
    private void simulateVolumeKeyPresses() {
        log("Simulating volume key presses to kick audio HAL");
        
        // Save current volume
        final int originalVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
        final int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
        
        // Method 1: Use AudioManager directly (most compatible)
        // Adjust up then down multiple times
        for (int i = 0; i < 3; i++) {
            mHandler.postDelayed(() -> {
                mAudioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, 
                        AudioManager.ADJUST_RAISE, 0);
            }, i * 150);
            
            mHandler.postDelayed(() -> {
                mAudioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, 
                        AudioManager.ADJUST_LOWER, 0);
            }, i * 150 + 75);
        }
        
        // Method 2: Inject actual key events if we have permission
        if (mHaveWakePermission) {
            injectVolumeKeyEvents();
        }
        
        // Method 3: Use instrumentation key events via reflection (last resort)
        tryInstrumentationMethod();
        
        // Restore original volume after all adjustments
        mHandler.postDelayed(() -> {
            mAudioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, originalVolume, 0);
            log("Restored original volume after simulations: " + originalVolume);
        }, 800);
    }
    
    // Schedule repeated volume key simulations with increasing delays
    private void scheduleRepeatedVolumeKeySimulation() {
        // Try multiple times at different intervals
        int[] delays = new int[]{300, 800, 1500, 3000, 6000};
        for (int delay : delays) {
            mHandler.postDelayed(() -> {
                if (mVoIPCallActive && mIsNewCallSetup && 
                    System.currentTimeMillis() - mCallStartTime < 10000) {
                    log("Applying repeated volume key simulation at " + delay + "ms");
                    simulateVolumeKeyPresses();
                }
            }, delay);
        }
    }
    
    // Rapidly toggle speaker mode to kick audio HAL into action
    private void toggleSpeakerFast() {
        final boolean originalSpeakerState = mAudioManager.isSpeakerphoneOn();
        
        // Toggle 3 times very rapidly
        mHandler.post(() -> {
            mAudioManager.setSpeakerphoneOn(!originalSpeakerState);
            log("Toggle speaker: " + !originalSpeakerState);
            
            mHandler.postDelayed(() -> {
                mAudioManager.setSpeakerphoneOn(originalSpeakerState);
                log("Toggle speaker back: " + originalSpeakerState);
                
                mHandler.postDelayed(() -> {
                    mAudioManager.setSpeakerphoneOn(!originalSpeakerState);
                    log("Toggle speaker again: " + !originalSpeakerState);
                    
                    mHandler.postDelayed(() -> {
                        mAudioManager.setSpeakerphoneOn(originalSpeakerState);
                        log("Final speaker state: " + originalSpeakerState);
                    }, 50);
                }, 50);
            }, 50);
        });
    }
    
    // Directly inject volume key events (requires permissions)
    private void injectVolumeKeyEvents() {
        try {
            log("Attempting direct key event injection");
            
            // Use reflection to access WindowManager's input injection
            Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
            Object wmgInstance = wmgClass.getMethod("getInstance").invoke(null);
            
            // Get the input manager
            Class<?> imClass = Class.forName("android.hardware.input.InputManager");
            Object imInstance = imClass.getMethod("getInstance").invoke(null);
            
            // Create DOWN then UP events for volume keys
            long eventTime = SystemClock.uptimeMillis();
            
            // Get method to inject key event
            java.lang.reflect.Method injectMethod = imClass.getMethod("injectInputEvent", 
                KeyEvent.class, int.class);
            
            // Volume UP events
            KeyEvent downEvent = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, 
                KeyEvent.KEYCODE_VOLUME_UP, 0);
            injectMethod.invoke(imInstance, downEvent, 0);
            
            KeyEvent upEvent = new KeyEvent(eventTime, eventTime+10, KeyEvent.ACTION_UP, 
                KeyEvent.KEYCODE_VOLUME_UP, 0);
            injectMethod.invoke(imInstance, upEvent, 0);
            
            // Small delay
            Thread.sleep(50);
            
            // Volume DOWN events
            eventTime = SystemClock.uptimeMillis();
            downEvent = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, 
                KeyEvent.KEYCODE_VOLUME_DOWN, 0);
            injectMethod.invoke(imInstance, downEvent, 0);
            
            upEvent = new KeyEvent(eventTime, eventTime+10, KeyEvent.ACTION_UP, 
                KeyEvent.KEYCODE_VOLUME_DOWN, 0);
            injectMethod.invoke(imInstance, upEvent, 0);
            
            log("Successfully injected volume key events");
        } catch (Exception e) {
            log("Failed to inject key events: " + e.toString());
        }
    }
    
    // Try using instrumentation to send key events
    private void tryInstrumentationMethod() {
        try {
            log("Attempting instrumentation method");
            
            // Use reflection to access Instrumentation
            Class<?> instrumentationClass = Class.forName("android.app.Instrumentation");
            Object instrumentation = instrumentationClass.newInstance();
            
            // Get the sendKeyDownUpSync method
            java.lang.reflect.Method sendKeyMethod = instrumentationClass.getMethod(
                "sendKeyDownUpSync", int.class);
            
            // Send volume up and down key events
            sendKeyMethod.invoke(instrumentation, KeyEvent.KEYCODE_VOLUME_UP);
            Thread.sleep(50);
            sendKeyMethod.invoke(instrumentation, KeyEvent.KEYCODE_VOLUME_DOWN);
            
            log("Successfully sent key events via instrumentation");
        } catch (Exception e) {
            log("Failed instrumentation method: " + e.toString());
        }
    }

    // Add method to check if media is playing
    private void checkMediaPlaybackState() {
        boolean isPlaying = false;
        
        // Check if music stream is active
        if (AudioSystem.isStreamActive(AudioManager.STREAM_MUSIC, 0)) {
            isPlaying = true;
        }
        
        // State changed
        if (isPlaying != mMediaPlaybackActive) {
            mMediaPlaybackActive = isPlaying;
            log("Media playback state changed to: " + mMediaPlaybackActive);
            
            // If we're near and media just started playing, ensure it routes to earpiece
            if (mMediaPlaybackActive && mProximityNear) {
                mHandler.postDelayed(() -> applyMediaRoutingFix(true), 300);
            }
        }
    }
    
    // Handle proximity changes during media playback
    private void handleMediaProximityChange(boolean isNear) {
        if (!mMediaPlaybackActive) {
            return;
        }
        
        log("Handling proximity change during media playback: " + (isNear ? "near" : "far"));
        
        // If near, route to earpiece, else route to speaker
        applyMediaRoutingFix(isNear);
    }
    
    // Apply routing fix for media streams
    private void applyMediaRoutingFix(boolean routeToEarpiece) {
        log("Applying media routing fix, route to earpiece: " + routeToEarpiece);
        
        // Save current volume
        int currentVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        
        if (routeToEarpiece) {
            // Force routing to earpiece for media stream
            mAudioManager.setSpeakerphoneOn(false);
            // On some devices, we need to also trigger audio routing reset
            mAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            mHandler.postDelayed(() -> {
                mAudioManager.setMode(AudioManager.MODE_NORMAL);
                // Force audio to continue playing by adjusting volume slightly
                applyVolumeFixToStream(AudioManager.STREAM_MUSIC, currentVolume);
            }, 100);
        } else {
            // Route back to speaker
            mAudioManager.setSpeakerphoneOn(true);
            mHandler.postDelayed(() -> {
                // Force audio to continue playing by adjusting volume slightly
                applyVolumeFixToStream(AudioManager.STREAM_MUSIC, currentVolume);
            }, 100);
        }
    }
    
    // Generalized volume fix method that works on any stream
    private void applyVolumeFixToStream(int streamType, int originalVolume) {
        // Get max volume for the stream
        int maxVolume = mAudioManager.getStreamMaxVolume(streamType);
        
        // Determine adjustment direction based on current volume
        if (originalVolume > maxVolume / 2) {
            // We're above half volume, so decrease then increase
            log("Adjusting stream " + streamType + " down then restoring");
            mAudioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, 0);
            
            // Wait a moment before restoring
            mHandler.postDelayed(() -> {
                mAudioManager.setStreamVolume(streamType, originalVolume, 0);
                log("Volume fix applied to stream " + streamType + " and restored to: " + originalVolume);
            }, 100);
        } else {
            // We're at or below half volume, so increase then decrease
            log("Adjusting stream " + streamType + " up then restoring");
            mAudioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, 0);
            
            // Wait a moment before restoring
            mHandler.postDelayed(() -> {
                mAudioManager.setStreamVolume(streamType, originalVolume, 0);
                log("Volume fix applied to stream " + streamType + " and restored to: " + originalVolume);
            }, 100);
        }
    }

    @Override
    public void onDestroy() {
        // Unregister the proximity sensor listener
        if (mSensorManager != null && mProximitySensor != null) {
            mSensorManager.unregisterListener(mProximitySensorListener);
        }
        
        unregisterReceiver(mReceiver);
        super.onDestroy();
        log("VoIPFix Service destroyed");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            log("Received action: " + intent.getAction());
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void log(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }
}
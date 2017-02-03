package com.ptrprograms.smartdoorbell;

import android.app.Activity;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.things.contrib.driver.pwmservo.Servo;
import com.google.android.things.contrib.driver.pwmspeaker.Speaker;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.IOException;
import java.nio.ByteBuffer;

public class MainActivity extends Activity implements ImageReader.OnImageAvailableListener, HCSR501.OnMotionDetectedEventListener {

    private static final String FIREBASE_URL = "INSERT_FIREBASE_STORAGE_URL_HERE";

    //Handlers handler
    private Handler mCameraBackgroundHandler;
    private HandlerThread mCameraBackgroundThread;

    private HandlerThread mSpeakerHandlerThread;
    private Handler mSpeakerHandler;

    private Handler mServoHandler;

    //Hardware components
    private DoorbellCamera mCamera;
    private HCSR501 mMotionSensor;
    private Speaker mSpeaker;
    private Servo mServo;

    private int mPlaybackIndex = 0;
    private static final long PLAYBACK_NOTE_DELAY = 80L;


    private final int MAX_MOUTH_MOVEMENT = 6;
    int mouthCounter = MAX_MOUTH_MOVEMENT;

    private Runnable mPlaybackRunnable = new Runnable() {

        @Override
        public void run() {
            if (mSpeaker == null) {
                return;
            }

            try {
                if (mPlaybackIndex == MusicNotes.DRAMATIC_THEME.length) {
                    // reached the end
                    mSpeaker.stop();
                } else {
                    double note = MusicNotes.DRAMATIC_THEME[mPlaybackIndex++];
                    if (note > 0) {
                        mSpeaker.play(note);
                    } else {
                        mSpeaker.stop();
                    }
                    mSpeakerHandler.postDelayed(this, PLAYBACK_NOTE_DELAY);
                }
            } catch (IOException e) {
                Log.e("Test", "ioexception in sound: " + e.getMessage());
            }
        }
    };

    private Runnable mMoveServoRunnable = new Runnable() {

        private static final long DELAY_MS = 1000L; // 5 seconds

        private double mAngle = Float.NEGATIVE_INFINITY;

        @Override
        public void run() {
            if (mServo == null || mouthCounter <= 0) {
                return;
            }

            try {
                if (mAngle <= mServo.getMinimumAngle()) {
                    mAngle = mServo.getMaximumAngle();
                } else {
                    mAngle = mServo.getMinimumAngle();
                }
                mServo.setAngle(mAngle);

                mouthCounter--;
                mServoHandler.postDelayed(this, DELAY_MS);
            } catch (IOException e) {
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initSpeaker();
        initServo();
        initMotionDetection();
        initCamera();
    }

    private void initCamera() {
        mCameraBackgroundThread = new HandlerThread("CameraInputThread");
        mCameraBackgroundThread.start();
        mCameraBackgroundHandler = new Handler(mCameraBackgroundThread.getLooper());

        mCamera = DoorbellCamera.getInstance();
        mCamera.initializeCamera(this, mCameraBackgroundHandler, this);
    }

    private void initServo() {
        try {
            mServo = new Servo(BoardDefaults.getServoPwmPin());
            mServo.setAngleRange(0f, 180f);
            mServo.setEnabled(true);
        } catch (IOException e) {
            Log.e("Camera App", e.getMessage());
            return; // don't init handler. Stuff broke.
        }
    }

    private void playSound() {
        if (mSpeakerHandler != null) {
            mSpeakerHandler.removeCallbacks(mPlaybackRunnable);
        }

        mPlaybackIndex = 0;

        mSpeakerHandlerThread = new HandlerThread("pwm-playback");
        mSpeakerHandlerThread.start();
        mSpeakerHandler = new Handler(mSpeakerHandlerThread.getLooper());

        mSpeakerHandler.post(mPlaybackRunnable);
    }

    private void initMotionDetection() {
        try {
            mMotionSensor = new HCSR501(BoardDefaults.getMotionDetectorPin());
            mMotionSensor.setOnMotionDetectedEventListener(this);
        } catch (IOException e) {

        }
    }

    private void initSpeaker() {
        try {
            mSpeaker = new Speaker(BoardDefaults.getSpeakerPwmPin());
            mSpeaker.stop(); // in case the PWM pin was enabled already
        } catch (IOException e) {
            return;
        }
    }

    private void onPictureTaken(byte[] imageBytes) {
        if (imageBytes != null) {
            FirebaseStorage storage = FirebaseStorage.getInstance();

            StorageReference storageReference = storage.getReferenceFromUrl(FIREBASE_URL).child(System.currentTimeMillis() + ".png");

            UploadTask uploadTask = storageReference.putBytes(imageBytes);
            uploadTask.addOnFailureListener(new OnFailureListener() {
                @Override
                public void onFailure(@NonNull Exception exception) {
                }
            }).addOnSuccessListener(new OnSuccessListener<UploadTask.TaskSnapshot>() {
                @Override
                public void onSuccess(UploadTask.TaskSnapshot taskSnapshot) {
                }
            });

        }
    }

    @Override
    public void onImageAvailable(ImageReader imageReader) {
        Image image = imageReader.acquireLatestImage();
        ByteBuffer imageBuf = image.getPlanes()[0].getBuffer();
        final byte[] imageBytes = new byte[imageBuf.remaining()];
        imageBuf.get(imageBytes);
        image.close();

        onPictureTaken(imageBytes);
    }

    private void moveMouth() {
        if (mServoHandler != null) {
            mServoHandler.removeCallbacks(mMoveServoRunnable);
        }

        mouthCounter = MAX_MOUTH_MOVEMENT;
        mServoHandler = new Handler();
        mServoHandler.post(mMoveServoRunnable);
    }

    @Override
    public void onMotionDetectedEvent(HCSR501.State state) {
        if (state == HCSR501.State.STATE_HIGH) {
            mCamera.takePicture();
            try {
                mSpeaker.stop();
            } catch (IOException e) {

            }

            moveMouth();
            playSound();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mSpeakerHandler != null) {
            mSpeakerHandler.removeCallbacks(mPlaybackRunnable);
            mSpeakerHandlerThread.quitSafely();
        }

        if (mSpeaker != null) {
            try {
                mSpeaker.stop();
                mSpeaker.close();
            } catch (IOException e) {
            } finally {
                mSpeaker = null;
            }
        }

        if (mMotionSensor != null) {
            try {
                mMotionSensor.close();
            } catch (IOException e) {

            } finally {
                mMotionSensor = null;
            }
        }

        if (mServoHandler != null) {
            mServoHandler.removeCallbacks(mMoveServoRunnable);
        }
        if (mServo != null) {
            try {
                mServo.close();
            } catch (IOException e) {
            } finally {
                mServo = null;
            }
        }

        mCameraBackgroundThread.quitSafely();
        mCamera.shutDown();
    }
}
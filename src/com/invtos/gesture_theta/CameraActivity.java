/*
 * Copyright 2016 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.invtos.gesture_theta;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Trace;
import android.support.v4.app.Fragment;
import android.support.v7.app.ActionBar;
import android.util.Size;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import com.invtos.gesture_theta.task.TakePictureTask;
import com.theta360.pluginlibrary.activity.PluginActivity;
import com.theta360.pluginlibrary.callback.KeyCallback;
import com.theta360.pluginlibrary.receiver.KeyReceiver;
import com.theta360.pluginlibrary.values.LedTarget;

import java.nio.ByteBuffer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

import com.invtos.gesture_theta.env.ImageUtils;
import com.invtos.gesture_theta.env.Logger;
import com.invtos.gesture_theta.R; // Explicit import needed for internal Google builds.

import static android.os.SystemClock.sleep;

public abstract class CameraActivity extends PluginActivity
    implements OnImageAvailableListener, Camera.PreviewCallback {
  private static final Logger LOGGER = new Logger();

  private static final int PERMISSIONS_REQUEST = 1;

  private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
  private static final String PERMISSION_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;

  private boolean debug = false;

  private Handler handler;
  private HandlerThread handlerThread;
  private boolean useCamera2API;
  private boolean isProcessingFrame = false;
  private byte[][] yuvBytes = new byte[3][];
  private int[] rgbBytes = null;
  private int yRowStride;

  protected int previewWidth = 0;
  protected int previewHeight = 0;

  private Runnable postInferenceCallback;
  private Runnable imageConverter;

  private boolean isInferenceWorking = true;
  private boolean isTakingPicture = false;

  private Handler mCameraActivityHandler=null;

  protected String mObjectNameToFind;
  protected boolean mObjectNameFound = false;

  private Date mCaptureTime;
  private final long mThreashIgnore_msec = 15 * 1000; // Capturing interval [msec]

  private final String mObjectToFind = "person"; // Take picture when the object found. Something to find from assets/coco_labels_list.txt for TensorFlowObjectDetectionAPIModel.

  private boolean isEnded = false;

  private final String CLOUD_UPLOAD_RESULT_KEY_NAME = "UploadResult";
  private final int CLOUD_UPLOAD_REQUSEST_CODE = 1;

  // Step4: Change to "true" for using Cloud Upload plug-in, 1
  private boolean ENABLE_CLOUD_UPLOAD = false;

  // Step3: Uncomment when taking a photo with WebAPI, 1
  private TakePictureTask.Callback mTakePictureTaskCallback = new TakePictureTask.Callback() {
    @Override
    public void onTakePicture(String fileUrl) {
      //fileUrl = "http://127.0.0.1:8080/files/150100525831424d420703bede5d2400/100RICOH/R0010231.JPG"
      LOGGER.d("onTakePicture: " + fileUrl);
      isTakingPicture = false;

      if(ENABLE_CLOUD_UPLOAD) {
        // Step4: Uncomment for using Cloud Upload plug-in, 2
        cloudUpload(fileUrl);
      }else {
        // Start Preview
        startInference();
      }
    }
  };

  // Step2: Comment-out when using pluginlibrary 2
//  private void notificationCameraClose(){
//    sendBroadcast(new Intent("com.theta360.plugin.ACTION_MAIN_CAMERA_CLOSE")); // for THETA
//  }

  // Step2: Comment-out when using pluginlibrary 3
//  private void notificationCameraOpen(){
//    sendBroadcast(new Intent("com.theta360.plugin.ACTION_MAIN_CAMERA_OPEN")); // for THETA
//  }

  // Step2: Comment-out when using pluginlibrary 4
//  public void notificationSuccess() {
//    Intent intent = new Intent("com.theta360.plugin.ACTION_FINISH_PLUGIN");
//    intent.putExtra("packageName", getPackageName());
//    intent.putExtra("exitStatus", "success");
//    sendBroadcast(intent);
//    finishAndRemoveTask();
//  }

  // Step2: Comment-out when using pluginlibrary 5
//  public void notificationError(String message) {
//    Intent intent = new Intent("com.theta360.plugin.ACTION_FINISH_PLUGIN");
//    intent.putExtra("packageName", getPackageName());
//    intent.putExtra("exitStatus", "failure");
//    intent.putExtra("message", message);
//    sendBroadcast(intent);
//    finishAndRemoveTask();
//  }

  // Step4: Uncomment for using Cloud Upload plug-in, 3
  // Upload fileUrl to Google Photos by Cloud Upload plug-in
  private void cloudUpload(String fileUrl){
    // Convert fileUrl to filePath
    int lastIndex = fileUrl.lastIndexOf('/');
    String dirAndFileName = fileUrl.substring(fileUrl.lastIndexOf('/', lastIndex-1) + 1); // 100RICOH/R0010231.JPG
    String filePath = "/storage/emulated/0/DCIM/"+dirAndFileName;
    LOGGER.d("cloudUpload: " + filePath);

    // Call File Cloud Upload
    Intent intent=new Intent();
    ArrayList<String> photoList = new ArrayList();
    photoList.add(filePath);
    intent.setClassName("com.theta360.cloudupload","com.theta360.cloudupload.MainActivity");
    intent.putStringArrayListExtra("com.theta360.cloudupload.photoList", photoList);
    startActivityForResult(intent, CLOUD_UPLOAD_REQUSEST_CODE);
    // once go to onStop after calling startActivityForResult
  }

  // Result from cloudupload
  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data){
    LOGGER.d("onActivityResult: " + requestCode + ", resultCode=" + resultCode);
    switch(requestCode) {
      case (CLOUD_UPLOAD_REQUSEST_CODE):
        if(resultCode == RESULT_OK){
          boolean uploadResult = data.getBooleanExtra(CLOUD_UPLOAD_RESULT_KEY_NAME, false);
          LOGGER.d("uploadResult: " + String.valueOf(uploadResult));
        }
        startInference();
        break;
      default:
        break;
    }
    // onRestart and onStart will be called.
  }

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    LOGGER.d("onCreate " + this);
    super.onCreate(null);

    mCameraActivityHandler = new Handler();

    onSetObjectNameToFind(mObjectToFind);

    try {
      SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy/M/dd hh:mm:ss");
      mCaptureTime = simpleDateFormat.parse("2016/10/6 12:00:00"); // initialize to the past
    } catch (ParseException e) {
      e.printStackTrace();
    }

    // Step2: Uncomment when using pluginlibrary 6
    // Set enable to close by pluginlibrary, If you set false, please call close() after finishing your end processing.
    setAutoClose(true);
/*
    if (ENABLE_CLOUD_UPLOAD) {
      notificationWlanCl(); // for uploading file
    } else {
      notificationWlanOff(); // for power saving
    }
*/
    // Step1: Uncomment for THETA, 3
    notificationCameraClose();

    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null) {
      actionBar.hide();
    }

    setContentView(R.layout.activity_camera);

    if (hasPermission()) {
      setFragment();
    } else {
      // Set app permission in Settings app, or install from THETA plugin store
      notificationError("Permissions are not granted.");
    }


    // Step2: Uncomment when using pluginlibrary 7
    // Set a callback when a button operation event is acquired.
    setKeyCallback(new KeyCallback() {
      @Override
      public void onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyReceiver.KEYCODE_CAMERA) {
          // Step3: Uncomment when taking a photo with WebAPI, 2
          stopInferenceAndCapture();
        }
      }

      @Override
      public void onKeyUp(int keyCode, KeyEvent event) {
        /**
         * You can control the LED of the camera.
         * It is possible to change the way of lighting, the cycle of blinking, the color of light emission.
         * Light emitting color can be changed only LED3.
         */
      }

      @Override
      public void onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyReceiver.KEYCODE_MEDIA_RECORD){
          if(!isTakingPicture) {
            endProcess();
          }
        }
      }
    });
  }

  protected void startInference() {
    if (isEnded) {
      // now on ending process
    }else{
      notificationCameraClose();
      sleep(400);

      mCameraActivityHandler.post(new Runnable() {
        @Override
        public void run() {
          Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.container);
          fragment.onResume();
          isInferenceWorking = true;
        }
      });
    }
  }


  // Step3: Uncomment when taking a photo with WebAPI, 3
  protected void stopInferenceAndCapture() {
    stopInference();

    isTakingPicture = true;
    // Take Picture
    new TakePictureTask(mTakePictureTaskCallback).execute();
  }


  protected void stopInference() {
    Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.container);
    if(isInferenceWorking) {
      isInferenceWorking = false;
      // Stop Preview
      fragment.onPause();
      notificationCameraOpen();
      sleep(600);
    }
  }

// Step2: Uncomment when using pluginlibrary 8
  private void endProcess() {
    LOGGER.d("CameraActivity::endProcess(): "+ isEnded);

    if (!isEnded) {
      isEnded = true;
      // Step3: Uncomment when taking a photo with WebAPI, 4
      stopInference();

      close();
    }
  }

  private byte[] lastPreviewFrame;

  protected int[] getRgbBytes() {
    imageConverter.run();
    return rgbBytes;
  }

  protected int getLuminanceStride() {
    return yRowStride;
  }

  protected byte[] getLuminance() {
    return yuvBytes[0];
  }

  /**
   * Callback for android.hardware.Camera API
   */
  @Override
  public void onPreviewFrame(final byte[] bytes, final Camera camera) {
    if (isProcessingFrame) {
      LOGGER.w("Dropping frame!");
      return;
    }

    try {
      // Initialize the storage bitmaps once when the resolution is known.
      if (rgbBytes == null) {
        Camera.Size previewSize = camera.getParameters().getPreviewSize();
        previewHeight = previewSize.height;
        previewWidth = previewSize.width;
        rgbBytes = new int[previewWidth * previewHeight];
        onPreviewSizeChosen(new Size(previewSize.width, previewSize.height), 0);
      }
    } catch (final Exception e) {
      LOGGER.e(e, "Exception!");
      return;
    }

    isProcessingFrame = true;
    lastPreviewFrame = bytes;
    yuvBytes[0] = bytes;
    yRowStride = previewWidth;

    imageConverter =
        new Runnable() {
          @Override
          public void run() {
            ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes);
          }
        };

    postInferenceCallback =
        new Runnable() {
          @Override
          public void run() {
            camera.addCallbackBuffer(bytes);
            isProcessingFrame = false;
          }
        };
    processImage();

    // Step3: Uncomment when taking a photo with WebAPI, 5
    if( objectNameFound() ) {
      mObjectNameFound = false;
      Date currentTime = Calendar.getInstance().getTime();
      long diff_msec = currentTime.getTime() - mCaptureTime.getTime();
      if (diff_msec > mThreashIgnore_msec){
        stopInferenceAndCapture();
        mCaptureTime = currentTime;
      }
    }
  }

  /**
   * Callback for Camera2 API
   */
  @Override
  public void onImageAvailable(final ImageReader reader) {
    //We need wait until we have some size from onPreviewSizeChosen
    if (previewWidth == 0 || previewHeight == 0) {
      return;
    }
    if (rgbBytes == null) {
      rgbBytes = new int[previewWidth * previewHeight];
    }
    try {
      final Image image = reader.acquireLatestImage();

      if (image == null) {
        return;
      }

      if (isProcessingFrame) {
        image.close();
        return;
      }
      isProcessingFrame = true;
      Trace.beginSection("imageAvailable");
      final Plane[] planes = image.getPlanes();
      fillBytes(planes, yuvBytes);
      yRowStride = planes[0].getRowStride();
      final int uvRowStride = planes[1].getRowStride();
      final int uvPixelStride = planes[1].getPixelStride();

      imageConverter =
          new Runnable() {
            @Override
            public void run() {
              ImageUtils.convertYUV420ToARGB8888(
                  yuvBytes[0],
                  yuvBytes[1],
                  yuvBytes[2],
                  previewWidth,
                  previewHeight,
                  yRowStride,
                  uvRowStride,
                  uvPixelStride,
                  rgbBytes);
            }
          };

      postInferenceCallback =
          new Runnable() {
            @Override
            public void run() {
              image.close();
              isProcessingFrame = false;
            }
          };

      processImage();
    } catch (final Exception e) {
      LOGGER.e(e, "Exception!");
      Trace.endSection();
      return;
    }
    Trace.endSection();
  }

  @Override
  public synchronized void onStart() {
    LOGGER.d("onStart " + this);

    // Step2: Uncomment when using pluginlibrary 9
    notificationLedShow(LedTarget.LED4); // Turn ON Camera LED
    notificationLedHide(LedTarget.LED5);
    notificationLedHide(LedTarget.LED6);
    notificationLedHide(LedTarget.LED7);
    notificationLedHide(LedTarget.LED8);
    super.onStart();
  }

  @Override
  public synchronized void onResume() {
    LOGGER.d("onResume " + this);
    super.onResume();

    handlerThread = new HandlerThread("inference");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
  }

  @Override
  public synchronized void onPause() {
    LOGGER.d("onPause " + this);

    handlerThread.quitSafely();
    try {
      handlerThread.join();
      handlerThread = null;
      handler = null;
    } catch (final InterruptedException e) {
      LOGGER.e(e, "Exception!");
    }

    super.onPause();
  }

  @Override
  public synchronized void onStop() {
    LOGGER.d("onStop " + this);
    super.onStop();
  }

  @Override
  public synchronized void onDestroy() {
    LOGGER.d("onDestroy " + this);
    if (!isFinishing()) {
      LOGGER.d("Requesting finish");

      // Step2: Uncomment when using pluginlibrary 10
      close();
    }
    super.onDestroy();
  }

  protected synchronized void runInBackground(final Runnable r) {
    if (handler != null) {
      handler.post(r);
    }
  }

  @Override
  public void onRequestPermissionsResult(
      final int requestCode, final String[] permissions, final int[] grantResults) {
    if (requestCode == PERMISSIONS_REQUEST) {
      if (grantResults.length > 0
          && grantResults[0] == PackageManager.PERMISSION_GRANTED
          && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
        setFragment();
      } else {
        requestPermission();
      }
    }
  }

  private boolean hasPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED &&
          checkSelfPermission(PERMISSION_STORAGE) == PackageManager.PERMISSION_GRANTED;
    } else {
      return true;
    }
  }

  private void requestPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA) ||
          shouldShowRequestPermissionRationale(PERMISSION_STORAGE)) {
        Toast.makeText(CameraActivity.this,
            "Camera AND storage permission are required for this program", Toast.LENGTH_LONG).show();
      }
      requestPermissions(new String[] {PERMISSION_CAMERA, PERMISSION_STORAGE}, PERMISSIONS_REQUEST);
    }
  }

  // Returns true if the device supports the required hardware level, or better.
  private boolean isHardwareLevelSupported(
      CameraCharacteristics characteristics, int requiredLevel) {
    int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
    if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
      return requiredLevel == deviceLevel;
    }
    // deviceLevel is not LEGACY, can use numerical sort
    return requiredLevel <= deviceLevel;
  }

  private String chooseCamera() {
    final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
    try {
      for (final String cameraId : manager.getCameraIdList()) {
        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

        // We don't use a front facing camera in this sample.
        final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
          continue;
        }

        final StreamConfigurationMap map =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        if (map == null) {
          continue;
        }

        // Fallback to camera1 API for internal cameras that don't have full support.
        // This should help with legacy situations where using the camera2 API causes
        // distorted or otherwise broken previews.
        useCamera2API = (facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
            || isHardwareLevelSupported(characteristics, 
                                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
        LOGGER.i("Camera API lv2?: %s", useCamera2API);
        return cameraId;
      }
    } catch (CameraAccessException e) {
      LOGGER.e(e, "Not allowed to access camera");
    }

    return null;
  }

  protected void setFragment() {
    String cameraId = chooseCamera();
    if (cameraId == null) {
      Toast.makeText(this, "No Camera Detected", Toast.LENGTH_SHORT).show();
      finish();
    }

    Fragment fragment;
    if (useCamera2API) {
      CameraConnectionFragment camera2Fragment =
          CameraConnectionFragment.newInstance(
              new CameraConnectionFragment.ConnectionCallback() {
                @Override
                public void onPreviewSizeChosen(final Size size, final int rotation) {
                  previewHeight = size.getHeight();
                  previewWidth = size.getWidth();
                  CameraActivity.this.onPreviewSizeChosen(size, rotation);
                }
              },
              this,
              getLayoutId(),
              getDesiredPreviewFrameSize());

      camera2Fragment.setCamera(cameraId);
      fragment = camera2Fragment;
    } else {
      fragment =
          new LegacyCameraConnectionFragment(this, getLayoutId(), getDesiredPreviewFrameSize());
    }

    getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.container, fragment)
        .commit();
  }

  protected void fillBytes(final Plane[] planes, final byte[][] yuvBytes) {
    // Because of the variable row stride it's not possible to know in
    // advance the actual necessary dimensions of the yuv planes.
    for (int i = 0; i < planes.length; ++i) {
      final ByteBuffer buffer = planes[i].getBuffer();
      if (yuvBytes[i] == null) {
        LOGGER.d("Initializing buffer %d at size %d", i, buffer.capacity());
        yuvBytes[i] = new byte[buffer.capacity()];
      }
      buffer.get(yuvBytes[i]);
    }
  }

  public boolean isDebug() {
    return debug;
  }

  public void requestRender() {
    final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
    if (overlay != null) {
      overlay.postInvalidate();
    }
  }

  public void addCallback(final OverlayView.DrawCallback callback) {
    final OverlayView overlay = (OverlayView) findViewById(R.id.debug_overlay);
    if (overlay != null) {
      overlay.addCallback(callback);
    }
  }

  public void onSetDebug(final boolean debug) {}

  @Override
  public boolean onKeyDown(final int keyCode, final KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
            || keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
      debug = !debug;
      requestRender();
      onSetDebug(debug);
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  protected void onSetObjectNameToFind(final String name) {
    mObjectNameToFind = name; // TF_OD_API_LABELS_FILE
  }
  protected boolean objectNameFound() {
    return mObjectNameFound;
  }

  protected void readyForNextImage() {
    if (postInferenceCallback != null) {
      postInferenceCallback.run();
    }
  }

  protected int getScreenOrientation() {
    switch (getWindowManager().getDefaultDisplay().getRotation()) {
      case Surface.ROTATION_270:
        return 270;
      case Surface.ROTATION_180:
        return 180;
      case Surface.ROTATION_90:
        return 90;
      default:
        return 0;
    }
  }

  protected abstract void processImage();

  protected abstract void onPreviewSizeChosen(final Size size, final int rotation);
  protected abstract int getLayoutId();
  protected abstract Size getDesiredPreviewFrameSize();
}

package com.spaylia.camera2video;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.util.Range;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v13.app.FragmentCompat;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Camera2VideoFragment extends Fragment
		implements View.OnClickListener, View.OnTouchListener, FragmentCompat.OnRequestPermissionsResultCallback,
		SeekBar.OnSeekBarChangeListener, SensorEventListener {

	private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
	private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;
	private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
	private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

	private static final String TAG = "Camera2VideoFragment";
	private static final int REQUEST_VIDEO_PERMISSIONS = 1;
	private static final String FRAGMENT_DIALOG = "dialog";

	private static final String[] VIDEO_PERMISSIONS = {
			Manifest.permission.CAMERA,
			Manifest.permission.RECORD_AUDIO,
	};

	static {
		DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90);
		DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0);
		DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
		DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);
	}

	static {
		INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
		INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
		INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
		INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
	}

	/**
	 * An {@link AutoFitTextureView} for camera preview.
	 */
	private AutoFitTextureView mTextureView;

	/**
	 * Button to record video
	 */
	private Button mButtonVideo;

	/**
	 * A refernce to the opened {@link android.hardware.camera2.CameraDevice}.
	 */
	private CameraDevice mCameraDevice;

	/**
	 * A reference to the current {@link android.hardware.camera2.CameraCaptureSession} for
	 * preview.
	 */
	private CameraCaptureSession mPreviewSession;

	/**
	 * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
	 * {@link TextureView}.
	 */
	private TextureView.SurfaceTextureListener mSurfaceTextureListener
			= new TextureView.SurfaceTextureListener() {

		@Override
		public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture,
											  int width, int height) {
			openCamera(width, height);
		}

		@Override
		public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture,
												int width, int height) {
			configureTransform(width, height);
		}

		@Override
		public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
			return true;
		}

		@Override
		public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
		}

	};

	/**
	 * The {@link android.util.Size} of camera preview.
	 */
	private Size mPreviewSize;

	/**
	 * The {@link android.util.Size} of video recording.
	 */
	private Size mVideoSize;

	/**
	 * MediaRecorder
	 */
	private MediaRecorder mMediaRecorder;

	/**
	 * Whether the app is recording video now
	 */
	private boolean mIsRecordingVideo;

	/**
	 * An additional thread for running tasks that shouldn't block the UI.
	 */
	private HandlerThread mBackgroundThread;

	/**
	 * A {@link Handler} for running tasks in the background.
	 */
	private Handler mBackgroundHandler;

	/**
	 * A {@link Semaphore} to prevent the app from exiting before closing the .
	 */
	private Semaphore mCameraOpenCloseLock = new Semaphore(1);

	/**
	 * A {@link CameraCharacteristics} to get the characteristics from the camera
	 */
	private CameraCharacteristics characteristics;

	/**
	 * A {@link SensorManager} to get the orientation in degrees
	 */
	private SensorManager sensorManager;

	/**
	 * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its status.
	 */
	private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

		@Override
		public void onOpened(CameraDevice cameraDevice) {
			mCameraDevice = cameraDevice;
			startPreview();
			mCameraOpenCloseLock.release();
			if (null != mTextureView) {
				configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
			}
		}

		@Override
		public void onDisconnected(CameraDevice cameraDevice) {
			mCameraOpenCloseLock.release();
			cameraDevice.close();
			mCameraDevice = null;
		}

		@Override
		public void onError(CameraDevice cameraDevice, int error) {
			mCameraOpenCloseLock.release();
			cameraDevice.close();
			mCameraDevice = null;
			Activity activity = getActivity();
			if (null != activity) {
				activity.finish();
			}
		}

	};
	private Integer mSensorOrientation;
	private String mNextVideoAbsolutePath;
	private CaptureRequest.Builder mPreviewBuilder;
	private Surface mRecorderSurface;

	public static Camera2VideoFragment newInstance() {
		return new Camera2VideoFragment();
	}

	/**
	 * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
	 * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
	 *
	 * @param choices The list of available sizes
	 * @return The video size
	 */
	private static Size chooseVideoSize(Size[] choices) {
		for (Size size : choices) {
			if (size.getWidth() == size.getHeight() * 4 / 3 && size.getWidth() <= 1080) {
				return size;
			}
		}
		Log.e(TAG, "Couldn't find any suitable video size");
		return choices[choices.length - 1];
	}

	/**
	 * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
	 * width and height are at least as large as the respective requested values, and whose aspect
	 * ratio matches with the specified value.
	 *
	 * @param choices     The list of sizes that the camera supports for the intended output class
	 * @param width       The minimum desired width
	 * @param height      The minimum desired height
	 * @param aspectRatio The aspect ratio
	 * @return The optimal {@code Size}, or an arbitrary one if none were big enough
	 */
	private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
		// Collect the supported resolutions that are at least as big as the preview Surface
		List<Size> bigEnough = new ArrayList<Size>();
		int w = aspectRatio.getWidth();
		int h = aspectRatio.getHeight();
		for (Size option : choices) {
			if (option.getHeight() == option.getWidth() * h / w &&
					option.getWidth() >= width && option.getHeight() >= height) {
				bigEnough.add(option);
			}
		}

		// Pick the smallest of those, assuming we found any
		if (bigEnough.size() > 0) {
			return Collections.min(bigEnough, new CompareSizesByArea());
		} else {
			Log.e(TAG, "Couldn't find any suitable preview size");
			return choices[0];
		}
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_camera2_video, container, false);
	}

	public Button returnAEButton;
	public SeekBar seekBarExposure;

	@Override
	public void onViewCreated(final View view, Bundle savedInstanceState) {
		view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
				| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
				| View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
				| View.SYSTEM_UI_FLAG_FULLSCREEN
				| View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

		mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
		mTextureView.setOnTouchListener(this);

		mButtonVideo = (Button) view.findViewById(R.id.video);
		mButtonVideo.setOnClickListener(this);

		((SeekBar)view.findViewById(R.id.seekBarFocal)).setOnSeekBarChangeListener(this);
		((SeekBar)view.findViewById(R.id.seekBarZoom)).setOnSeekBarChangeListener(this);
		//((SeekBar)view.findViewById(R.id.seekBarExposure)).setOnSeekBarChangeListener(this);
		seekBarExposure = (SeekBar)view.findViewById(R.id.seekBarExposure);
		seekBarExposure.setOnSeekBarChangeListener(this);

		tv = (TextView) view.findViewById(R.id.textViewDegrees);

		Context t = this.getContext();

		sensorManager = (SensorManager)t.getSystemService(Context.SENSOR_SERVICE);
		sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), 500000);

		returnAEButton = (Button)view.findViewById(R.id.returnAEButton);
		returnAEButton.setOnClickListener(this);
	}

	@Override
	public void onStopTrackingTouch(SeekBar seekBar) {}

	@Override
	public void onStartTrackingTouch(SeekBar seekBar) {
		switch (seekBar.getId()) {
			case R.id.seekBarFocal: {
				mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
				break;
			}
			case R.id.seekBarExposure: {
				mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
				break;
			}
		}
	}

	@Override
	public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
		switch (seekBar.getId()) {
			case R.id.seekBarFocal: {
				float minimumLens = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
				float num = (((float) progress) * minimumLens / 100);
				mPreviewBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, num);
				break;
			}
			case R.id.seekBarExposure: {
				Range<Integer> range1 = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);

				int maxMax = range1.getUpper();
				int minMin = range1.getLower();
				int all = (-minMin) + maxMax;
				int time = 100 / all;

				int ae = ((progress / time) - maxMax) > maxMax ? maxMax : ((progress / time) - maxMax) < minMin ? minMin : ((progress / time) - maxMax);
				mPreviewBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ae);
				break;
			}
			case R.id.seekBarZoom: {
				float maxZoom = (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))*10;
				Rect m = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

				int minW = (int) (m.width() / maxZoom);
				int minH = (int) (m.height() / maxZoom);
				int difW = m.width() - minW;
				int difH = m.height() - minH;
				int cropW = difW /100 * (progress/2);
				int cropH = difH /100 * (progress/2);
				cropW -= cropW & 3;
				cropH -= cropH & 3;

				Rect zoom = new Rect(cropW, cropH, m.width() - cropW, m.height() - cropH);
				mPreviewBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoom);
				break;
			}
		}
		try {
			mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	public float finger_spacing = 0;
	public float zoom_level = 1;

	@Override
	public boolean onTouch(View v, MotionEvent event) {
		float maxZoom = (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))*10;

		Rect m = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
		int action = event.getAction();
		float current_finger_spacing;

		if (event.getPointerCount() > 1) {		// Multi touch logic
			current_finger_spacing = getFingerSpacing(event);

			if(finger_spacing != 0) {
				if(current_finger_spacing > finger_spacing && maxZoom > zoom_level) {
					zoom_level += .6f;
				}
				else if (current_finger_spacing < finger_spacing && zoom_level > 1) {
					zoom_level -= .6f;

				}

				int minW = (int) (m.width() / maxZoom);
				int minH = (int) (m.height() / maxZoom);
				int difW = m.width() - minW;
				int difH = m.height() - minH;
				int cropW = difW /100 *(int)zoom_level;
				int cropH = difH /100 *(int)zoom_level;
				cropW -= cropW & 3;
				cropH -= cropH & 3;
				Rect zoom = new Rect(cropW, cropH, m.width() - cropW, m.height() - cropH);
				mPreviewBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoom);
			}
			finger_spacing = current_finger_spacing;
		}
		else {			// Single touch logic
			if(action == MotionEvent.ACTION_UP) {
				//Toast.makeText(getActivity(), "prog " + pr, Toast.LENGTH_SHORT).show();
			}
		}

		try {
			mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
		}
		catch (CameraAccessException e) {
			e.printStackTrace();
		}
		catch (NullPointerException ex) {
			ex.printStackTrace();
		}

		return true;
	}


    //Determine the space between the first two fingers
    private float getFingerSpacing(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

	private TextView tv;
	@Override
	public void onAccuracyChanged(Sensor arg0, int arg1) {

	}
	@Override
	public void onSensorChanged(SensorEvent arg0) {
		float[] values = arg0.values;
		float x = values[0]*10;
		float y = values[1]*10;
		float z = values[2]*10;

		String s = String.format("%1$.3f   %2$.3f   %3$.3f", x, y, z);

		tv.setText(s);
	}

	@Override
	public void onResume() {
		super.onResume();
		startBackgroundThread();
		if (mTextureView.isAvailable()) {
			openCamera(mTextureView.getWidth(), mTextureView.getHeight());
		} else {
			mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
		}
	}

	@Override
	public void onPause() {
		closeCamera();
		stopBackgroundThread();
		super.onPause();
	}

	@Override
	public void onClick(View view) {
		switch (view.getId()) {
			case R.id.video: {
				if (mIsRecordingVideo) {
					stopRecordingVideo();
					//returnAEButton.setText(R.string.returnButton);
					returnAEButton.setVisibility(View.VISIBLE);
				} else {
					startRecordingVideo();
					//returnAEButton.setText(R.string.AEButton);
					returnAEButton.setVisibility(View.INVISIBLE);
				}
				break;
			}
			case R.id.returnAEButton: {
				if (!mIsRecordingVideo) {
					getActivity().finish();
				} /*else {													//TODO auto exposure
					mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
					try {
						mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
					} catch (CameraAccessException e) {
						e.printStackTrace();
					}
				}*/
				break;
			}
		}
	}

	/**
	 * Starts a background thread and its {@link Handler}.
	 */
	private void startBackgroundThread() {
		mBackgroundThread = new HandlerThread("CameraBackground");
		mBackgroundThread.start();
		mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
	}

	/**
	 * Stops the background thread and its {@link Handler}.
	 */
	private void stopBackgroundThread() {
		mBackgroundThread.quitSafely();
		try {
			mBackgroundThread.join();
			mBackgroundThread = null;
			mBackgroundHandler = null;
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Gets whether you should show UI with rationale for requesting permissions.
	 *
	 * @param permissions The permissions your app wants to request.
	 * @return Whether you can show permission rationale UI.
	 */
	private boolean shouldShowRequestPermissionRationale(String[] permissions) {
		for (String permission : permissions) {
			if (FragmentCompat.shouldShowRequestPermissionRationale(this, permission)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Requests permissions needed for recording video.
	 */
	private void requestVideoPermissions() {
		if (shouldShowRequestPermissionRationale(VIDEO_PERMISSIONS)) {
			new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
		} else {
			FragmentCompat.requestPermissions(this, VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
										   @NonNull int[] grantResults) {
		Log.d(TAG, "onRequestPermissionsResult");
		if (requestCode == REQUEST_VIDEO_PERMISSIONS) {
			if (grantResults.length == VIDEO_PERMISSIONS.length) {
				for (int result : grantResults) {
					if (result != PackageManager.PERMISSION_GRANTED) {
						ErrorDialog.newInstance(getString(R.string.permission_request))
								.show(getChildFragmentManager(), FRAGMENT_DIALOG);
						break;
					}
				}
			} else {
				ErrorDialog.newInstance(getString(R.string.permission_request))
						.show(getChildFragmentManager(), FRAGMENT_DIALOG);
			}
		} else {
			super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		}
	}

	private boolean hasPermissionsGranted(String[] permissions) {
		for (String permission : permissions) {
			if (ActivityCompat.checkSelfPermission(getActivity(), permission)
					!= PackageManager.PERMISSION_GRANTED) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Tries to open a {@link CameraDevice}. The result is listened by `mStateCallback`.
	 */
	private void openCamera(int width, int height) {
		if (!hasPermissionsGranted(VIDEO_PERMISSIONS)) {
			requestVideoPermissions();
			return;
		}
		final Activity activity = getActivity();
		if (null == activity || activity.isFinishing()) {
			return;
		}
		CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
		try {
			Log.d(TAG, "tryAcquire");
			if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
				throw new RuntimeException("Time out waiting to lock camera opening.");
			}
			String cameraId = manager.getCameraIdList()[0];

			// Choose the sizes for camera preview and video recording
			characteristics = manager.getCameraCharacteristics(cameraId);
			StreamConfigurationMap map = characteristics
					.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
			mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
			mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
			mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height, mVideoSize);

			WindowManager windowManager = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
			Display display = windowManager.getDefaultDisplay();
			Point size = new Point();
			display.getSize(size);
			mTextureView.setAspectRatio(size.x, size.y);

			configureTransform(width, height);
			mMediaRecorder = new MediaRecorder();
			if (ContextCompat.checkSelfPermission(getContext(), android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
					|| ContextCompat.checkSelfPermission(getContext(), android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
				manager.openCamera(cameraId, mStateCallback, null);
		} catch (CameraAccessException e) {
			Toast.makeText(activity, "Cannot access the camera.", Toast.LENGTH_SHORT).show();
			activity.finish();
		} catch (NullPointerException e) {
			// Currently an NPE is thrown when the Camera2API is used but not supported on the
			// device this code runs.
			ErrorDialog.newInstance(getString(R.string.camera_error))
					.show(getChildFragmentManager(), FRAGMENT_DIALOG);
		} catch (InterruptedException e) {
			throw new RuntimeException("Interrupted while trying to lock camera opening.");
		}
	}

	private void closeCamera() {
		try {
			mCameraOpenCloseLock.acquire();
			closePreviewSession();
			if (null != mCameraDevice) {
				mCameraDevice.close();
				mCameraDevice = null;
			}
			if (null != mMediaRecorder) {
				mMediaRecorder.release();
				mMediaRecorder = null;
			}
		} catch (InterruptedException e) {
			throw new RuntimeException("Interrupted while trying to lock camera closing.");
		} finally {
			mCameraOpenCloseLock.release();
		}
	}

	/**
	 * Start the camera preview.
	 */
	private void startPreview() {
		if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize) {
			return;
		}
		try {
			closePreviewSession();
			SurfaceTexture texture = mTextureView.getSurfaceTexture();
			assert texture != null;
			texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
			mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

			Surface previewSurface = new Surface(texture);
			mPreviewBuilder.addTarget(previewSurface);

			mCameraDevice.createCaptureSession(Arrays.asList(previewSurface), new CameraCaptureSession.StateCallback() {

				@Override
				public void onConfigured(CameraCaptureSession cameraCaptureSession) {
					mPreviewSession = cameraCaptureSession;
					updatePreview();
				}

				@Override
				public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
					Activity activity = getActivity();
					if (null != activity) {
						Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
					}
				}
			}, mBackgroundHandler);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Update the camera preview. {@link #startPreview()} needs to be called in advance.
	 */
	private void updatePreview() {
		if (null == mCameraDevice) {
			return;
		}
		try {
			setUpCaptureRequestBuilder(mPreviewBuilder);
			HandlerThread thread = new HandlerThread("CameraPreview");
			thread.start();
			mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
		//builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
		builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
		mPreviewBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
		builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO);
		mPreviewBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO);
		builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<Integer>(30, 30));
		mPreviewBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<Integer>(30, 30));
	}

	/**
	 * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
	 * This method should not to be called until the camera preview size is determined in
	 * openCamera, or until the size of `mTextureView` is fixed.
	 *
	 * @param viewWidth  The width of `mTextureView`
	 * @param viewHeight The height of `mTextureView`
	 */
	private void configureTransform(int viewWidth, int viewHeight) {
		Activity activity = getActivity();
		if (null == mTextureView || null == mPreviewSize || null == activity) {
			return;
		}
		int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
		Matrix matrix = new Matrix();
		RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
		RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
		float centerX = viewRect.centerX();
		float centerY = viewRect.centerY();
		if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
			bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
			matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
			float scale = Math.max(
					(float) viewHeight / mPreviewSize.getHeight(),
					(float) viewWidth / mPreviewSize.getWidth());
			matrix.postScale(scale, scale, centerX, centerY);
			matrix.postRotate(90 * (rotation - 2), centerX, centerY);
		}
		mTextureView.setTransform(matrix);
	}

	private void setUpMediaRecorder() throws IOException {
		final Activity activity = getActivity();
		if (null == activity) {
			return;
		}
		mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
		mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

		mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
		if (mNextVideoAbsolutePath == null || mNextVideoAbsolutePath.isEmpty()) {
			mNextVideoAbsolutePath = getVideoFilePath(getActivity());
		}
		mMediaRecorder.setOutputFile(mNextVideoAbsolutePath);
		mMediaRecorder.setVideoEncodingBitRate(50 * 1000 * 1000);
		//mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
		mMediaRecorder.setVideoSize(3840, 2160);
		mMediaRecorder.setVideoFrameRate(32);
		mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
		mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
		mMediaRecorder.setAudioEncodingBitRate(192 * 1024);
		int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
		switch (mSensorOrientation) {
			case SENSOR_ORIENTATION_DEFAULT_DEGREES:
				mMediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation));
				break;
			case SENSOR_ORIENTATION_INVERSE_DEGREES:
				mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
				break;
		}

		mMediaRecorder.prepare();
	}

	private String getVideoFilePath(Context context) {
		return context.getExternalFilesDir(null).getAbsolutePath() + "/"
				+ System.currentTimeMillis() + ".mp4";
	}

	private void startRecordingVideo() {
		if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize) {
			return;
		}
		try {
			closePreviewSession();
			setUpMediaRecorder();
			SurfaceTexture texture = mTextureView.getSurfaceTexture();
			assert texture != null;
			texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
			mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
			List<Surface> surfaces = new ArrayList<>();

			// Set up Surface for the camera preview
			Surface previewSurface = new Surface(texture);
			surfaces.add(previewSurface);
			mPreviewBuilder.addTarget(previewSurface);

			// Set up Surface for the MediaRecorder
			mRecorderSurface = mMediaRecorder.getSurface();
			surfaces.add(mRecorderSurface);
			mPreviewBuilder.addTarget(mRecorderSurface);

			// Start a capture session
			// Once the session starts, we can update the UI and start recording
			mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {

				@Override
				public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
					mPreviewSession = cameraCaptureSession;
					updatePreview();
					getActivity().runOnUiThread(new Runnable() {
						@Override
						public void run() {
							// UI
							mButtonVideo.setText(R.string.stop);
							mIsRecordingVideo = true;

							// Start recording
							mMediaRecorder.start();
						}
					});
				}

				@Override
				public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
					Activity activity = getActivity();
					if (null != activity) {
						Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
					}
				}
			}, mBackgroundHandler);
		} catch (CameraAccessException | IOException e) {
			e.printStackTrace();
		}

	}

	private void closePreviewSession() {
		if (mPreviewSession != null) {
			mPreviewSession.close();
			mPreviewSession = null;
		}
	}

	private void stopRecordingVideo() {
		// UI
		mIsRecordingVideo = false;
		mButtonVideo.setText(R.string.record);
		// Stop recording
		mMediaRecorder.stop();
		mMediaRecorder.reset();

		Activity activity = getActivity();
		if (null != activity) {
			Toast.makeText(activity, "Video saved: " + mNextVideoAbsolutePath,
					Toast.LENGTH_SHORT).show();
			Log.d(TAG, "Video saved: " + mNextVideoAbsolutePath);
		}
		mNextVideoAbsolutePath = null;
		startPreview();
	}

	/**
	 * Compares two {@code Size}s based on their areas.
	 */
	static class CompareSizesByArea implements Comparator<Size> {

		@Override
		public int compare(Size lhs, Size rhs) {
			// We cast here to ensure the multiplications won't overflow
			return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
					(long) rhs.getWidth() * rhs.getHeight());
		}

	}

	public static class ErrorDialog extends DialogFragment {

		private static final String ARG_MESSAGE = "message";

		public static ErrorDialog newInstance(String message) {
			ErrorDialog dialog = new ErrorDialog();
			Bundle args = new Bundle();
			args.putString(ARG_MESSAGE, message);
			dialog.setArguments(args);
			return dialog;
		}

		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			final Activity activity = getActivity();
			return new AlertDialog.Builder(activity)
					.setMessage(getArguments().getString(ARG_MESSAGE))
					.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialogInterface, int i) {
							activity.finish();
						}
					})
					.create();
		}

	}

	public static class ConfirmationDialog extends DialogFragment {

		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			final Fragment parent = getParentFragment();
			return new AlertDialog.Builder(getActivity())
					.setMessage(R.string.permission_request)
					.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							FragmentCompat.requestPermissions(parent, VIDEO_PERMISSIONS,
									REQUEST_VIDEO_PERMISSIONS);
						}
					})
					.setNegativeButton(android.R.string.cancel,
							new DialogInterface.OnClickListener() {
								@Override
								public void onClick(DialogInterface dialog, int which) {
									parent.getActivity().finish();
								}
							})
					.create();
		}

	}

}
package org.artoolkitx.arx.arxj.camera;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/*
 *  CameraSurfaceImpl.java
 *  artoolkitX
 *
 *  This file is part of artoolkitX.
 *
 *  artoolkitX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  artoolkitX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with artoolkitX.  If not, see <http://www.gnu.org/licenses/>.
 *
 *  As a special exception, the copyright holders of this library give you
 *  permission to link this library with independent modules to produce an
 *  executable, regardless of the license terms of these independent modules, and to
 *  copy and distribute the resulting executable under terms of your choice,
 *  provided that you also meet, for each linked independent module, the terms and
 *  conditions of the license of that module. An independent module is a module
 *  which is neither derived from nor based on this library. If you modify this
 *  library, you may extend this exception to your version of the library, but you
 *  are not obligated to do so. If you do not wish to do so, delete this exception
 *  statement from your version.
 *
 *  Copyright 2018 Realmax, Inc.
 *  Copyright 2015-2016 Daqri, LLC.
 *  Copyright 2010-2015 ARToolworks, Inc.
 *
 *  Author(s): Philip Lamb, Thorsten Bux, John Wolf
 *
 */
public class CameraSurfaceImpl implements CameraSurface {

    public static boolean swap;
    public static boolean xflip;
    public static boolean yflip;

    public static boolean useNV21 = false;
    public static boolean useFlip = false;

    /**
     * Android logging tag for this class.
     */
    private static final String TAG = CameraSurfaceImpl.class.getSimpleName();
    private CameraDevice mCameraDevice;
    private ImageReader mImageReader;
    private Size mImageReaderVideoSize;
    private final Context mAppContext;

    private final CameraDevice.StateCallback mCamera2DeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera2DeviceInstance) {
            mCameraDevice = camera2DeviceInstance;
            startCaptureAndForwardFramesSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera2DeviceInstance) {
            camera2DeviceInstance.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera2DeviceInstance, int error) {
            camera2DeviceInstance.close();
            mCameraDevice = null;
        }
    };

    /**
     * Listener to inform of camera related events: start, frame, and stop.
     */
    private final CameraEventListener mCameraEventListener;
    /**
     * Tracks if SurfaceView instance was created.
     */
    private boolean mImageReaderCreated;

    public CameraSurfaceImpl(CameraEventListener cameraEventListener, Context appContext) {
        this.mCameraEventListener = cameraEventListener;
        this.mAppContext = appContext;

    }

    private Bitmap nv21ToBitmap565(byte[] nv21bytearray, int width, int height) {
        YuvImage yuvImage = new YuvImage(nv21bytearray, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, os);
        byte[] jpegByteArray = os.toByteArray();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.outConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(jpegByteArray, 0, jpegByteArray.length, options);
    }


    private final ImageReader.OnImageAvailableListener mImageAvailableAndProcessHandler = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {

            Image imageInstance = reader.acquireLatestImage();
            if (imageInstance == null || mImageReader == null) {
                //Note: This seems to happen quite often.
                Log.v(TAG, "onImageAvailable(): unable to acquire new image");
                return;
            }

            if (useNV21) {
                if (mCameraEventListener != null) {
                    byte[] nv21bytes = YUV_420_888toNV21(imageInstance);
                    Bitmap bitmap = nv21ToBitmap565(nv21bytes, imageInstance.getWidth(), imageInstance.getHeight());
                    Bitmap resized = Bitmap.createScaledBitmap(bitmap, imageInstance.getWidth() / 2, imageInstance.getHeight() / 2, true);
                    byte[] newNv21 = getNV21(imageInstance.getWidth() / 2, imageInstance.getHeight() / 2, resized);

                    if (useFlip) {
                        newNv21 = rotateNV21_working(newNv21, imageInstance.getWidth() / 2, imageInstance.getHeight() / 2, 180);
                    }

                    mCameraEventListener.cameraStreamFrame(newNv21, newNv21.length);
                }
            } else {
                // Get a ByteBuffer for each plane.
                final Image.Plane[] imagePlanes = imageInstance.getPlanes();
                final int imagePlaneCount = Math.min(4, imagePlanes.length); // We can handle up to 4 planes max.
                final ByteBuffer[] imageBuffers = new ByteBuffer[imagePlaneCount];
                final int[] imageBufferPixelStrides = new int[imagePlaneCount];
                final int[] imageBufferRowStrides = new int[imagePlaneCount];
                for (int i = 0; i < imagePlaneCount; i++) {
                    imageBuffers[i] = imagePlanes[i].getBuffer();
                    // For ImageFormat.YUV_420_888 the order of planes in the array returned by Image.getPlanes()
                    // is guaranteed such that plane #0 is always Y, plane #1 is always U (Cb), and plane #2 is always V (Cr).
                    // The Y-plane is guaranteed not to be interleaved with the U/V planes (in particular, pixel stride is
                    // always 1 in yPlane.getPixelStride()). The U/V planes are guaranteed to have the same row stride and
                    // pixel stride (in particular, uPlane.getRowStride() == vPlane.getRowStride() and uPlane.getPixelStride() == vPlane.getPixelStride(); ).
                    imageBufferPixelStrides[i] = imagePlanes[i].getPixelStride();
                    imageBufferRowStrides[i] = imagePlanes[i].getRowStride();
                }

                if (mCameraEventListener != null) {
                    mCameraEventListener.cameraStreamFrame(imageBuffers, imageBufferPixelStrides, imageBufferRowStrides);
                }
            }

            imageInstance.close();
        }

        private byte[] rotateNV21_working(final byte[] yuv,
                                          final int width,
                                          final int height,
                                          final int rotation) {
            if (rotation == 0) return yuv;
            if (rotation % 90 != 0 || rotation < 0 || rotation > 270) {
                throw new IllegalArgumentException("0 <= rotation < 360, rotation % 90 == 0");
            }

            final byte[] output = new byte[yuv.length];
            final int frameSize = width * height;
//            final boolean swap      = false;//rotation % 180 != 0;
//            final boolean xflip     = true;//rotation % 270 != 0;
//            final boolean yflip     = false;//rotation >= 180;

            for (int j = 0; j < height; j++) {
                for (int i = 0; i < width; i++) {
                    final int yIn = j * width + i;
                    final int uIn = frameSize + (j >> 1) * width + (i & ~1);
                    final int vIn = uIn + 1;

                    final int wOut = swap ? height : width;
                    final int hOut = swap ? width : height;
                    final int iSwapped = swap ? j : i;
                    final int jSwapped = swap ? i : j;
                    final int iOut = xflip ? wOut - iSwapped - 1 : iSwapped;
                    final int jOut = yflip ? hOut - jSwapped - 1 : jSwapped;

                    final int yOut = jOut * wOut + iOut;
                    final int uOut = frameSize + (jOut >> 1) * wOut + (iOut & ~1);
                    final int vOut = uOut + 1;

                    output[yOut] = (byte) (0xff & yuv[yIn]);
                    output[uOut] = (byte) (0xff & yuv[uIn]);
                    output[vOut] = (byte) (0xff & yuv[vIn]);
                }
            }
            return output;
        }

        private byte[] YUV_420_888toNV21(Image image) {

            int width = image.getWidth();
            int height = image.getHeight();
            int ySize = width * height;
            int uvSize = width * height / 4;

            byte[] nv21 = new byte[ySize + uvSize * 2];

            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer(); // Y
            ByteBuffer uBuffer = image.getPlanes()[1].getBuffer(); // U
            ByteBuffer vBuffer = image.getPlanes()[2].getBuffer(); // V

            int rowStride = image.getPlanes()[0].getRowStride();
            assert (image.getPlanes()[0].getPixelStride() == 1);

            int pos = 0;

            if (rowStride == width) { // likely
                yBuffer.get(nv21, 0, ySize);
                pos += ySize;
            } else {
                int yBufferPos = width - rowStride; // not an actual position
                for (; pos < ySize; pos += width) {
                    yBufferPos += rowStride - width;
                    yBuffer.position(yBufferPos);
                    yBuffer.get(nv21, pos, width);
                }
            }

            rowStride = image.getPlanes()[2].getRowStride();
            int pixelStride = image.getPlanes()[2].getPixelStride();

            assert (rowStride == image.getPlanes()[1].getRowStride());
            assert (pixelStride == image.getPlanes()[1].getPixelStride());

            if (pixelStride == 2 && rowStride == width && uBuffer.get(0) == vBuffer.get(1)) {
                // maybe V an U planes overlap as per NV21, which means vBuffer[1] is alias of uBuffer[0]
                byte savePixel = vBuffer.get(1);
                vBuffer.put(1, (byte) 0);
                if (uBuffer.get(0) == 0) {
                    vBuffer.put(1, (byte) 255);
                    if (uBuffer.get(0) == 255) {
                        vBuffer.put(1, savePixel);
                        vBuffer.get(nv21, ySize, uvSize);

                        return nv21; // shortcut
                    }
                }

                // unfortunately, the check failed. We must save U and V pixel by pixel
                vBuffer.put(1, savePixel);
            }

            // other optimizations could check if (pixelStride == 1) or (pixelStride == 2),
            // but performance gain would be less significant

            for (int row = 0; row < height / 2; row++) {
                for (int col = 0; col < width / 2; col++) {
                    int vuPos = col * pixelStride + row * rowStride;
                    nv21[pos++] = vBuffer.get(vuPos);
                    nv21[pos++] = uBuffer.get(vuPos);
                }
            }

            return nv21;
        }
    };

    @Override
    public void surfaceCreated() {
        Log.i(TAG, "surfaceCreated(): called");

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mAppContext);
        int defaultCameraIndexId = mAppContext.getResources().getIdentifier("pref_defaultValue_cameraIndex", "string", mAppContext.getPackageName());
        mCamera2DeviceID = Integer.parseInt(prefs.getString("pref_cameraIndex", mAppContext.getResources().getString(defaultCameraIndexId)));
        Log.i(TAG, "surfaceCreated(): will attempt to open camera \"" + mCamera2DeviceID +
                "\", set orientation, set preview surface");

        /*
        Set the resolution from the settings as size for the glView. Because the video stream capture
        is requested based on this size.

        WARNING: While coding the preferences are taken from the res/xml/preferences.xml!!!
        When building for Unity the actual used preferences are taken from the UnityARPlayer project!!!
        */
        int defaultCameraValueId = mAppContext.getResources().getIdentifier("pref_defaultValue_cameraResolution", "string", mAppContext.getPackageName());
        String camResolution = prefs.getString("pref_cameraResolution", mAppContext.getResources().getString(defaultCameraValueId));
        String[] dims = camResolution.split("x", 2);
        if (dims.length == 2) {
            mImageReaderVideoSize = new Size(Integer.parseInt(dims[0]), Integer.parseInt(dims[1]));
        } else {
            mImageReaderVideoSize = new Size(960, 540);
        }

        // Note that maxImages should be at least 2 for acquireLatestImage() to be any different than acquireNextImage() -
        // discarding all-but-the-newest Image requires temporarily acquiring two Images at once. Or more generally,
        // calling acquireLatestImage() with less than two images of margin, that is (maxImages - currentAcquiredImages < 2)
        // will not discard as expected.
        mImageReader = ImageReader.newInstance(mImageReaderVideoSize.getWidth(), mImageReaderVideoSize.getHeight(), ImageFormat.YUV_420_888, /* The maximum number of images the user will want to access simultaneously:*/ 2);
        mImageReader.setOnImageAvailableListener(mImageAvailableAndProcessHandler, null);

        mImageReaderCreated = true;

    } // end: public void surfaceCreated(SurfaceHolder holder)

    /* Interface implemented by this SurfaceView subclass
       holder: SurfaceHolder instance associated with SurfaceView instance that changed
       format: pixel format of the surface
       width: of the SurfaceView instance
       height: of the SurfaceView instance
    */
    @Override
    public void surfaceChanged() {
        Log.i(TAG, "surfaceChanged(): called");

        // This is where to calculate the optimal size of the display and set the aspect ratio
        // of the surface view (probably the service holder). Also where to Create transformation
        // matrix to scale and then rotate surface view, if the app is going to handle orientation
        // changes.
        if (!mImageReaderCreated) {
            surfaceCreated();
        }
        if (!isCamera2DeviceOpen()) {
            openCamera2(mCamera2DeviceID);
        }
        if (isCamera2DeviceOpen() && (null == mYUV_CaptureAndSendSession)) {
            startCaptureAndForwardFramesSession();
        }
    }

    private void openCamera2(int camera2DeviceID) {
        Log.i(TAG, "openCamera2(): called");
        CameraManager camera2DeviceMgr = (CameraManager) mAppContext.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(mAppContext, Manifest.permission.CAMERA)) {
                camera2DeviceMgr.openCamera(Integer.toString(camera2DeviceID), mCamera2DeviceStateCallback, null);
                return;
            }
        } catch (CameraAccessException ex) {
            Log.e(TAG, "openCamera2(): CameraAccessException caught, " + ex.getMessage());
        } catch (Exception ex) {
            Log.e(TAG, "openCamera2(): exception caught, " + ex.getMessage());
        }
        if (null == camera2DeviceMgr) {
            Log.e(TAG, "openCamera2(): Camera2 DeviceMgr not set");
        }
        Log.e(TAG, "openCamera2(): abnormal exit");
    }

    private int mCamera2DeviceID = -1;
    private CaptureRequest.Builder mCaptureRequestBuilder;
    private CameraCaptureSession mYUV_CaptureAndSendSession;

    private void startCaptureAndForwardFramesSession() {

        if ((null == mCameraDevice) || (!mImageReaderCreated) /*|| (null == mPreviewSize)*/) {
            return;
        }

        closeYUV_CaptureAndForwardSession();

        try {
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            List<Surface> surfaces = new ArrayList<>();

            Surface surfaceInstance;
            surfaceInstance = mImageReader.getSurface();
            surfaces.add(surfaceInstance);
            mCaptureRequestBuilder.addTarget(surfaceInstance);

            Log.d(TAG, "fps range : 15");
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new android.util.Range<>(15, 15));

            mCameraDevice.createCaptureSession(
                    surfaces, // Output surfaces
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                if (mCameraEventListener != null) {
                                    String pixelFormat = useNV21 ? "NV21" : "YUV_420_888";
                                    mCameraEventListener.cameraStreamStarted(
                                            useNV21 ? mImageReaderVideoSize.getWidth() / 2 : mImageReaderVideoSize.getWidth(),
                                            useNV21 ? mImageReaderVideoSize.getHeight() / 2 : mImageReaderVideoSize.getHeight(),
                                            pixelFormat,
                                            mCamera2DeviceID,
                                            false);
                                }
                                mYUV_CaptureAndSendSession = session;
                                // Session to repeat request to update passed in camSensorSurface
                                mYUV_CaptureAndSendSession.setRepeatingRequest(mCaptureRequestBuilder.build(), /* CameraCaptureSession.CaptureCallback cameraEventListener: */null, /* Background thread: */ null);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
//                            Toast.makeText(mAppContext, "Unable to setup camera sensor capture session", Toast.LENGTH_SHORT).show();
                        }
                    }, // Callback for capture session state updates
                    null); // Secondary thread message queue
        } catch (CameraAccessException ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void closeCameraDevice() {
        Log.d(TAG, "closeCameraDevice");

        closeYUV_CaptureAndForwardSession();
        if (null != mCameraDevice) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (null != mImageReader) {
            mImageReader.close();
            mImageReader = null;
        }
        if (mCameraEventListener != null) {
            mCameraEventListener.cameraStreamStopped();
        }
        mImageReaderCreated = false;
    }

    private void closeYUV_CaptureAndForwardSession() {
        if (mYUV_CaptureAndSendSession != null) {
            mYUV_CaptureAndSendSession.close();
            mYUV_CaptureAndSendSession = null;
        }
    }

    /**
     * Indicates whether or not camera2 device instance is available, opened, enabled.
     */
    @Override
    public boolean isCamera2DeviceOpen() {
        return (null != mCameraDevice);
    }

    @Override
    public boolean isImageReaderCreated() {
        return mImageReaderCreated;
    }


    byte[] getNV21(int inputWidth, int inputHeight, Bitmap scaled) {

        int[] argb = new int[inputWidth * inputHeight];

        scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight);

        byte[] yuv = new byte[inputWidth * inputHeight * 3 / 2];
        encodeYUV420SP(yuv, argb, inputWidth, inputHeight);

        scaled.recycle();

        return yuv;
    }

    void encodeYUV420SP(byte[] yuv420sp, int[] argb, int width, int height) {
        final int frameSize = width * height;

        int yIndex = 0;
        int uvIndex = frameSize;

        int a, R, G, B, Y, U, V;
        int index = 0;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {

                a = (argb[index] & 0xff000000) >> 24; // a is not used obviously
                R = (argb[index] & 0xff0000) >> 16;
                G = (argb[index] & 0xff00) >> 8;
                B = (argb[index] & 0xff) >> 0;

                // well known RGB to YUV algorithm
                Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;

                // NV21 has a plane of Y and interleaved planes of VU each sampled by a factor of 2
                //    meaning for every 4 Y pixels there are 1 V and 1 U.  Note the sampling is every other
                //    pixel AND every other scanline.
                yuv420sp[yIndex++] = (byte) ((Y < 0) ? 0 : ((Y > 255) ? 255 : Y));
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = (byte) ((V < 0) ? 0 : ((V > 255) ? 255 : V));
                    yuv420sp[uvIndex++] = (byte) ((U < 0) ? 0 : ((U > 255) ? 255 : U));
                }

                index++;
            }
        }
    }
}

package local.son.connectdiagnostics;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;

import java.util.Collections;

public final class CameraProbe {
    public interface Listener {
        void onStatus(String message);
        void onFaces(int count);
    }

    private final TextureView preview;
    private final Listener listener;
    private final CameraManager cameras;
    private final DisplayManager displays;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Camera ownership and view changes are serialized on the main thread.
    private String requestedId;
    private int generation;
    private boolean opening;
    private int openingGeneration;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private Surface surface;
    private Size bufferSize;
    private int sensorOrientation;
    private HandlerThread resultThread;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) { }

        @Override public void onDisplayChanged(int displayId) {
            Display display = preview.getDisplay();
            if (display != null && display.getDisplayId() == displayId) {
                updateTransform();
            }
        }
    };

    public CameraProbe(Activity activity, TextureView preview, Listener listener) {
        this.preview = preview;
        this.listener = listener;
        cameras = activity.getSystemService(CameraManager.class);
        displays = activity.getSystemService(DisplayManager.class);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                openIfReady();
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                updateTransform();
            }

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                generation++;
                closePreview();
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
        });
    }

    public String[] cameraIds() {
        try {
            return cameras.getCameraIdList();
        } catch (CameraAccessException | SecurityException error) {
            listener.onStatus("카메라 목록을 불러오지 못했습니다.");
            return new String[0];
        }
    }

    public String describe(String id) {
        try {
            CameraCharacteristics info = cameras.getCameraCharacteristics(id);
            Integer facing = info.get(CameraCharacteristics.LENS_FACING);
            String position = "방향 알 수 없음";
            if (facing != null) {
                switch (facing) {
                    case CameraCharacteristics.LENS_FACING_FRONT: position = "전면"; break;
                    case CameraCharacteristics.LENS_FACING_BACK: position = "후면"; break;
                    case CameraCharacteristics.LENS_FACING_EXTERNAL: position = "외장"; break;
                    default: break;
                }
            }
            return "카메라 " + id + " · " + position + " · " + faceModeLabel(faceMode(info));
        } catch (CameraAccessException | IllegalArgumentException | SecurityException error) {
            return "카메라 " + id + " · 정보 조회 실패";
        }
    }

    /** Call on the main thread after CAMERA permission has been granted. */
    public void start(String id) {
        stop();
        requestedId = id;
        displays.registerDisplayListener(displayListener, main);
        listener.onFaces(0);
        listener.onStatus("카메라 여는 중");
        openIfReady();
    }

    /** Call on the main thread when leaving the preview or pausing the activity. */
    public void stop() {
        generation++;
        if (requestedId != null) {
            displays.unregisterDisplayListener(displayListener);
        }
        requestedId = null;
        closePreview();
    }

    @SuppressLint("MissingPermission")
    private void openIfReady() {
        SurfaceTexture texture = preview.getSurfaceTexture();
        if (requestedId == null || texture == null || opening || camera != null) {
            return;
        }
        final int attempt = generation;
        try {
            CameraCharacteristics info = cameras.getCameraCharacteristics(requestedId);
            Integer orientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation != null ? orientation : 0;
            bufferSize = chooseSize(info);
            if (bufferSize == null) {
                fail("미리보기를 지원하지 않는 카메라입니다.");
                return;
            }
            opening = true;
            openingGeneration = attempt;
            cameras.openCamera(requestedId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    if (attempt != generation) {
                        device.close();
                        openAfterStaleCallback(attempt);
                        return;
                    }
                    opening = false;
                    camera = device;
                    createPreview(attempt, texture, faceMode(info));
                }

                @Override public void onDisconnected(CameraDevice device) {
                    handleDeviceFailure(attempt, device, "카메라 연결이 끊겼습니다.");
                }

                @Override public void onError(CameraDevice device, int error) {
                    handleDeviceFailure(attempt, device, "카메라 오류 (" + error + ")");
                }
            }, main);
        } catch (SecurityException error) {
            opening = false;
            fail("카메라 권한 없음");
        } catch (CameraAccessException | IllegalArgumentException error) {
            opening = false;
            fail("카메라를 열지 못했습니다.");
        }
    }

    private void handleDeviceFailure(int attempt, CameraDevice device, String message) {
        device.close();
        if (attempt == generation) {
            opening = false;
            fail(message);
        } else {
            // A stale open must finish before the next open begins. A stale
            // error from a previously closed device must not clear a new open.
            openAfterStaleCallback(attempt);
        }
    }

    private void openAfterStaleCallback(int attempt) {
        if (opening && attempt == openingGeneration) {
            opening = false;
            openIfReady();
        }
    }

    @SuppressWarnings("deprecation")
    private void createPreview(int attempt, SurfaceTexture texture, int faceMode) {
        try {
            texture.setDefaultBufferSize(bufferSize.getWidth(), bufferSize.getHeight());
            surface = new Surface(texture);
            updateTransform();

            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(surface);
            if (faceMode != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) {
                request.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceMode);
            }

            camera.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (attempt != generation) {
                        configured.close();
                        return;
                    }
                    session = configured;
                    startRepeating(attempt, request.build(), faceMode);
                }

                @Override public void onConfigureFailed(CameraCaptureSession configured) {
                    configured.close();
                    if (attempt == generation) {
                        fail("카메라 미리보기를 설정하지 못했습니다.");
                    }
                }
            }, main);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            fail("카메라 미리보기를 시작하지 못했습니다.");
        }
    }

    private void startRepeating(int attempt, CaptureRequest request, int mode) {
        try {
            CameraCaptureSession.CaptureCallback callback = null;
            Handler results = null;
            if (mode != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) {
                resultThread = new HandlerThread("camera-faces");
                resultThread.start();
                results = new Handler(resultThread.getLooper());
                callback = new CameraCaptureSession.CaptureCallback() {
                    private int previousCount = -1;

                    @Override public void onCaptureCompleted(CameraCaptureSession source, CaptureRequest capture,
                                                             TotalCaptureResult result) {
                        Face[] faces = result.get(CaptureResult.STATISTICS_FACES);
                        if (faces == null || faces.length == previousCount) {
                            return;
                        }
                        int count = faces.length;
                        previousCount = count;
                        main.post(() -> {
                            if (attempt == generation) {
                                listener.onFaces(count);
                            }
                        });
                    }
                };
            }
            session.setRepeatingRequest(request, callback, results);
            listener.onStatus("미리보기 · " + faceModeLabel(mode));
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            fail("카메라 미리보기를 시작하지 못했습니다.");
        }
    }

    private void fail(String message) {
        stop();
        listener.onFaces(0);
        listener.onStatus(message);
    }

    private void closePreview() {
        if (session != null) {
            session.close();
            session = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (resultThread != null) {
            resultThread.quitSafely();
            resultThread = null;
        }
        bufferSize = null;
    }

    private void updateTransform() {
        int width = preview.getWidth();
        int height = preview.getHeight();
        Display display = preview.getDisplay();
        if (bufferSize == null || display == null || width == 0 || height == 0) {
            return;
        }
        int rotation = display.getRotation() * 90;
        boolean sensorSwapsAxes = sensorOrientation % 180 != 0;
        float naturalWidth = sensorSwapsAxes ? bufferSize.getHeight() : bufferSize.getWidth();
        float naturalHeight = sensorSwapsAxes ? bufferSize.getWidth() : bufferSize.getHeight();
        boolean displaySwapsAxes = rotation % 180 != 0;
        float shownWidth = displaySwapsAxes ? naturalHeight : naturalWidth;
        float shownHeight = displaySwapsAxes ? naturalWidth : naturalHeight;
        float scale = Math.min(width / shownWidth, height / shownHeight);
        float centerX = width / 2f;
        float centerY = height / 2f;
        float halfWidth = naturalWidth * scale / 2f;
        float halfHeight = naturalHeight * scale / 2f;

        // Camera2's surface transform already applies sensor rotation and the front-camera mirror.
        // Correct the view's stretching and display rotation without mirroring it a second time.
        Matrix transform = new Matrix();
        transform.setRectToRect(new RectF(0, 0, width, height),
                new RectF(centerX - halfWidth, centerY - halfHeight,
                        centerX + halfWidth, centerY + halfHeight), Matrix.ScaleToFit.FILL);
        transform.postRotate(-rotation, centerX, centerY);
        preview.setTransform(transform);
    }

    private static Size chooseSize(CameraCharacteristics info) {
        StreamConfigurationMap map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        Size smallest = sizes[0];
        Size best = null;
        for (Size size : sizes) {
            if (area(size) < area(smallest)) {
                smallest = size;
            }
            int longer = Math.max(size.getWidth(), size.getHeight());
            int shorter = Math.min(size.getWidth(), size.getHeight());
            if (longer <= 1280 && shorter <= 720 && (best == null || area(size) > area(best))) {
                best = size;
            }
        }
        return best != null ? best : smallest;
    }

    private static long area(Size size) {
        return (long) size.getWidth() * size.getHeight();
    }

    private static int faceMode(CameraCharacteristics info) {
        int[] modes = info.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
        Integer maxFaces = info.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT);
        int selected = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF;
        if (modes != null && maxFaces != null && maxFaces > 0) {
            for (int mode : modes) {
                if (mode == CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE) {
                    return mode;
                }
                if (mode == CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL) {
                    selected = mode;
                }
            }
        }
        return selected;
    }

    private static String faceModeLabel(int mode) {
        switch (mode) {
            case CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE: return "얼굴 수 감지 (SIMPLE)";
            case CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL: return "얼굴 수 감지 (FULL)";
            default: return "얼굴 감지 미지원";
        }
    }
}

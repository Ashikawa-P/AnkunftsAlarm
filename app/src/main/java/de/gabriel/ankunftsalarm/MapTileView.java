package de.gabriel.ankunftsalarm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Lightweight OpenStreetMap view without an SDK dependency. It supports dragging,
 * pinch zooming and selecting an arbitrary geographic point.
 */
public final class MapTileView extends View {
    public interface OnPointSelectedListener {
        void onPointSelected(double latitude, double longitude);
    }

    private static final double MAX_LATITUDE = 85.05112878;
    private static final double EARTH_RADIUS_METERS = 6_378_137.0;
    private static final int MIN_ZOOM = 3;
    private static final int MAX_ZOOM = 18;
    private static final long FAILED_TILE_RETRY_DELAY_MS = 30_000L;

    private final Paint tilePlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint radiusFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint radiusStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint locationPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint locationRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService tileExecutor = Executors.newFixedThreadPool(4);
    private final Set<String> loadingTiles = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> retryAfterByTile = new ConcurrentHashMap<>();
    private final File tileCacheDirectory;
    private final float tileSize;

    private final LruCache<String, Bitmap> memoryCache = new LruCache<String, Bitmap>(32 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleGestureDetector;

    private double centerLatitude = 51.1657;
    private double centerLongitude = 10.4515;
    private int zoom = 6;
    private boolean selectable = true;
    private boolean hasTarget;
    private double targetLatitude;
    private double targetLongitude;
    private int radiusMeters = AppState.DEFAULT_RADIUS_METERS;
    private boolean hasCurrentLocation;
    private double currentLatitude;
    private double currentLongitude;
    private float scaleAccumulator = 1f;
    private OnPointSelectedListener pointSelectedListener;

    public MapTileView(Context context) {
        this(context, null);
    }

    public MapTileView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(Color.rgb(238, 242, 246));
        tileSize = 256f;
        tileCacheDirectory = new File(context.getCacheDir(), "map_tiles");
        //noinspection ResultOfMethodCallIgnored
        tileCacheDirectory.mkdirs();

        tilePlaceholderPaint.setColor(Color.rgb(229, 235, 240));
        gridPaint.setColor(Color.rgb(205, 214, 222));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1));

        radiusFillPaint.setColor(Color.argb(48, 25, 118, 210));
        radiusFillPaint.setStyle(Paint.Style.FILL);
        radiusStrokePaint.setColor(Color.rgb(21, 101, 192));
        radiusStrokePaint.setStyle(Paint.Style.STROKE);
        radiusStrokePaint.setStrokeWidth(dp(2.5f));

        targetPaint.setColor(Color.rgb(198, 40, 40));
        targetPaint.setStrokeWidth(dp(3));
        targetPaint.setStyle(Paint.Style.FILL);
        targetPaint.setStrokeCap(Paint.Cap.ROUND);

        locationPaint.setColor(Color.rgb(25, 118, 210));
        locationPaint.setStyle(Paint.Style.FILL);
        locationRingPaint.setColor(Color.WHITE);
        locationRingPaint.setStyle(Paint.Style.STROKE);
        locationRingPaint.setStrokeWidth(dp(3));

        textPaint.setColor(Color.rgb(65, 75, 85));
        textPaint.setTextSize(dp(11));
        textPaint.setShadowLayer(dp(2), 0, dp(1), Color.WHITE);

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent first, MotionEvent current, float distanceX, float distanceY) {
                panBy(distanceX, distanceY);
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent event) {
                if (!selectable || scaleGestureDetector.isInProgress()) {
                    return false;
                }
                double[] point = screenToGeo(event.getX(), event.getY());
                setTarget(point[0], point[1], radiusMeters);
                if (pointSelectedListener != null) {
                    pointSelectedListener.onPointSelected(point[0], point[1]);
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                setZoom(zoom + 1);
                return true;
            }
        });

        scaleGestureDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        scaleAccumulator = 1f;
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        scaleAccumulator *= detector.getScaleFactor();
                        if (scaleAccumulator > 1.2f) {
                            setZoom(zoom + 1);
                            scaleAccumulator = 1f;
                        } else if (scaleAccumulator < 0.8f) {
                            setZoom(zoom - 1);
                            scaleAccumulator = 1f;
                        }
                        return true;
                    }
                });
    }

    public void setOnPointSelectedListener(OnPointSelectedListener listener) {
        pointSelectedListener = listener;
    }

    public void setSelectable(boolean selectable) {
        this.selectable = selectable;
    }

    public void setCenter(double latitude, double longitude, int zoom) {
        centerLatitude = clampLatitude(latitude);
        centerLongitude = normalizeLongitude(longitude);
        this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        invalidate();
    }

    public void setTarget(double latitude, double longitude, int radiusMeters) {
        hasTarget = true;
        targetLatitude = clampLatitude(latitude);
        targetLongitude = normalizeLongitude(longitude);
        this.radiusMeters = radiusMeters;
        invalidate();
    }

    public void clearTarget() {
        hasTarget = false;
        invalidate();
    }

    public void setRadiusMeters(int radiusMeters) {
        this.radiusMeters = radiusMeters;
        invalidate();
    }

    public void setCurrentLocation(double latitude, double longitude) {
        hasCurrentLocation = true;
        currentLatitude = latitude;
        currentLongitude = longitude;
        invalidate();
    }

    public void clearCurrentLocation() {
        hasCurrentLocation = false;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean scaled = scaleGestureDetector.onTouchEvent(event);
        boolean gestured = gestureDetector.onTouchEvent(event);
        return scaled || gestured || super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(238, 242, 246));
        drawTiles(canvas);
        drawRadiusAndMarkers(canvas);
        canvas.drawText("© OpenStreetMap-Mitwirkende", dp(8), getHeight() - dp(8), textPaint);
    }

    private void drawTiles(Canvas canvas) {
        double worldSize = worldSize();
        double centerX = longitudeToWorldX(centerLongitude, worldSize);
        double centerY = latitudeToWorldY(centerLatitude, worldSize);
        double left = centerX - getWidth() / 2.0;
        double top = centerY - getHeight() / 2.0;

        int firstTileX = (int) Math.floor(left / tileSize);
        int lastTileX = (int) Math.floor((left + getWidth()) / tileSize);
        int firstTileY = (int) Math.floor(top / tileSize);
        int lastTileY = (int) Math.floor((top + getHeight()) / tileSize);
        int tileCount = 1 << zoom;

        for (int tileY = firstTileY; tileY <= lastTileY; tileY++) {
            if (tileY < 0 || tileY >= tileCount) {
                continue;
            }
            for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
                int wrappedX = ((tileX % tileCount) + tileCount) % tileCount;
                float screenX = (float) (tileX * tileSize - left);
                float screenY = (float) (tileY * tileSize - top);
                RectF destination = new RectF(screenX, screenY,
                        screenX + tileSize, screenY + tileSize);
                String key = zoom + "_" + wrappedX + "_" + tileY;
                Bitmap bitmap = memoryCache.get(key);
                if (bitmap != null && !bitmap.isRecycled()) {
                    canvas.drawBitmap(bitmap, null, destination, null);
                } else {
                    canvas.drawRect(destination, tilePlaceholderPaint);
                    canvas.drawRect(destination, gridPaint);
                    requestTile(key, zoom, wrappedX, tileY);
                }
            }
        }
    }

    private void drawRadiusAndMarkers(Canvas canvas) {
        if (hasTarget) {
            float[] target = geoToScreen(targetLatitude, targetLongitude);
            double pixelsPerMeter = worldSize()
                    / (Math.cos(Math.toRadians(targetLatitude))
                    * 2.0 * Math.PI * EARTH_RADIUS_METERS);
            float radiusPixels = (float) Math.max(dp(3), radiusMeters * pixelsPerMeter);
            canvas.drawCircle(target[0], target[1], radiusPixels, radiusFillPaint);
            canvas.drawCircle(target[0], target[1], radiusPixels, radiusStrokePaint);

            float pinRadius = dp(7);
            canvas.drawCircle(target[0], target[1], pinRadius, targetPaint);
            canvas.drawLine(target[0], target[1] + pinRadius,
                    target[0], target[1] + dp(18), targetPaint);
        }

        if (hasCurrentLocation) {
            float[] current = geoToScreen(currentLatitude, currentLongitude);
            canvas.drawCircle(current[0], current[1], dp(8), locationPaint);
            canvas.drawCircle(current[0], current[1], dp(8), locationRingPaint);
        }
    }

    private void requestTile(String key, int zoom, int x, int y) {
        Long retryAfter = retryAfterByTile.get(key);
        if (retryAfter != null && System.currentTimeMillis() < retryAfter) {
            return;
        }
        if (!loadingTiles.add(key)) {
            return;
        }
        try {
            tileExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;
                File cacheFile = new File(tileCacheDirectory, key + ".png");
                File temporaryFile = new File(tileCacheDirectory, key + ".download");
                HttpURLConnection connection = null;
                try {
                    if (cacheFile.isFile()) {
                        bitmap = BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
                        if (bitmap == null) {
                            //noinspection ResultOfMethodCallIgnored
                            cacheFile.delete();
                        }
                    }
                    if (bitmap == null) {
                        URL url = new URL(String.format(Locale.US,
                                "https://tile.openstreetmap.org/%d/%d/%d.png", zoom, x, y));
                        connection = (HttpURLConnection) url.openConnection();
                        connection.setConnectTimeout(8_000);
                        connection.setReadTimeout(8_000);
                        connection.setRequestProperty("User-Agent",
                                "AnkunftsAlarm/2.2 (Android; arrival reminder)");
                        connection.connect();
                        if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                            try (InputStream input = connection.getInputStream()) {
                                bitmap = BitmapFactory.decodeStream(input);
                            }
                            if (bitmap != null) {
                                try (FileOutputStream output = new FileOutputStream(temporaryFile)) {
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                                    output.flush();
                                }
                                if (cacheFile.exists()) {
                                    //noinspection ResultOfMethodCallIgnored
                                    cacheFile.delete();
                                }
                                if (!temporaryFile.renameTo(cacheFile)) {
                                    //noinspection ResultOfMethodCallIgnored
                                    temporaryFile.delete();
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // The grid remains usable when the network is temporarily unavailable.
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                    if (temporaryFile.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        temporaryFile.delete();
                    }
                }

                final Bitmap loaded = bitmap;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        loadingTiles.remove(key);
                        if (loaded != null) {
                            retryAfterByTile.remove(key);
                            memoryCache.put(key, loaded);
                            postInvalidateOnAnimation();
                        } else {
                            retryAfterByTile.put(
                                    key,
                                    System.currentTimeMillis() + FAILED_TILE_RETRY_DELAY_MS
                            );
                        }
                    }
                });
            }
            });
        } catch (RejectedExecutionException ignored) {
            loadingTiles.remove(key);
        }
    }

    private void panBy(float distanceX, float distanceY) {
        double size = worldSize();
        double x = longitudeToWorldX(centerLongitude, size) + distanceX;
        double y = latitudeToWorldY(centerLatitude, size) + distanceY;
        centerLongitude = normalizeLongitude(worldXToLongitude(x, size));
        centerLatitude = clampLatitude(worldYToLatitude(y, size));
        invalidate();
    }

    private void setZoom(int newZoom) {
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
        invalidate();
    }

    private float[] geoToScreen(double latitude, double longitude) {
        double size = worldSize();
        double centerX = longitudeToWorldX(centerLongitude, size);
        double targetX = longitudeToWorldX(longitude, size);
        double deltaX = targetX - centerX;
        if (deltaX > size / 2.0) {
            deltaX -= size;
        } else if (deltaX < -size / 2.0) {
            deltaX += size;
        }
        return new float[]{
                (float) (getWidth() / 2.0 + deltaX),
                (float) (getHeight() / 2.0
                        + latitudeToWorldY(latitude, size)
                        - latitudeToWorldY(centerLatitude, size))
        };
    }

    private double[] screenToGeo(float screenX, float screenY) {
        double size = worldSize();
        double worldX = longitudeToWorldX(centerLongitude, size)
                + screenX - getWidth() / 2.0;
        double worldY = latitudeToWorldY(centerLatitude, size)
                + screenY - getHeight() / 2.0;
        return new double[]{
                clampLatitude(worldYToLatitude(worldY, size)),
                normalizeLongitude(worldXToLongitude(worldX, size))
        };
    }

    private double worldSize() {
        return tileSize * (1 << zoom);
    }

    private static double longitudeToWorldX(double longitude, double size) {
        return (longitude + 180.0) / 360.0 * size;
    }

    private static double latitudeToWorldY(double latitude, double size) {
        double safeLatitude = clampLatitude(latitude);
        double sin = Math.sin(Math.toRadians(safeLatitude));
        return (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * size;
    }

    private static double worldXToLongitude(double x, double size) {
        return x / size * 360.0 - 180.0;
    }

    private static double worldYToLatitude(double y, double size) {
        double mercator = Math.PI - 2.0 * Math.PI * y / size;
        return Math.toDegrees(Math.atan(Math.sinh(mercator)));
    }

    private static double clampLatitude(double latitude) {
        return Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude));
    }

    private static double normalizeLongitude(double longitude) {
        double normalized = (longitude + 180.0) % 360.0;
        if (normalized < 0) {
            normalized += 360.0;
        }
        return normalized - 180.0;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        tileExecutor.shutdownNow();
    }
}

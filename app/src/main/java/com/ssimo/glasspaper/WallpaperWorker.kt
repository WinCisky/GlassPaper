package com.ssimo.glasspaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max // For Math.max
import androidx.core.content.edit

class WallpaperWorker(val appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    // Define a small epsilon for comparing float aspect ratios
    private val ASPECT_RATIO_EPSILON = 0.01f

    override suspend fun doWork(): Result {
        Log.i(TAG, "Worker started.")
        return withContext(Dispatchers.IO) {
            try {
                val htmlPageUrl = "https://wallpapers.opentrust.it/"
                val imageUrl = getImageUrlFromHtml(htmlPageUrl)

                if (imageUrl.isNullOrBlank()) {
                    Log.e(TAG, "Could not extract a valid image URL from: $htmlPageUrl")
                    return@withContext Result.failure()
                }

                Log.d(TAG, "Extracted image URL: $imageUrl")
                // Get screen dimensions once for use in processing and potentially logging
                val (screenWidth, screenHeight) = getScreenDimensions()
                val bitmap = downloadAndProcessImage(imageUrl, screenWidth, screenHeight)

                if (bitmap != null) {
                    Log.d(TAG, "Image download and processing successful. Final Bitmap: ${bitmap.width}x${bitmap.height}")
                    val success = setWallpaper(bitmap)
                    // Consider recycling the bitmap if it's confirmed WallpaperManager is done with it.
                    // For safety, often not explicitly recycled here unless memory pressure is extreme.
                    // if (success) bitmap.recycle() // Be very careful with this line

                    if (success) {
                        val sharedPrefs = appContext.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                        sharedPrefs.edit {
                            putLong(
                                "last_successful_run_time",
                                System.currentTimeMillis()
                            )
                        }
                        Log.d(TAG, "Wallpaper changed successfully and last run time stored.")
                        Result.success()
                    } else {
                        Log.e(TAG, "Failed to set wallpaper.")
                        Result.failure()
                    }
                } else {
                    Log.e(TAG, "Image download or processing failed for URL: $imageUrl")
                    Result.failure()
                }
            } catch (e: Exception) {
                Log.e(TAG, "An error occurred in doWork", e)
                Result.failure()
            }
        }
    }

    private fun getImageUrlFromHtml(pageUrl: String): String? {
        try {
            Log.d(TAG, "Fetching HTML from: $pageUrl")
            val document = Jsoup.connect(pageUrl)
                .timeout(10000) // 10 second timeout for connection
                .get()

            var extractedUrl = document.body()?.text()?.trim()
            if (extractedUrl != null && (extractedUrl.startsWith("http://") || extractedUrl.startsWith("https://"))) {
                Log.d(TAG, "Potential image URL (from body text): $extractedUrl")
                return extractedUrl
            }

            val firstImg = document.body()?.selectFirst("img")
            extractedUrl = firstImg?.attr("abs:src")
            if (extractedUrl != null && (extractedUrl.startsWith("http://") || extractedUrl.startsWith("https://"))) {
                Log.d(TAG, "Potential image URL (from first <img> tag): $extractedUrl")
                return extractedUrl
            }

            Log.e(TAG, "Could not find a valid image URL on $pageUrl.")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing HTML or extracting image URL from $pageUrl", e)
            return null
        }
    }

    private fun downloadAndProcessImage(
        urlString: String,
        screenWidth: Int, // Pass screen dimensions
        screenHeight: Int
    ): Bitmap? {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var newConnection: HttpURLConnection? = null
        var finalInputStream: InputStream? = null
        var initialBitmap: Bitmap? = null // To hold the bitmap after initial sampling

        try {
            val url = URL(urlString)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }

            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doInput = true
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Server returned HTTP ${connection.responseCode} for URL: $urlString")
                return null
            }
            inputStream = connection.inputStream.buffered()
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()
            connection.disconnect()

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.e(TAG, "Failed to decode image bounds. URL: $urlString")
                return null
            }

            options.inSampleSize = calculateInSampleSize(options, screenWidth, screenHeight)
            options.inJustDecodeBounds = false
            Log.d(TAG, "Decoding image with inSampleSize: ${options.inSampleSize}. Original: ${options.outWidth}x${options.outHeight}, Target Screen: ${screenWidth}x${screenHeight}")

            newConnection = url.openConnection() as HttpURLConnection
            newConnection.connectTimeout = 15000
            newConnection.readTimeout = 15000
            newConnection.doInput = true
            newConnection.connect()

            if (newConnection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Server returned HTTP ${newConnection.responseCode} (2nd attempt) for URL: $urlString")
                return null
            }
            finalInputStream = newConnection.inputStream.buffered()
            initialBitmap = BitmapFactory.decodeStream(finalInputStream, null, options)

            if (initialBitmap == null) {
                Log.e(TAG, "BitmapFactory.decodeStream returned null after sampling. URL: $urlString")
                return null
            }
            Log.d(TAG, "Initial decoded bitmap. Dimensions: ${initialBitmap.width}x${initialBitmap.height}")

            // --- Aspect Ratio Cropping ---
            val currentBitmapWidth = initialBitmap.width.toFloat()
            val currentBitmapHeight = initialBitmap.height.toFloat()

            if (currentBitmapWidth <= 0 || currentBitmapHeight <= 0) {
                Log.e(TAG, "Initial bitmap has invalid dimensions. Cannot crop.")
                return initialBitmap // Return as is, or handle error
            }

            val screenAspectRatio = screenWidth.toFloat() / screenHeight.toFloat()
            val imageAspectRatio = currentBitmapWidth / currentBitmapHeight

            var finalBitmapToSet = initialBitmap // Default to initial if no cropping happens or fails

            if (abs(imageAspectRatio - screenAspectRatio) > ASPECT_RATIO_EPSILON) {
                Log.d(TAG, "Image AR (${imageAspectRatio}) differs from screen AR (${screenAspectRatio}). Cropping.")

                var cropWidth: Int
                var cropHeight: Int
                var cropX: Int
                var cropY: Int

                if (imageAspectRatio > screenAspectRatio) {
                    // Image is wider than screen AR (e.g., landscape image on portrait screen)
                    // Keep full image height, crop width
                    cropHeight = currentBitmapHeight.toInt()
                    cropWidth = max(1, (screenAspectRatio * currentBitmapHeight).toInt())
                    cropX = max(0, ((currentBitmapWidth - cropWidth) / 2f).toInt())
                    cropY = 0
                } else {
                    // Image is taller than screen AR (e.g., portrait image on landscape screen)
                    // Keep full image width, crop height
                    cropWidth = currentBitmapWidth.toInt()
                    cropHeight = max(1, (currentBitmapWidth / screenAspectRatio).toInt())
                    cropX = 0
                    cropY = max(0, ((currentBitmapHeight - cropHeight) / 2f).toInt())
                }

                // Ensure calculated crop dimensions are valid and within bounds
                if (cropWidth > 0 && cropHeight > 0 &&
                    cropX >= 0 && (cropX + cropWidth) <= currentBitmapWidth &&
                    cropY >= 0 && (cropY + cropHeight) <= currentBitmapHeight) {

                    Log.d(TAG, "Calculated crop: x=$cropX, y=$cropY, w=$cropWidth, h=$cropHeight from ${initialBitmap.width}x${initialBitmap.height}")
                    try {
                        val cropped = Bitmap.createBitmap(initialBitmap, cropX, cropY, cropWidth, cropHeight)
                        // If createBitmap returns a new bitmap, recycle the old one.
                        if (initialBitmap != cropped) {
                            initialBitmap.recycle()
                        }
                        finalBitmapToSet = cropped
                        Log.d(TAG, "Bitmap successfully cropped to: ${finalBitmapToSet.width}x${finalBitmapToSet.height}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during Bitmap.createBitmap for cropping. Using uncropped (but sampled) bitmap.", e)
                        // finalBitmapToSet remains initialBitmap in case of error during createBitmap
                    }
                } else {
                    Log.w(TAG, "Invalid crop dimensions after calculation: x=$cropX, y=$cropY, w=$cropWidth, h=$cropHeight. Source: ${initialBitmap.width}x${initialBitmap.height}. Using uncropped (but sampled) bitmap.")
                    // finalBitmapToSet remains initialBitmap
                }
            } else {
                Log.d(TAG, "Image aspect ratio matches screen. No crop needed beyond initial sampling.")
                // finalBitmapToSet is already initialBitmap
            }
            return finalBitmapToSet

        } catch (e: Exception) {
            Log.e(TAG, "Error downloading/processing image from $urlString", e)
            initialBitmap?.recycle() // Clean up if an error occurred after initial decode
            return null
        } finally {
            inputStream?.close()
            connection?.disconnect()
            finalInputStream?.close()
            newConnection?.disconnect()
        }
    }

    private fun getScreenDimensions(): Pair<Int, Int> {
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val size = Point()
            @Suppress("DEPRECATION")
            display.getSize(size)
            Pair(size.x, size.y)
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun setWallpaper(bitmap: Bitmap): Boolean {
        val wallpaperManager = WallpaperManager.getInstance(appContext)
        Log.d(TAG, "WallpaperManager instance obtained.")
        try {
            Log.d(TAG, "Attempting to set bitmap as wallpaper. Final Bitmap dimensions: ${bitmap.width}x${bitmap.height}")
            wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            Log.d(TAG, "WallpaperManager.setBitmap call completed.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting wallpaper", e)
            return false
        }
    }

    companion object {
        const val WORK_NAME = "WallpaperWorker"
        private const val TAG = "WallpaperWorker"
    }
}
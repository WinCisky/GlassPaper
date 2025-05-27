package com.ssimo.glasspaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class WallpaperWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val TAG = "WallpaperWorker"

    override suspend fun doWork(): Result {
        Log.i(TAG, "Worker started.")
        return withContext(Dispatchers.IO) {
            try {
                val basePageUrl = "https://wallpapers.opentrust.it/"
                val imageUrl = getImageUrlFromHtml(basePageUrl)
                Log.d(TAG, "Downloading image from: $imageUrl")

                if (imageUrl != null) {
                    Log.d(TAG, "Extracted image URL: $imageUrl")
                    val bitmap = downloadImage(imageUrl)

                    if (bitmap != null) {
                        Log.d(TAG, "Image download successful.")
                        setWallpaper(bitmap)
                        Log.i(TAG, "Wallpaper set successfully. Worker finished.")
                        Result.success()
                    } else {
                        Log.e(TAG, "Image download failed for URL: $imageUrl")
                        Result.failure()
                    }
                } else {
                    Log.e(TAG, "Could not extract image URL from HTML page.")
                    Result.failure()
                }
            } catch (e: Exception) {
                Log.e(TAG, "An error occurred in doWork", e)
                e.printStackTrace()
                Result.failure()
            }
        }
    }

    private fun getImageUrlFromHtml(htmlUrl: String): String? {
        try {
            val doc: Document = Jsoup.connect(htmlUrl).get()
            val docBody = doc.body()
            return docBody.text()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing HTML or extracting image URL", e)
            e.printStackTrace()
        }
        return null
    }

    private fun downloadImage(urlString: String): Bitmap? {
        var bitmap: Bitmap? = null
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connect()
            val input = connection.inputStream
            bitmap = BitmapFactory.decodeStream(input)
            connection.disconnect()
            Log.d(TAG, "Image decoded.")
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading/decoding image", e)
            e.printStackTrace()
        }
        return bitmap
    }

    private fun setWallpaper(bitmap: Bitmap) {
        val wallpaperManager = WallpaperManager.getInstance(applicationContext)
        Log.d(TAG, "WallpaperManager created")
        try {
            Log.d(TAG, "WallpaperManager setting bitmap")
            wallpaperManager.setBitmap(bitmap)
            Log.d(TAG, "WallpaperManager setBitmap called.")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting wallpaper", e)
            e.printStackTrace()
            // Handle exceptions, e.g., SecurityException
        }
    }
}
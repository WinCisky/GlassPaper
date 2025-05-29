package com.ssimo.glasspaper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.work.*
import java.util.concurrent.TimeUnit
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import android.widget.Toast
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private lateinit var wallpaperPreview: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Make sure you have this layout

        Log.i(TAG, "Activity created.")

        val btnChangeNow: Button = findViewById(R.id.set_wallpaper_button) // Your button's ID

        btnChangeNow.setOnClickListener {
            Log.d(TAG, "Change Now button clicked.")
            triggerWallpaperChangeNow()
        }

        // ... You might have other buttons or UI elements ...

        val basePageUrl = "https://wallpapers.opentrust.it/"
        wallpaperPreview = findViewById(R.id.wallpaper_preview)

        // Launch a coroutine on the IO dispatcher for network operation
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val imageUrl = getImageUrlFromHtml(basePageUrl)
                Log.d(TAG, "$imageUrl IMAGE URL MAIN")

                // Switch back to the Main dispatcher to update the UI with Glide
                withContext(Dispatchers.Main) {
                    Glide.with(this@MainActivity)
                        .load(imageUrl)
                        .placeholder(R.drawable.image)
                        .error(R.drawable.image)
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(wallpaperPreview)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching or loading image: ${e.message}")
                e.printStackTrace()
                // Optionally show an error message to the user
                withContext(Dispatchers.Main) {
                    // Update UI to show error, e.g., show a default image or a toast
                    Glide.with(this@MainActivity)
                        .load(R.drawable.image) // Load a default error image
                        .into(wallpaperPreview)
                }
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

    /**
     * Enqueues a One-Time Work Request to run the WallpaperWorker immediately.
     */
    private fun triggerWallpaperChangeNow() {
        Log.i(TAG, "Attempting to enqueue a one-time wallpaper change.")

        // Get the WorkManager instance
        val workManager = WorkManager.getInstance(this)

        // Define constraints (optional but recommended, especially for network)
        // These should ideally match or be less strict than your periodic request.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) // Essential for download
            .build()

        // Create a One-Time Work Request
        val oneTimeWallpaperRequest = OneTimeWorkRequestBuilder<WallpaperWorker>()
            .setConstraints(constraints)
            // You can add a tag to observe or cancel it later if needed
            .addTag("OneTimeWallpaperChange")
            .build()

        // Enqueue the request
        workManager.enqueue(oneTimeWallpaperRequest)

        Log.i(TAG, "One-time wallpaper change work request enqueued.")

        // Provide feedback to the user
        Toast.makeText(this, "Wallpaper change requested. It will update soon!", Toast.LENGTH_SHORT).show()
    }

    private fun scheduleWallpaperWorker() {
        // Define constraints (optional, but recommended)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) // Only run when connected
            .build()

        // Create a periodic work request
        val wallpaperWorkRequest =
            PeriodicWorkRequestBuilder<WallpaperWorker>(1, TimeUnit.MINUTES) // Repeat every 1 hour
                .setConstraints(constraints)
                .build()

        // Enqueue the work request
        // Use enqueueUniquePeriodicWork to avoid scheduling multiple instances
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "WallpaperWorker",
            ExistingPeriodicWorkPolicy.KEEP, // Keep existing work if it exists
            wallpaperWorkRequest
        )
    }


}
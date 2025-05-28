package com.ssimo.glasspaper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.work.*
import java.util.concurrent.TimeUnit
import android.util.Log
import android.widget.Button
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import android.widget.Toast

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"

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
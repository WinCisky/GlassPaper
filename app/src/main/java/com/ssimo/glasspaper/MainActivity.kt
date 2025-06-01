package com.ssimo.glasspaper

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Observer
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.ssimo.glasspaper.databinding.ActivityMainBinding
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private lateinit var wallpaperPreview: ImageView
    private lateinit var binding: ActivityMainBinding
    // Define the interval for easier access
    private val workIntervalMillis = TimeUnit.HOURS.toMillis(2) // 2 hours
    private val workManager by lazy { WorkManager.getInstance(applicationContext) }

    // Use SharedPreferences to store the active state
    private val sharedPreferences by lazy {
        getSharedPreferences("wallpaper_prefs", MODE_PRIVATE)
    }
    private var isWorkerActive: Boolean
        get() = sharedPreferences.getBoolean("is_worker_active", false)
        set(value) = sharedPreferences.edit().putBoolean("is_worker_active", value).apply()



    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupInitialUIState()
        observeWorkerStatus()

        binding.setWallpaperButton.setOnClickListener {
            Log.d(TAG, "Change Now button clicked.")
            triggerWallpaperChangeNow()
        }

        binding.toggleActiveButton.setOnClickListener {
            Log.d(TAG, "Toggle button clicked.")
            if (isWorkerActive) {
                deactivateWorker()
            } else {
                activateWorker()
            }
        }

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

    private fun setupInitialUIState() {
        updateUI(isWorkerActive)
        if (isWorkerActive) {
            // If the worker was active, we need to re-observe its status
            // to get the correct next run time.
            observeWorkerStatus()
        }
    }

    private fun activateWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWorkRequest =
            PeriodicWorkRequestBuilder<WallpaperWorker>(2, TimeUnit.HOURS) // Or use workIntervalMillis property
                .setConstraints(constraints)
                // Optionally set initial delay if you want the first run to be after 1 hour
                // .setInitialDelay(workIntervalMillis, TimeUnit.MILLISECONDS)
                .build()

        workManager.enqueueUniquePeriodicWork(
            WallpaperWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE, // UPDATE is often better than KEEP for periodic work
            // if you want changes to constraints/interval to apply
            periodicWorkRequest
        )
        isWorkerActive = true
        // Store the approximate time of activation to estimate the first run
        // This is for the very first run if no 'last_successful_run_time' exists yet.
        sharedPreferences.edit { putLong("activation_time", System.currentTimeMillis()) }
        updateUI(true)
        observeWorkerStatus() // Re-observe to immediately try and get updated time
    }

    private fun deactivateWorker() {
        workManager.cancelUniqueWork(WallpaperWorker.WORK_NAME)
        isWorkerActive = false
        // Optionally clear the last run time or activation time if needed
        // sharedPreferences.edit().remove("last_successful_run_time").remove("activation_time").apply()
        updateUI(false)
    }

    private fun observeWorkerStatus() {
        workManager.getWorkInfosForUniqueWorkLiveData(WallpaperWorker.WORK_NAME)
            .observe(this, Observer { workInfos ->
                val workInfo = workInfos?.firstOrNull()
                if (workInfo != null && isWorkerActive) {
                    // Always try to calculate and display the next change time when active
                    binding.timeCardContainer.visibility = View.VISIBLE
                    val nextRunTimeMillis = calculateNextRunTime()

                    if (nextRunTimeMillis != null) {
                        val currentTimeMillis = System.currentTimeMillis()
                        val diffMillis = nextRunTimeMillis - currentTimeMillis

                        if (diffMillis > 0) {
                            val hours = TimeUnit.MILLISECONDS.toHours(diffMillis)
                            val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMillis) % 60
                            binding.nextChange.text = "Next change: ${hours}h ${minutes}m"
                        } else {
                            // If diffMillis is negative, it means the scheduled time is past,
                            // and WorkManager should be running it or scheduling the next one soon.
                            binding.nextChange.text = "Next change: Processing..."
                        }
                    } else {
                        // This might happen if it's the very first activation and worker hasn't run yet
                        binding.nextChange.text = "Next change: Scheduling..."
                    }

                    // You can still use workInfo.state for other UI logic if needed,
                    // but the timer is now based on your stored value.
                    when (workInfo.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                            // Worker is active
                            Log.d(TAG, "Worker state: ${workInfo.state}")
                        }
                        WorkInfo.State.CANCELLED, WorkInfo.State.FAILED -> {
                            Log.d(TAG, "Worker state: ${workInfo.state}. Considered inactive for periodic.")
                            // If it failed or was cancelled, and we think it should be active,
                            // might need to re-evaluate. But `isWorkerActive` should be the source of truth.
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            Log.d(TAG, "Worker state: SUCCEEDED. Will be rescheduled by WorkManager.")
                            // After success, it will be automatically rescheduled for its next periodic run.
                            // The calculateNextRunTime() will pick up the new last_successful_run_time.
                            // You might want to explicitly call observeWorkerStatus or updateUI if needed,
                            // but the LiveData should trigger a recalculation.
                        }
                    }
                } else if (!isWorkerActive) {
                    binding.timeCardContainer.visibility = View.INVISIBLE
                }
            })
    }

    /**
     * Calculates the next expected run time based on stored last successful run
     * or the activation time for the very first run.
     */
    private fun calculateNextRunTime(): Long? {
        val lastSuccessfulRunTime = sharedPreferences.getLong("last_successful_run_time", 0L)

        if (lastSuccessfulRunTime > 0) {
            // If we have a last successful run, the next run is intervalMillis after that
            return lastSuccessfulRunTime + workIntervalMillis
        } else {
            // If the worker has never run successfully (e.g., first activation)
            // estimate based on activation time.
            val activationTime = sharedPreferences.getLong("activation_time", 0L)
            if (activationTime > 0) {
                // The first run will be approximately workIntervalMillis after activation,
                // or sooner if there's no initial delay and WorkManager runs it quickly.
                // For a PeriodicWorkRequest without an initial delay, the first run can happen
                // fairly soon after enqueueing, then the period applies.
                // If you set an initial delay on the PeriodicWorkRequest equal to the period,
                // then (activationTime + workIntervalMillis) is a good estimate for the first run.
                // Without initial delay, the *first* period starts after the *first execution*.
                // For simplicity, let's assume if it hasn't run, the next is roughly one interval from now
                // or from activation. This part can be tricky to get perfect for the *very first* run.

                // A common strategy for periodic work:
                // The first execution happens, then `last_successful_run_time` is set.
                // Subsequent calculations are accurate.
                // Before the first execution, it's "Scheduling..." or an estimate.
                val workInfo = workManager.getWorkInfosForUniqueWork(WallpaperWorker.WORK_NAME).get()?.firstOrNull()
                if (workInfo?.state == WorkInfo.State.ENQUEUED || workInfo?.state == WorkInfo.State.RUNNING) {
                    // If it's already enqueued or running but hasn't succeeded yet,
                    // the next *scheduled* one is effectively one interval from "now" in a loose sense,
                    // or more accurately, one interval after this current/pending run completes.
                    // The reflection method was trying to get this, but it's unreliable.
                    // A safe bet is to show "Processing" or "Scheduling" until the first success.
                    // Or, estimate one full interval from activation if you didn't set an initial delay.
                    return activationTime + workIntervalMillis // This is an estimate for the *second* run if first is immediate
                    // or the first run if initialDelay was set.
                }
            }
        }
        return null // Cannot determine yet
    }

    private fun updateUI(isActive: Boolean) {
        if (isActive) {
            binding.statusBadge.text = "Active"
            binding.activeCardContainer.setCardBackgroundColor(ContextCompat.getColor(this, R.color.green_500))
            binding.statusBadge.setTextColor(ContextCompat.getColor(this, R.color.green_50))
            binding.toggleActiveButton.text = getString(R.string.deactivate)
            binding.timeCardContainer.visibility = View.VISIBLE // Ensure it's visible to show updated time
            // Trigger a recalculation and update of the displayed time
            val nextRunTimeMillis = calculateNextRunTime()
            if (nextRunTimeMillis != null) {
                val currentTimeMillis = System.currentTimeMillis()
                val diffMillis = nextRunTimeMillis - currentTimeMillis
                if (diffMillis > 0) {
                    val hours = TimeUnit.MILLISECONDS.toHours(diffMillis)
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMillis) % 60
                    binding.nextChange.text = "Next change: ${hours}h ${minutes}m"
                } else {
                    binding.nextChange.text = "Next change: Processing..."
                }
            } else {
                binding.nextChange.text = "Next change: Scheduling..."
            }
        } else {
            binding.statusBadge.text = "Inactive"
            binding.activeCardContainer.setCardBackgroundColor(ContextCompat.getColor(this, R.color.red_500))
            binding.statusBadge.setTextColor(ContextCompat.getColor(this, R.color.red_50))
            binding.toggleActiveButton.text = getString(R.string.activate)
            binding.timeCardContainer.visibility = View.INVISIBLE
        }
    }


}
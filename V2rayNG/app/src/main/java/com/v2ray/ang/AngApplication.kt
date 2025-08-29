package com.v2ray.ang

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import androidx.multidex.MultiDexApplication
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tencent.mmkv.MMKV
import com.v2ray.ang.worker.ProxyProbeWorker
import java.util.concurrent.TimeUnit
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.MessageUtil

class AngApplication : MultiDexApplication() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    private val workManagerConfiguration: Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        .build()

    private val networkChangeHandler = Handler(Looper.getMainLooper())
    private val networkChangeRunnable = Runnable {
        MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_RESTART, "")
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            networkChangeHandler.removeCallbacks(networkChangeRunnable)
            networkChangeHandler.postDelayed(networkChangeRunnable, 1000) // Debounce for 1 second
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            networkChangeHandler.removeCallbacks(networkChangeRunnable)
            networkChangeHandler.postDelayed(networkChangeRunnable, 1000) // Debounce for 1 second
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            networkChangeHandler.removeCallbacks(networkChangeRunnable)
            networkChangeHandler.postDelayed(networkChangeRunnable, 1000) // Debounce for 1 second
        }
    }

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        MMKV.initialize(this)

        SettingsManager.setNightMode()
        // Initialize WorkManager with the custom configuration
        WorkManager.initialize(this, workManagerConfiguration)

        SettingsManager.initRoutingRulesets(this)

        // Register network callback
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // Schedule the periodic proxy probing worker
        val repeatingRequest = PeriodicWorkRequestBuilder<ProxyProbeWorker>(
            repeatInterval = 15L, // Repeat every 15 minutes
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
            flexTimeInterval = 5L, // Flexible to run anytime in the last 5 minutes of the interval
            flexTimeIntervalUnit = TimeUnit.MINUTES
        ).addTag(AppConfig.WORK_MANAGER_AUTO_PROXY_TAG)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            AppConfig.WORK_MANAGER_AUTO_PROXY_TAG,
            ExistingPeriodicWorkPolicy.UPDATE, // Update existing work if it exists
            repeatingRequest
        )

        es.dmoral.toasty.Toasty.Config.getInstance()
            .setGravity(android.view.Gravity.BOTTOM, 0, 200)
            .apply()
    }

    override fun onTerminate() {
        super.onTerminate()
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}

package com.v2ray.ang.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.AutoSelectorManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.V2RayServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NetworkChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != ConnectivityManager.CONNECTIVITY_ACTION) {
            return
        }

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

        val isConnected = networkCapabilities != null &&
                (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                 networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                 networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))

        if (isConnected) {
            Log.i(TAG, "Network connected or changed. Triggering auto-selection.")
            CoroutineScope(Dispatchers.IO).launch {
                val allServerGuids = MmkvManager.decodeServerList()
                val nonAutoSelectorGuids = allServerGuids.filter {
                    MmkvManager.decodeServerConfig(it)?.remarks != MmkvManager.AUTO_SELECTOR_REMARKS
                }
                val bestProxyGuid = AutoSelectorManager.autoSelectBestProxy(context, nonAutoSelectorGuids)

                if (bestProxyGuid != null) {
                    val currentSelectedGuid = MmkvManager.getSelectServer()
                    if (currentSelectedGuid != bestProxyGuid) {
                        Log.i(TAG, "Network change: New best proxy found: ${MmkvManager.decodeServerConfig(bestProxyGuid)?.remarks}. Switching...")
                        V2RayServiceManager.switchProxy(context, bestProxyGuid)
                    } else {
                        Log.i(TAG, "Network change: Best proxy is still the current one: ${MmkvManager.decodeServerConfig(bestProxyGuid)?.remarks}.")
                    }
                } else {
                    Log.w(TAG, "Network change: No suitable proxy found after re-evaluation.")
                }
            }
        } else {
            Log.i(TAG, "Network disconnected.")
            // Optionally, you could stop the VPN service here if desired, or just let it try to reconnect.
        }
    }

    companion object {
        private const val TAG = "NetworkChangeReceiver"
    }
}
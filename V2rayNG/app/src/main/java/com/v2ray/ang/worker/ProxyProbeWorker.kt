package com.v2ray.ang.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.v2ray.ang.handler.AutoSelectorManager
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProxyProbeWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting background proxy probing...")
        try {
            val allServerGuids = MmkvManager.decodeServerList()
            val nonAutoSelectorGuids = allServerGuids.filter {
                MmkvManager.decodeServerConfig(it)?.remarks != MmkvManager.AUTO_SELECTOR_REMARKS
            }

            if (nonAutoSelectorGuids.isEmpty()) {
                Log.d(TAG, "No non-Auto Selector proxies to probe.")
                return@withContext Result.success()
            }

            val bestProxyGuid = AutoSelectorManager.autoSelectBestProxy(applicationContext, nonAutoSelectorGuids)

            if (bestProxyGuid != null) {
                Log.i(TAG, "Background probing completed. Best proxy found: ${MmkvManager.decodeServerConfig(bestProxyGuid)?.remarks}")
                // If the best proxy is different from the currently selected one, trigger a switch
                // This will be handled by the seamless switching mechanism later.
                // For now, we just ensure the best proxy is updated in MMKV.
                if (MmkvManager.getSelectServer() != bestProxyGuid) {
                    MmkvManager.setSelectServer(bestProxyGuid)
                    Log.i(TAG, "Updated selected server to the new best proxy: ${MmkvManager.decodeServerConfig(bestProxyGuid)?.remarks}")
                }
            } else {
                Log.w(TAG, "Background probing completed. No suitable proxy found.")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during background proxy probing: ${e.message}", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "ProxyProbeWorker"
    }
}
package com.jhomlala.better_player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.jhomlala.better_player.DataSourceUtils.isHTTP
import com.jhomlala.better_player.DataSourceUtils.getUserAgent
import com.jhomlala.better_player.DataSourceUtils.getDataSourceFactory
import androidx.work.WorkerParameters
import com.google.android.exoplayer2.upstream.cache.CacheWriter
import androidx.work.Worker
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.HttpDataSource.HttpDataSourceException
import java.lang.Exception
import java.util.*

/**
 * Cache worker which download part of video and save in cache for future usage. The cache job
 * will be executed in work manager.
 */
class CacheWorker(
    private val context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    private var cacheWriter: CacheWriter? = null
    private var lastCacheReportIndex = 0
    override fun doWork(): Result {
        try {
            val data = inputData
            val url = data.getString(AppBetterPlayerPlugin.URL_PARAMETER)
            val cacheKey = data.getString(AppBetterPlayerPlugin.CACHE_KEY_PARAMETER)
            val preCacheSize = data.getLong(AppBetterPlayerPlugin.PRE_CACHE_SIZE_PARAMETER, 0)
            val maxCacheSize = data.getLong(AppBetterPlayerPlugin.MAX_CACHE_SIZE_PARAMETER, 0)
            val maxCacheFileSize = data.getLong(AppBetterPlayerPlugin.MAX_CACHE_FILE_SIZE_PARAMETER, 0)
            val headers: MutableMap<String, String> = HashMap()
            for (key in data.keyValueMap.keys) {
                if (key.contains(AppBetterPlayerPlugin.HEADER_PARAMETER)) {
                    val keySplit =
                        key.split(AppBetterPlayerPlugin.HEADER_PARAMETER.toRegex()).toTypedArray()[0]
                    headers[keySplit] = Objects.requireNonNull(data.keyValueMap[key]) as String
                }
            }
            val uri = Uri.parse(url)
            if (isHTTP(uri)) {
                val userAgent = getUserAgent(headers)
                val dataSourceFactory = getDataSourceFactory(userAgent, headers)
                var dataSpec = DataSpec(uri, 0, preCacheSize)
                if (cacheKey != null && cacheKey.isNotEmpty()) {
                    dataSpec = dataSpec.buildUpon().setKey(cacheKey).build()
                }
                val cacheDataSourceFactory = CacheDataSourceFactory(
                    context,
                    maxCacheSize,
                    maxCacheFileSize,
                    dataSourceFactory
                )
                cacheWriter = CacheWriter(
                    cacheDataSourceFactory.createDataSource(),
                    dataSpec,
                    null
                ) { _: Long, bytesCached: Long, _: Long ->
                    val completedData = (bytesCached * 100f / preCacheSize).toDouble()
                    if (completedData >= lastCacheReportIndex * 10) {
                        lastCacheReportIndex += 1
                        Log.d(
                            TAG,
                            "Completed pre cache of " + url + ": " + completedData.toInt() + "%"
                        )
                    }
                }
                cacheWriter?.cache()
            } else {
                Log.e(TAG, "Preloading only possible for remote data sources")
                return Result.failure()
            }
        } catch (exception: Exception) {
            Log.e(TAG, exception.toString())
            return if (exception is HttpDataSourceException) {
                Result.success()
            } else {
                Result.failure()
            }
        }
        return Result.success()
    }

    override fun onStopped() {
        try {
            cacheWriter?.cancel()
            super.onStopped()
        } catch (exception: Exception) {
            Log.e(TAG, exception.toString())
        }
    }

    companion object {
        private const val TAG = "CacheWorker"
    }
}
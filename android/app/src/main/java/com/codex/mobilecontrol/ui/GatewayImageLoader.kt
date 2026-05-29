package com.codex.mobilecontrol.ui

import android.content.Context
import coil.ImageLoader
import com.codex.mobilecontrol.GatewayPreferences
import okhttp3.OkHttpClient

object GatewayImageLoader {
    @Volatile
    private var imageLoader: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return imageLoader ?: synchronized(this) {
            imageLoader ?: build(context.applicationContext).also { imageLoader = it }
        }
    }

    private fun build(context: Context): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val token = GatewayPreferences.loadConfig(context)?.token
                val authorization = ThreadMessageRenderSupport.authorizationHeader(
                    imageUrl = request.url.toString(),
                    token = token
                )
                val nextRequest = if (authorization == null) {
                    request
                } else {
                    request.newBuilder()
                        .header("Authorization", authorization)
                        .build()
                }
                chain.proceed(nextRequest)
            }
            .build()

        return ImageLoader.Builder(context)
            .okHttpClient(okHttpClient)
            .crossfade(true)
            .build()
    }
}

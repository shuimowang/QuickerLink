package app.quickerlink

import android.app.Application
import android.content.Context
import app.quickerlink.connection.QuickerIconPolicy
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.IOException
import okhttp3.OkHttpClient

class QuickerLinkApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: Context): ImageLoader {
        val restrictedClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (QuickerIconPolicy.normalizeUrl(url) != url) {
                    throw IOException("Blocked untrusted action icon URL")
                }
                chain.proceed(chain.request())
            }
            .build()

        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { restrictedClient }))
            }
            .build()
    }
}

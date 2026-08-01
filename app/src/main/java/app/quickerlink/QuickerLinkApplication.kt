package app.quickerlink

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.quickerlink.connection.QuickerConnectionRuntime
import app.quickerlink.connection.QuickerIconPolicy
import app.quickerlink.notification.ReceiptCuePlayer
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.IOException
import okhttp3.OkHttpClient

class QuickerLinkApplication : Application(), SingletonImageLoader.Factory, DefaultLifecycleObserver {
    val connectionRuntime: QuickerConnectionRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        QuickerConnectionRuntime(this)
    }

    override fun onCreate() {
        super<Application>.onCreate()
        ReceiptCuePlayer.prepare(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        connectionRuntime.setAppInForeground(true)
    }

    override fun onStop(owner: LifecycleOwner) {
        connectionRuntime.setAppInForeground(false)
        if (!connectionRuntime.shouldRetainConnection()) connectionRuntime.manager.disconnect()
    }

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

package com.xjb.medicalquestionai.data.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Observes the default network without changing Wi-Fi, mobile data or process routing. */
internal class UpdateNetworkMonitor(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    fun isOnline(): Boolean = validated(manager.getNetworkCapabilities(manager.activeNetwork))

    val connectivity = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(validated(capabilities))
            }
            override fun onLost(network: Network) { trySend(false) }
        }
        trySend(isOnline())
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun validated(capabilities: NetworkCapabilities?): Boolean =
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

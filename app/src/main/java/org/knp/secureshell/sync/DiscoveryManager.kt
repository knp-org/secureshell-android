package org.knp.secureshell.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/**
 * Browses for `_secureshellsync._tcp` services on the local network.
 */
class DiscoveryManager(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_secureshellsync._tcp."

    interface DiscoveryListener {
        fun onServiceFound(info: NsdServiceInfo)
        fun onServiceLost(info: NsdServiceInfo)
    }

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun startDiscovery(listener: DiscoveryListener) {
        stopDiscovery()
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("Discovery", "Service discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d("Discovery", "Service found: ${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
                val st = serviceInfo.serviceType.lowercase()
                if (!st.contains("secureshellsync")) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    nsdManager.registerServiceInfoCallback(
                        serviceInfo,
                        { it.run() },
                        object : NsdManager.ServiceInfoCallback {
                            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                                Log.e("Discovery", "Register callback failed: $errorCode")
                            }
                            override fun onServiceUpdated(info: NsdServiceInfo) {
                                val addr = info.hostAddresses.firstOrNull()
                                Log.d("Discovery", "Resolved: $addr:${info.port}")
                                listener.onServiceFound(info)
                                nsdManager.unregisterServiceInfoCallback(this)
                            }
                            override fun onServiceLost() {}
                            override fun onServiceInfoCallbackUnregistered() {}
                        },
                    )
                } else {
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e("Discovery", "Resolve failed: $errorCode")
                        }
                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            Log.d("Discovery", "Resolved: ${resolvedInfo.host}:${resolvedInfo.port}")
                            listener.onServiceFound(resolvedInfo)
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d("Discovery", "Service lost: ${serviceInfo.serviceName}")
                listener.onServiceLost(serviceInfo)
            }

            override fun onDiscoveryStopped(regType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
        }

        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (_: Exception) {}
            discoveryListener = null
        }
    }
}

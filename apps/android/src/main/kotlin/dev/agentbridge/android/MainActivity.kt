package dev.agentbridge.android

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.agentbridge.client.RelayDiscovery
import dev.agentbridge.ui.BridgeApp
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val http = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000 }
    }
    private lateinit var remoteBridge: AndroidRemoteBridge
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val development = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val automaticUrl = if (development) intent.getStringExtra(EXTRA_RELAY_URL) else null
        val automaticToken = if (development) intent.getStringExtra(EXTRA_TOKEN) else null
        val automaticKey = if (development) intent.getStringExtra(EXTRA_KEY) else null
        val automaticHost = if (development) intent.getStringExtra(EXTRA_HOST) else null
        intent.removeExtra(EXTRA_TOKEN)
        intent.removeExtra(EXTRA_KEY)
        remoteBridge = AndroidRemoteBridge.active ?: AndroidRemoteBridge(applicationContext)
        val discovery = RelayDiscovery(http, allowLoopbackHttp = development)
        setContent {
            val bridgeState by remoteBridge.state.collectAsState()
            LaunchedEffect(bridgeState.connected) {
                if (
                    bridgeState.connected &&
                    Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            BridgeApp(
                discovery,
                development = development,
                remoteBridge = remoteBridge,
                defaultLanguage = Locale.getDefault().language,
            )
        }
        if (automaticUrl != null && automaticToken != null && automaticKey != null) {
            remoteBridge.connect(automaticUrl, automaticToken, automaticKey, hostId = automaticHost)
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        http.close()
        if (!remoteBridge.state.value.connected) remoteBridge.close()
    }

    private companion object {
        const val EXTRA_RELAY_URL = "agent_bridge_url"
        const val EXTRA_TOKEN = "agent_bridge_token"
        const val EXTRA_KEY = "agent_bridge_key"
        const val EXTRA_HOST = "agent_bridge_host"
    }
}

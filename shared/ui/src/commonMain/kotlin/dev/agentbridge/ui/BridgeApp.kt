package dev.agentbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentbridge.client.RelayDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val BridgeDarkColors = darkColorScheme(
    primary = Color(0xFF79E0CF),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0A5047),
    onPrimaryContainer = Color(0xFF9CF4E3),
    secondary = Color(0xFFB8C8FF),
    secondaryContainer = Color(0xFF314574),
    tertiary = Color(0xFFD2B8FF),
    background = Color(0xFF0B1118),
    surface = Color(0xFF111A23),
    surfaceVariant = Color(0xFF1C2835),
    surfaceContainer = Color(0xFF14202B),
    surfaceContainerHigh = Color(0xFF1B2935),
    outline = Color(0xFF3A4B59),
    outlineVariant = Color(0xFF263744),
    error = Color(0xFFFFB4AB),
)

private val BridgeLightColors = lightColorScheme(
    primary = Color(0xFF006B5D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF4E3),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF435D92),
    secondaryContainer = Color(0xFFD9E2FF),
    tertiary = Color(0xFF70558F),
    background = Color(0xFFF5F8FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE6EEF3),
    surfaceContainer = Color(0xFFEDF3F7),
    surfaceContainerHigh = Color(0xFFE3ECF2),
    outline = Color(0xFF71808A),
    outlineVariant = Color(0xFFC6D3DA),
    error = Color(0xFFBA1A1A),
)

private val BaseTypography = Typography()
private val BridgeTypography = Typography(
    headlineLarge = BaseTypography.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val BridgeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

data class LocalHarnessView(
    val id: String,
    val name: String,
    val version: String? = null,
    val transport: String? = null,
    val available: Boolean,
    val detail: String? = null,
)

@Composable
private fun AgentAvailabilityCard(
    harness: LocalHarnessView,
    text: (String, String) -> String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (harness.available) MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(harness.name, style = MaterialTheme.typography.titleMedium)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (harness.available) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        if (harness.available) text("ГОТОВ", "READY") else text("НЕ НАЙДЕН", "MISSING"),
                        Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        color = if (harness.available) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                if (harness.available) listOfNotNull(harness.version, harness.transport).joinToString(" · ")
                else harness.detail ?: text("CLI не найден", "CLI not found"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BridgeHeaderContent(
    english: Boolean,
    darkTheme: Boolean,
    connected: Boolean,
    subtitle: String,
    onLanguage: (Boolean) -> Unit,
    onToggleTheme: () -> Unit,
) {
    @Composable
    fun Brand() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("AB", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Column {
                Text("Agent Bridge", style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    @Composable
    fun Controls(compact: Boolean = false) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (compact) {
                TextButton(onClick = { onLanguage(!english) }) { Text(if (english) "EN" else "RU") }
            } else {
                FilterChip(selected = !english, onClick = { onLanguage(false) }, label = { Text("RU") })
                FilterChip(selected = english, onClick = { onLanguage(true) }, label = { Text("EN") })
            }
            TextButton(onClick = onToggleTheme) {
                Text(if (darkTheme) "☀" else "☾", fontSize = 20.sp)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        if (maxWidth < 520.dp) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Brand()
                Controls(compact = true)
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Brand()
                Controls()
            }
        }
    }
}

@Composable
fun BridgeApp(
    discovery: RelayDiscovery,
    development: Boolean = false,
    localHarnesses: List<LocalHarnessView> = emptyList(),
    scanningHarnesses: Boolean = false,
    remoteBridge: RemoteBridgeActions? = null,
    defaultWorkingDirectory: String = "",
    chooseLocalDirectory: (() -> String?)? = null,
    connectUsbAndroid: (() -> String)? = null,
    autoConnect: Boolean = false,
    defaultRelayUrl: String? = null,
    defaultLanguage: String = "ru",
) {
    var english by rememberSaveable { mutableStateOf(defaultLanguage.lowercase().startsWith("en")) }
    val systemDarkTheme = isSystemInDarkTheme()
    var darkTheme by rememberSaveable { mutableStateOf(systemDarkTheme) }
    fun t(ru: String, en: String) = if (english) en else ru
    var url by rememberSaveable {
        mutableStateOf(defaultRelayUrl ?: if (development) "http://127.0.0.1:8787" else "")
    }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val remoteState by remoteBridge?.state?.collectAsState() ?: remember { mutableStateOf(RemoteBridgeState()) }
    var workingDirectory by rememberSaveable { mutableStateOf(defaultWorkingDirectory) }
    var showDirectoryBrowser by remember { mutableStateOf(false) }
    var preferredAgentId by rememberSaveable { mutableStateOf<String?>(null) }
    var newTabName by rememberSaveable { mutableStateOf("") }
    var sessionQuery by rememberSaveable { mutableStateOf("") }
    var sessionFilter by rememberSaveable { mutableStateOf("all") }
    val promptDrafts = remember { mutableStateMapOf<String, String>() }
    var bridgeToken by remember { mutableStateOf("") }
    var bridgeKey by remember { mutableStateOf("") }
    var workspaceId by rememberSaveable { mutableStateOf("default") }
    var selectedHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var connectionCode by remember { mutableStateOf("") }
    var usbStatus by remember { mutableStateOf<String?>(null) }
    var showExternalSessions by rememberSaveable { mutableStateOf(false) }
    var showManualConnection by rememberSaveable { mutableStateOf(false) }
    var screenSection by rememberSaveable { mutableStateOf("work") }
    var connectionTarget by rememberSaveable {
        mutableStateOf(if (url.startsWith("http://127.0.0.1")) "local" else "vps")
    }
    val attentionSessions = remoteState.sessions.filter {
        it.helpNeeded != null || it.approvals.isNotEmpty() || it.error != null
    }
    val embeddedLocalRouter = remoteBridge != null &&
        !remoteBridge.requiresCredentials &&
        defaultRelayUrl == null &&
        url.startsWith("http://127.0.0.1")
    LaunchedEffect(remoteBridge, autoConnect, url) {
        if (autoConnect && remoteBridge != null && !remoteBridge.requiresCredentials && !remoteState.connected) {
            remoteBridge.connect(url)
        }
    }
    LaunchedEffect(remoteState.directoryCurrent) {
        if (workingDirectory.isBlank()) {
            remoteState.directoryCurrent?.let { workingDirectory = it }
        }
    }
    LaunchedEffect(remoteState.hosts) {
        if (selectedHostId !in remoteState.hosts.map { it.id }) {
            selectedHostId = remoteState.hosts.singleOrNull()?.id
        }
    }
    val colorScheme = if (darkTheme) BridgeDarkColors else BridgeLightColors
    MaterialTheme(colorScheme = colorScheme, typography = BridgeTypography, shapes = BridgeShapes) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (darkTheme) 0.26f else 0.42f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
            ) {
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().widthIn(max = 1080.dp)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    tonalElevation = 2.dp,
                ) {
                    BridgeHeaderContent(
                        english = english,
                        darkTheme = darkTheme,
                        connected = remoteState.connected,
                        subtitle = if (remoteState.connected) t("Система в сети", "System online")
                        else t("Центр управления", "Control center"),
                        onLanguage = { english = it },
                        onToggleTheme = { darkTheme = !darkTheme },
                    )
                }
                if (!remoteState.connected) BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compactHero = maxWidth < 520.dp
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            t("Ваши агенты — в одном месте", "All your agents in one place"),
                            style = if (compactHero) MaterialTheme.typography.headlineSmall
                            else MaterialTheme.typography.headlineLarge,
                        )
                        Text(
                            t(
                                "Запускайте Codex, Claude Code и OpenCode локально или управляйте ими через свой VPS.",
                                "Run Codex, Claude Code and OpenCode locally or control them through your own VPS.",
                            ),
                            style = if (compactHero) MaterialTheme.typography.bodyMedium
                            else MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (remoteState.connected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
                        else MaterialTheme.colorScheme.surface,
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (remoteState.connected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (embeddedLocalRouter && remoteState.connected) t("Один Router работает", "One Router is running")
                            else if (remoteState.reconnecting) t("Восстанавливаем соединение", "Reconnecting")
                            else if (embeddedLocalRouter) t("Запустить на этом компьютере", "Start on this computer")
                            else if (remoteState.connected) t("Мост подключён", "Bridge connected")
                            else t("Где работает Relay?", "Where does Relay run?"),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (remoteState.connected) {
                            Text(
                                if (embeddedLocalRouter) "● ${t("Relay, Host и UI подключены", "Relay, Host and UI connected")}"
                                else "● ${t("Подключено", "Connected")} · $url",
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (embeddedLocalRouter) Text(
                                t(
                                    "Codex, Claude Code и OpenCode используют этот единый Router. Создайте вкладку ниже.",
                                    "Codex, Claude Code and OpenCode use this single Router. Create a tab below.",
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (remoteState.reconnecting) {
                            Text(
                                t(
                                    "Router временно недоступен. Попытка ${remoteState.reconnectAttempt}; приложение подключится само.",
                                    "Router is temporarily unavailable. Attempt ${remoteState.reconnectAttempt}; the app will reconnect automatically.",
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            OutlinedButton(onClick = { remoteBridge?.reconnectNow() }) {
                                Text(t("Повторить сейчас", "Retry now"))
                            }
                        } else if (embeddedLocalRouter) {
                            Text(
                                t(
                                    "Одна кнопка запустит локальный Relay, Host и подключит интерфейс. Адрес, порты и ключи вводить не нужно.",
                                    "One button starts the local Relay and Host and connects the UI. No address, ports or keys are needed.",
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (remoteBridge?.requiresCredentials == true && !remoteState.reconnecting) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = connectionTarget == "local",
                                    onClick = { connectionTarget = "local"; url = "http://127.0.0.1:8787"; status = null },
                                    label = { Text(t("USB / этот компьютер", "USB / this computer")) },
                                )
                                FilterChip(
                                    selected = connectionTarget == "vps",
                                    onClick = {
                                        connectionTarget = "vps"
                                        if (url.startsWith("http://127.0.0.1")) url = ""
                                        status = null
                                    },
                                    label = { Text("VPS") },
                                )
                            }
                            Text(
                                if (connectionTarget == "local") t(
                                    "Для телефона по USB: запустите start-android.cmd — адрес и ключи передадутся автоматически.",
                                    "For a phone over USB: run start-android.cmd; address and keys are filled automatically.",
                                ) else t(
                                    "Укажите HTTPS-адрес вашего Relay. Host-компьютер и телефон используют одинаковые token и key.",
                                    "Enter your Relay HTTPS address. The Host computer and phone use the same token and key.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (remoteBridge != null) {
                            if (remoteBridge.requiresCredentials && !remoteState.connected && !remoteState.reconnecting) {
                                OutlinedTextField(
                                    value = connectionCode,
                                    onValueChange = { connectionCode = it },
                                    label = { Text(t("Код подключения", "Connection code")) },
                                    placeholder = { Text("url|workspace|password|key|host") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedButton(
                                    enabled = connectionCode.count { it == '|' } in setOf(2, 4),
                                    onClick = {
                                        val parts = connectionCode.trim().split('|')
                                        if (parts.size == 3) {
                                            url = parts[0].trim()
                                            bridgeToken = parts[1].trim()
                                            bridgeKey = parts[2].trim()
                                            workspaceId = "default"
                                            selectedHostId = null
                                            connectionTarget = if (url.startsWith("http://127.0.0.1")) "local" else "vps"
                                            status = t("Код применён. Найдите компьютер.", "Code applied. Find a computer.")
                                        } else if (parts.size == 5) {
                                            url = parts[0].trim()
                                            workspaceId = parts[1].trim()
                                            bridgeToken = parts[2].trim()
                                            bridgeKey = parts[3].trim()
                                            selectedHostId = parts[4].trim()
                                            connectionTarget = if (url.startsWith("http://127.0.0.1")) "local" else "vps"
                                            status = t("Код применён. Проверьте список компьютеров.", "Code applied. Check the computer list.")
                                        }
                                        if (parts.size == 3 || parts.size == 5) {
                                            remoteBridge.discoverHosts(url, workspaceId, bridgeToken)
                                        }
                                    },
                                ) { Text(t("Применить код", "Apply code")) }
                                TextButton(onClick = { showManualConnection = !showManualConnection }) {
                                    Text(
                                        if (showManualConnection) t("Скрыть ручную настройку", "Hide manual setup")
                                        else t("Настроить вручную", "Set up manually"),
                                    )
                                }
                                if (showManualConnection) {
                                    OutlinedTextField(
                                        value = url, onValueChange = { url = it; status = null }, singleLine = true,
                                        enabled = !busy, label = { Text("Relay URL") },
                                        placeholder = { Text("https://agents.example.com") }, modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedButton(
                                        enabled = !busy && url.isNotBlank(),
                                        onClick = {
                                            busy = true
                                            status = null
                                            scope.launch {
                                                try {
                                                    val info = discovery.inspect(url)
                                                    status = t(
                                                        "Relay доступен · протокол ${info.protocolVersions.joinToString()} · ${info.stage}.",
                                                        "Relay reachable · protocol ${info.protocolVersions.joinToString()} · ${info.stage}.",
                                                    )
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    status = t("Проверка не пройдена: ", "Check failed: ") +
                                                        (e.message?.take(240) ?: t("ошибка соединения", "connection error"))
                                                } finally { busy = false }
                                            }
                                        },
                                    ) { Text(if (busy) t("Проверяем…", "Checking…") else t("Проверить адрес", "Check address")) }
                                    OutlinedTextField(
                                        value = workspaceId,
                                        onValueChange = { workspaceId = it.take(64); selectedHostId = null },
                                        label = { Text(t("Пространство", "Workspace")) },
                                        placeholder = { Text("alex") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = bridgeToken,
                                        onValueChange = { bridgeToken = it },
                                        label = { Text(t("Пароль Router", "Router password")) },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = bridgeKey,
                                        onValueChange = { bridgeKey = it },
                                        label = { Text(t("Ключ шифрования", "Encryption key")) },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                OutlinedButton(
                                    enabled = !remoteState.busy && url.isNotBlank() && workspaceId.isNotBlank() && bridgeToken.length >= 12,
                                    onClick = {
                                        selectedHostId = null
                                        remoteBridge.discoverHosts(url, workspaceId, bridgeToken)
                                    },
                                ) { Text(t("Показать доступные компьютеры", "Show available computers")) }
                                if (remoteState.hosts.isEmpty() && !remoteState.busy) {
                                    Text(
                                        t("После проверки здесь появятся Host-компьютеры этого пользователя.", "Host computers in this workspace will appear here after the check."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                remoteState.hosts.forEach { host ->
                                    FilterChip(
                                        selected = selectedHostId == host.id,
                                        onClick = { selectedHostId = host.id },
                                        label = { Text("${host.name} · ${host.id}") },
                                    )
                                }
                            }
                            if (!remoteState.connected && !remoteState.reconnecting) {
                                Button(
                                    enabled = !remoteState.busy && url.isNotBlank() &&
                                        (!remoteBridge.requiresCredentials || (bridgeToken.isNotBlank() && bridgeKey.isNotBlank() && selectedHostId != null)),
                                    onClick = { remoteBridge.connect(url, bridgeToken, bridgeKey, workspaceId, selectedHostId) },
                                ) {
                                    Text(
                                        if (remoteState.busy && embeddedLocalRouter) t("Запускаем…", "Starting…")
                                        else if (embeddedLocalRouter) t("Запустить один Router", "Start one Router")
                                        else t("Подключиться", "Connect"),
                                    )
                                }
                            }
                            if (embeddedLocalRouter && remoteState.connected && connectUsbAndroid != null) {
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = {
                                        busy = true
                                        usbStatus = null
                                        scope.launch {
                                            usbStatus = runCatching { connectUsbAndroid() }
                                                .getOrElse { failure ->
                                                    t("Телефон не подключён: ", "Phone connection failed: ") +
                                                        (failure.message ?: t("неизвестная ошибка", "unknown error"))
                                                }
                                            busy = false
                                        }
                                    },
                                ) { Text(t("Подключить телефон по USB", "Connect phone over USB")) }
                            }
                        }
                        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        usbStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        remoteState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (remoteBridge != null && remoteState.connected) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = screenSection == "attention",
                            onClick = { screenSection = "attention" },
                            label = { Text("${t("Внимание", "Attention")} · ${attentionSessions.size}") },
                        )
                        FilterChip(
                            selected = screenSection == "work",
                            onClick = { screenSection = "work" },
                            label = { Text("${t("Работа", "Work")} · ${remoteState.sessions.size}") },
                        )
                        FilterChip(
                            selected = screenSection == "terminal",
                            onClick = { screenSection = "terminal"; remoteBridge.refreshExternalSessions() },
                            label = { Text("${t("Терминал", "Terminal")} · ${remoteState.externalSessions.size}") },
                        )
                        FilterChip(
                            selected = screenSection == "system",
                            onClick = { screenSection = "system" },
                            label = { Text(t("Система", "System")) },
                        )
                    }
                }
                if (remoteBridge != null && remoteState.connected && screenSection == "attention") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(t("Центр внимания", "Attention center"), style = MaterialTheme.typography.titleLarge)
                            Text(
                                t(
                                    "Разрешения, запросы помощи и ошибки из всех вкладок.",
                                    "Approvals, help requests, and errors from every tab.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (attentionSessions.isEmpty()) {
                                Surface(
                                    Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                ) {
                                    Text(
                                        t("✓ Все агенты работают без запросов", "✓ No agents need attention"),
                                        Modifier.padding(16.dp),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                            attentionSessions.forEach { session ->
                                Surface(
                                    Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (session.error != null) MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.surfaceContainer,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(session.title, fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    session.agentId ?: session.requestedAgentId ?: "agent",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            TextButton(onClick = {
                                                remoteBridge.selectSession(session.tabId)
                                                screenSection = "work"
                                            }) { Text(t("Открыть", "Open")) }
                                        }
                                        session.helpNeeded?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                        session.error?.let { Text(it, color = MaterialTheme.colorScheme.onErrorContainer) }
                                        session.approvals.forEach { approval ->
                                            Text(approval.command, style = MaterialTheme.typography.bodyMedium)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(onClick = { remoteBridge.respondApproval(session.tabId, approval.id, true) }) {
                                                    Text(t("Разрешить", "Allow"))
                                                }
                                                OutlinedButton(onClick = { remoteBridge.respondApproval(session.tabId, approval.id, false) }) {
                                                    Text(t("Отклонить", "Deny"))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (remoteBridge != null && remoteState.connected && screenSection == "system") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(t("Текущее подключение", "Current connection"), style = MaterialTheme.typography.titleLarge)
                            Text(
                                "${t("Компьютер", "Computer")}: ${remoteState.selectedHostId ?: "host"}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "${t("Пространство", "Workspace")}: ${remoteState.workspaceId}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(t("Агенты на Host", "Agents on Host"), style = MaterialTheme.typography.titleMedium)
                            if (remoteState.agents.isEmpty()) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            } else {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    remoteState.agents.forEach { agent ->
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = if (agent.available) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        ) {
                                            Text(
                                                "${if (agent.available) "●" else "○"} ${agent.name}",
                                                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                                color = if (agent.available) MaterialTheme.colorScheme.onPrimaryContainer
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if ((scanningHarnesses || localHarnesses.isNotEmpty()) &&
                    (!remoteState.connected || screenSection == "system")
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(t("Доступные агенты", "Available agents"), style = MaterialTheme.typography.titleLarge)
                            Text(
                                t("Host находит совместимые CLI автоматически", "Host discovers compatible CLIs automatically"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                "${localHarnesses.count { it.available }}/${localHarnesses.size}",
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    if (scanningHarnesses) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth < 720.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                localHarnesses.forEach { harness ->
                                    AgentAvailabilityCard(harness, ::t, Modifier.fillMaxWidth())
                                }
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                localHarnesses.forEach { harness ->
                                    AgentAvailabilityCard(harness, ::t, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                if (remoteBridge != null && remoteState.connected && screenSection == "terminal") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(t("Сессии из терминала", "Terminal sessions"), style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        t(
                                            "Смотрите уже запущенные Codex и Claude Code, не останавливая их.",
                                            "Watch running Codex and Claude Code sessions without stopping them.",
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(
                                        remoteState.externalSessions.size.toString(),
                                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { showExternalSessions = !showExternalSessions }) {
                                    Text(if (showExternalSessions) t("Скрыть", "Hide") else t("Найти текущую сессию", "Find current session"))
                                }
                                OutlinedButton(onClick = remoteBridge::refreshExternalSessions) {
                                    Text("↻")
                                }
                            }
                            if (showExternalSessions) {
                                if (remoteState.externalSessions.isEmpty()) {
                                    Text(
                                        t(
                                            "Недавние терминальные сессии не найдены. Запустите Codex или Claude Code на Host и нажмите «Обновить».",
                                            "No recent terminal sessions found. Start Codex or Claude Code on the Host and press Refresh.",
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    remoteState.externalSessions.take(12).forEach { session ->
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = MaterialTheme.shapes.small,
                                            color = MaterialTheme.colorScheme.surfaceContainer,
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(12.dp),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(session.title, fontWeight = FontWeight.SemiBold)
                                                    Text(
                                                        session.workingDirectory.ifBlank { session.id.take(12) },
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    Text(
                                                        if (session.active) t("● обновляется сейчас", "● updating now")
                                                        else t("○ готова к продолжению", "○ ready to resume"),
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = if (session.active) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                OutlinedButton(onClick = { remoteBridge.watchExternalSession(session) }) {
                                                    Text(t("Смотреть", "Watch"))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            remoteState.watchedExternalSession?.let { session ->
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(session.title, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            if (session.active) t("Только просмотр — агент работает в терминале", "View only — agent is working in the terminal")
                                            else t("Текущий шаг завершён", "Current turn has finished"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (session.active) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = remoteBridge::stopWatchingExternalSession) {
                                        Text(t("Закрыть", "Close"))
                                    }
                                }
                                Surface(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)
                                        .verticalScroll(rememberScrollState()),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Text(
                                        remoteState.externalTranscript.ifBlank {
                                            t("Ждём сообщения агента…", "Waiting for agent messages…")
                                        },
                                        Modifier.padding(14.dp),
                                    )
                                }
                                if (!session.active && session.canResume) {
                                    Button(onClick = { remoteBridge.resumeExternalSession(session) }) {
                                        Text(t("Продолжить в Agent Bridge", "Continue in Agent Bridge"))
                                    }
                                    Text(
                                        t(
                                            "После переноса не отправляйте новые сообщения из старого терминала.",
                                            "After transfer, do not send new messages from the old terminal.",
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                if (remoteBridge != null && remoteState.connected && screenSection == "work") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(t("Рабочее пространство", "Workspace"), style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        t("Сессии и сохранённые контексты", "Sessions and saved contexts"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(
                                        "${remoteState.liveSessionCount} / $MAX_LIVE_SESSIONS",
                                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                            if (remoteState.sessions.size >= 4) {
                                OutlinedTextField(
                                    value = sessionQuery,
                                    onValueChange = { sessionQuery = it.take(80) },
                                    label = { Text(t("Поиск вкладок", "Search tabs")) },
                                    placeholder = { Text(t("Название, агент или папка", "Title, agent, or folder")) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (remoteState.sessions.isNotEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    listOf(
                                        "all" to t("Все", "All"),
                                        "live" to t("Запущены", "Running"),
                                        "attention" to t("Нужна помощь", "Needs attention"),
                                        "queued" to t("С очередью", "Queued"),
                                    ).forEach { (id, label) ->
                                        FilterChip(
                                            selected = sessionFilter == id,
                                            onClick = { sessionFilter = id },
                                            label = { Text(label) },
                                        )
                                    }
                                }
                            }
                            val normalizedQuery = sessionQuery.trim().lowercase()
                            val visibleSessions = remoteState.sessions.filter { session ->
                                val matchesQuery = normalizedQuery.isEmpty() || listOf(
                                    session.title,
                                    session.workingDirectory,
                                    session.agentId.orEmpty(),
                                    session.requestedAgentId.orEmpty(),
                                ).any { normalizedQuery in it.lowercase() }
                                val matchesFilter = when (sessionFilter) {
                                    "live" -> session.sessionId != null
                                    "attention" -> session.helpNeeded != null || session.approvals.isNotEmpty() || session.error != null
                                    "queued" -> session.queuedMessages.isNotEmpty()
                                    else -> true
                                }
                                matchesQuery && matchesFilter
                            }.sortedByDescending { it.pinned }
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = remoteState.activeTabId == null,
                                    onClick = { remoteBridge.selectSession(null) },
                                    enabled = remoteState.sessions.size < MAX_SAVED_TABS && remoteState.liveSessionCount < MAX_LIVE_SESSIONS,
                                    label = { Text(t("＋ Новая", "＋ New")) },
                                )
                                visibleSessions.forEach { session ->
                                    val marker = when {
                                        session.helpNeeded != null || session.approvals.isNotEmpty() -> " !"
                                        session.closing -> " ×"
                                        session.busy -> " …"
                                        session.queuedMessages.isNotEmpty() -> " +${session.queuedMessages.size}"
                                        session.sessionId == null -> " ○"
                                        else -> ""
                                    }
                                    FilterChip(
                                        selected = remoteState.activeTabId == session.tabId,
                                        onClick = { remoteBridge.selectSession(session.tabId) },
                                        label = {
                                            Text("${if (session.pinned) "★ " else ""}${session.agentId ?: session.requestedAgentId ?: "agent"}: ${session.title}$marker")
                                        },
                                    )
                                }
                            }
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(t("Разбудить, если нужна помощь", "Wake me when help is needed"), fontWeight = FontWeight.Medium)
                                        Text(
                                            t("Звук и заметное уведомление при запросе разрешения.", "Sound and prominent notification on approval requests."),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = remoteState.wakeAlarmEnabled,
                                        onCheckedChange = remoteBridge::setWakeAlarmEnabled,
                                    )
                                }
                            }
                            remoteState.activeSession?.helpNeeded?.let {
                                Text(
                                    t("Агенту нужна помощь: ", "Agent needs help: ") + it,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            val activeSession = remoteState.activeSession
                            if (activeSession == null) {
                                OutlinedTextField(
                                    value = newTabName,
                                    onValueChange = { newTabName = it.take(80) },
                                    label = { Text(t("Название вкладки", "Tab name")) },
                                    placeholder = { Text(t("Например: Backend", "For example: Backend")) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    val browseAction: () -> Unit = {
                                        if (chooseLocalDirectory != null) {
                                            chooseLocalDirectory()?.let { workingDirectory = it }
                                        } else {
                                            showDirectoryBrowser = true
                                            remoteBridge.browseDirectories(null)
                                        }
                                    }
                                    if (maxWidth < 560.dp) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = workingDirectory,
                                                onValueChange = { workingDirectory = it },
                                                label = { Text(t("Рабочая папка на Host", "Working directory on Host")) },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            OutlinedButton(onClick = browseAction, modifier = Modifier.fillMaxWidth()) {
                                                Text(t("Выбрать папку…", "Browse folder…"))
                                            }
                                        }
                                    } else {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = workingDirectory,
                                                onValueChange = { workingDirectory = it },
                                                label = { Text(t("Рабочая папка на Host", "Working directory on Host")) },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f),
                                            )
                                            OutlinedButton(onClick = browseAction, modifier = Modifier.padding(top = 8.dp)) {
                                                Text(t("Выбрать…", "Browse…"))
                                            }
                                        }
                                    }
                                }
                                if (showDirectoryBrowser && remoteState.directoryCurrent != null) {
                                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(remoteState.directoryCurrent.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(onClick = {
                                                    workingDirectory = remoteState.directoryCurrent.orEmpty()
                                                    showDirectoryBrowser = false
                                                }) { Text(t("Выбрать эту папку", "Choose this folder")) }
                                                remoteState.directoryParent?.let { parent ->
                                                    OutlinedButton(onClick = { remoteBridge.browseDirectories(parent) }) {
                                                        Text(t("На уровень выше", "Up"))
                                                    }
                                                }
                                            }
                                            remoteState.directories.forEach { directory ->
                                                TextButton(
                                                    onClick = { remoteBridge.browseDirectories(directory.path) },
                                                    modifier = Modifier.fillMaxWidth(),
                                                ) { Text("📁 ${directory.name}") }
                                            }
                                        }
                                    }
                                }
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    remoteState.agents.filter { it.available }.forEach { agent ->
                                        FilterChip(
                                            selected = preferredAgentId == agent.id,
                                            onClick = {
                                                preferredAgentId = if (preferredAgentId == agent.id) null else agent.id
                                            },
                                            label = { Text(agent.name) },
                                        )
                                    }
                                }
                                Text(
                                    t(
                                        "Без выбора Host автоматически предпочитает Codex, затем Claude и OpenCode.",
                                        "Without a selection, Host prefers Codex, then Claude and OpenCode.",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(
                                    enabled = workingDirectory.isNotBlank() && !remoteState.busy &&
                                        remoteState.sessions.size < MAX_SAVED_TABS && remoteState.liveSessionCount < MAX_LIVE_SESSIONS,
                                    onClick = {
                                        remoteBridge.startSession(workingDirectory, preferredAgentId, newTabName)
                                        newTabName = ""
                                    },
                                ) { Text(t("Создать вкладку", "Create tab")) }
                                if (remoteState.liveSessionCount >= MAX_LIVE_SESSIONS) {
                                    Text(t("Остановите одного из активных агентов. Сохранённые вкладки не удалятся.", "Stop one live agent. Saved tabs will remain."), color = MaterialTheme.colorScheme.error)
                                }
                            } else {
                                OutlinedTextField(
                                    value = activeSession.title,
                                    onValueChange = { remoteBridge.renameSession(activeSession.tabId, it) },
                                    label = { Text(t("Название вкладки", "Tab name")) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("${activeSession.agentId ?: activeSession.requestedAgentId ?: "agent"} · ${activeSession.workingDirectory}", style = MaterialTheme.typography.bodySmall)
                                if (activeSession.sessionId == null && !activeSession.busy && activeSession.error == null) {
                                    Text(t("Сохранена · нажмите вкладку ещё раз, чтобы продолжить", "Saved · tap the tab again to continue"), style = MaterialTheme.typography.bodySmall)
                                }
                                if (activeSession.resumed && activeSession.sessionId != null) {
                                    Text(t("Предыдущая сессия восстановлена", "Previous session resumed"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text(t("Автомод", "Auto mode"))
                                        Text(
                                            t("Автоматически разрешать структурированные запросы этой вкладки.", "Automatically allow structured approvals for this tab."),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = activeSession.autoMode,
                                        onCheckedChange = { remoteBridge.setSessionAutoMode(activeSession.tabId, it) },
                                    )
                                }
                                activeSession.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                if (activeSession.sessionId == null && activeSession.busy) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text(t("Запускаем агент…", "Starting agent…"), style = MaterialTheme.typography.bodySmall)
                                }
                                if (activeSession.transcript.isNotBlank()) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.medium,
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                    ) {
                                        Text(activeSession.transcript, modifier = Modifier.padding(14.dp).fillMaxWidth())
                                    }
                                }
                                if (activeSession.activity.isNotEmpty()) {
                                    Text(t("Что сейчас происходит", "Current activity"), style = MaterialTheme.typography.titleMedium)
                                    activeSession.activity.takeLast(8).forEach { activity ->
                                        Text(
                                            "${activity.state}: ${activity.detail.orEmpty()}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                activeSession.approvals.forEach { approval ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                                    ) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(approval.command)
                                            if (approval.cwd.isNotBlank()) Text(approval.cwd, style = MaterialTheme.typography.bodySmall)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(onClick = { remoteBridge.respondApproval(activeSession.tabId, approval.id, true) }) {
                                                    Text(t("Разрешить один раз", "Allow once"))
                                                }
                                                OutlinedButton(onClick = { remoteBridge.respondApproval(activeSession.tabId, approval.id, false) }) {
                                                    Text(t("Отклонить", "Deny"))
                                                }
                                            }
                                        }
                                    }
                                }
                                if (activeSession.queuedMessages.isNotEmpty()) {
                                    Text(
                                        "${t("Очередь задач", "Task queue")} · ${activeSession.queuedMessages.size}",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    activeSession.queuedMessages.forEachIndexed { index, queued ->
                                        Surface(
                                            Modifier.fillMaxWidth(),
                                            shape = MaterialTheme.shapes.small,
                                            color = MaterialTheme.colorScheme.surfaceContainer,
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text("${index + 1}.", color = MaterialTheme.colorScheme.primary)
                                                Text(queued, Modifier.weight(1f), maxLines = 2)
                                                TextButton(onClick = { remoteBridge.removeQueuedText(activeSession.tabId, index) }) {
                                                    Text("×")
                                                }
                                            }
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = promptDrafts[activeSession.tabId].orEmpty(),
                                    onValueChange = { promptDrafts[activeSession.tabId] = it },
                                    label = { Text(t("Сообщение", "Message")) },
                                    placeholder = { Text(t("Напишите задачу агенту…", "Write a task for the agent…")) },
                                    minLines = 3,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        enabled = promptDrafts[activeSession.tabId].orEmpty().isNotBlank() && !activeSession.busy && activeSession.sessionId != null,
                                        onClick = {
                                            remoteBridge.sendText(activeSession.tabId, promptDrafts[activeSession.tabId].orEmpty())
                                            promptDrafts.remove(activeSession.tabId)
                                        },
                                    ) { Text(t("Отправить", "Send")) }
                                    OutlinedButton(
                                        enabled = promptDrafts[activeSession.tabId].orEmpty().isNotBlank() && !activeSession.closing,
                                        onClick = {
                                            remoteBridge.queueText(activeSession.tabId, promptDrafts[activeSession.tabId].orEmpty())
                                            promptDrafts.remove(activeSession.tabId)
                                        },
                                    ) { Text(t("В очередь", "Queue")) }
                                }
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(
                                        enabled = activeSession.sessionId != null && !activeSession.closing,
                                        onClick = remoteBridge::interrupt,
                                    ) { Text(t("Остановить", "Stop")) }
                                    TextButton(
                                        enabled = activeSession.sessionId != null && !activeSession.closing,
                                        onClick = { remoteBridge.closeSession(activeSession.tabId) },
                                    ) { Text(if (activeSession.closing) t("Останавливаем…", "Stopping…") else t("Остановить агент", "Stop agent")) }
                                }
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(onClick = { remoteBridge.clearSession(activeSession.tabId) }) {
                                        Text(t("Новый контекст", "New context"))
                                    }
                                    TextButton(onClick = {
                                        remoteBridge.setSessionPinned(activeSession.tabId, !activeSession.pinned)
                                    }) {
                                        Text(if (activeSession.pinned) t("Открепить", "Unpin") else t("Закрепить", "Pin"))
                                    }
                                    TextButton(
                                        enabled = !activeSession.closing,
                                        onClick = { remoteBridge.deleteSession(activeSession.tabId) },
                                    ) { Text(t("Удалить вкладку", "Delete tab")) }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t("Agent Bridge · предварительная версия", "Agent Bridge · preview"), color = MaterialTheme.colorScheme.primary)
                        Text(
                            t(
                                "Зашифрованный мост для локального подключения и вашего VPS.",
                                "Encrypted bridge for local connections and your VPS.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (development) Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text("DEV", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            }
        }
    }
}

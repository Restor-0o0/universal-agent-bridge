# Universal Agent Bridge — Product Requirements & Technical Design

**Статус:** проектное ТЗ v0.2 — универсальный Desktop Host + Relay, без обязательного VPN
**Платформы:** Android, Windows 10/11, Linux, Linux VPS
**Основная идея:** self-hosted remote-control система для локальных CLI AI-агентов. Клиент подключается к собственному Relay пользователя, а не к инфраструктуре разработчика.
**Документ предназначен:** для разработки через Codex и последующего публичного релиза Android-клиента в Google Play.

> **Важно:** это технический проект, а не обещание, что любые сторонние CLI можно перехватить в середине выполнения. Для уже работающих независимых процессов требуется отдельная поддержка и честная индикация уровня управления.

## 1. Продукт

Пользователь работает на компьютере с Codex / Claude Code / Gemini CLI / OpenCode или другим локально установленным агентом. Затем открывает приложение на телефоне, выбирает компьютер и сессию и продолжает общение: отправляет текст и картинки, наблюдает поток ответов и выполнение инструментов, подтверждает разрешения, получает файлы и скриншоты. Вернувшись за компьютер, пользователь продолжает работу с тем же контекстом. Вычисления, файлы проектов и AI CLI остаются на компьютере.

**Принцип размещения:** Android/desktop клиент и Host общаются через собственный VPS/сервер пользователя; разработчик продукта **не предоставляет обязательный облачный relay**, не получает содержимое сессий и не хранит ключи устройств.

**Вне границ:** обход учётных записей/подписок AI-провайдеров, удалённая передача API-ключей на Relay, скрытый удалённый доступ к компьютерам, обход OS-разрешений, собственная модель AI, гарантия совместимости с любым CLI без адаптера.

## 2. Состав поставки (два GUI-приложения + headless-режим того же Relay)

1. **Android Client:** Kotlin Multiplatform/Compose UI, клиент протокола, локальный зашифрованный кеш, вложения, уведомления, управление сессиями/разрешениями.
2. **Desktop Client + Host:** единое устанавливаемое приложение для Windows/Linux; Compose Desktop UI + отдельный управляемый фоновый Host процесс. Закрытие GUI не останавливает Host. При желании Host запускается headless (без GUI).
3. **Встроенный Relay + headless server:** единый `relay-engine` на Ktor/JVM, запускаемый (а) внутри Desktop GUI в режиме Relay, (б) как отдельная фоновая служба Windows/Linux, (в) как headless Linux-сервис на VPS. Docker — *необязательная* упаковка. Relay выполняет аутентификацию, pairing, маршрутизацию E2EE-событий и файлов, краткоживущую доставку; не получает приватные ключи устройств.

**Shared modules:** `protocol`, `crypto`, `domain`, `client-core`, `agent-spi`, `design-system`, `test-fixtures`. Android/Desktop шарят максимум UI и бизнес-логики; Relay повторно использует только релевантные серверные и протокольные модули.

### 2.1 Пример структуры монорепозитория

```text
universal-agent-bridge/
  apps/
    android/                   # Android entry point, FCM-опция, secure storage
    desktop/                   # Compose Desktop Windows/Linux, Host/Relay toggles
    relay/                     # headless Ktor executable / CLI / optional Docker
    host/                      # автономный JVM процесс, OS adapters
  shared/
    protocol/                  # версии событий, serialisation
    domain/                    # сессии, permissions, computers
    crypto/                    # key exchange / e2ee envelopes
    client-core/               # sync, offline queue, reconnect
    agent-spi/                 # interfaces + capability model
    ui/                        # общие Compose views
  adapters/
    codex/
    claude/
    opencode/
    gemini/
    generic-pty/
  deployment/
    packages/deb/              # linux installation without Docker
    packages/rpm/
    windows-installer/         # Desktop & service install
    docker-compose.yml         # OPTIONAL
    caddy/Caddyfile.example
    nginx/nginx.example.conf
    systemd/
  docs/
    architecture.md
    protocol.md
    security.md
    adapters.md
    self-hosting.md
    play-release.md
  integration-tests/
```

**KMP:** Compose Multiplatform применить к Android/Desktop, JVM — к Host/Relay. Не делать обязательной целью компиляцию единого приложения под все платформы: фоновые службы, OS keychain, ConPTY, PTY и Android push реализуются платформенно-специфично.

## 3. Типовое развертывание

```text
Android App --HTTPS/WSS--> [свой домен + TLS / CDN optional]
                                  |
                             Relay (VPS)
                                  |
                         WSS исходящий канал
                                  |
                 Desktop Host (Windows/Linux)
                       |             |
                 Agent Adapter    Screenshots / files
                       |
            Codex / Claude / OpenCode / Gemini
```

**Для сценария через VPS не требуется:** белый IP дома, входящий порт на домашнем ПК, VPN на Android, регистрация на сервере разработчика. **Для Relay на домашнем ПК без VPS** может потребоваться маршрутизация через VPN, внешний адрес/port-forward или отдельный туннель; NAT автоматически не исчезает.

### 3.1 Транспортные режимы

- **A. Standard self-hosted (основной режим):** `https://agents.userdomain.tld` → reverse proxy HTTPS (Caddy/Nginx) → Relay localhost/container. С Android и Host устанавливаются **исходящие WSS-соединения**. Рекомендуемый способ для Google Play.
- **B. Private network (опция):** тот же протокол через локальную сеть, WireGuard, Tailscale или Headscale. Независим от VPN-приложения; приложение подключается к обычному HTTPS/адресу, доступному в данной сети. Требовать TLS в релизе, для локальной разработки разрешить отдельно помеченный dev-режим.
- **C. Existing VLESS/CDN (опция, твой сценарий):** пользователь продолжает использовать текущий VPN/VLESS для другого трафика. Для моста создаёт **отдельный HTTPS virtual host/path** (например `agents.example.com`) через имеющийся CDN и reverse proxy на том же VPS. Сам протокол приложения **не оборачивается вручную в VLESS**. VLESS необязателен, CLI не зависит от Xray.

**Важно:** у разных CDN свои лимиты WebSocket и upload. Настраивать ping/pong, reconnect, resumable uploads, размеры вложений и проверку доступности WSS. Не рассчитывать на вечные WebSocket-подключения.

### 3.2 Self-hosted-first onboarding

- Экран приветствия → «У меня уже есть сервер» / «Как развернуть сервер» / «Локальная сеть».
- Подключение: HTTPS URL, TLS проверка, получить сведения о версии/возможностях Relay (`/.well-known/agent-bridge`), привязать устройство.
- Встроенный пошаговый мастер развёртывания: Linux `.deb`/`.rpm` + systemd (основной путь для VPS), установщик Windows Service (для Windows Relay), DNS A/AAAA, TLS, reverse proxy, firewall, обновления; Docker Compose только как альтернатива. **Не собирать логин/пароль/root SSH ключи VPS в Android-клиенте.**
- Pairing: администратор инициирует привязку на Host/сервере, показывает одноразовый QR с короткоживущим challenge + server identity/fingerprint. Android подтверждает fingerprint, Host подтверждает новое устройство; ключи согласуются напрямую с криптографической привязкой к challenge.
- Поддержать несколько серверов и компьютеров в одном клиенте, переключение без повторной регистрации.
- Потерянный телефон: отзыв ключа устройства на Host/админ-панели Relay, немедленное прекращение доступа, ротация ключей сессий.

## 4. Типы сессий: честная семантика

Статусы должны быть явными в API/UI:

1. **Managed Live Session:** агент запущен через Host / подключённый контролируемый PTY или структурированный server API. Один процесс, согласованный ввод/вывод. Можно безопасно переключать управление ПК ↔ Android.
2. **Resumable Session:** сторонняя сессия обнаружена через официальные файлы состояния/API; можно возобновить контекст в новом процессе после безопасной проверки, но это не тот же PID и не обязательно тот же текущий turn.
3. **Read-only External Session:** можно прочитать историю или метаданные; нельзя подтвердить инструменты/отправить сообщение, пока не выполнен контролируемый handoff.
4. **Unsupported:** агент найден, но управление не поддерживается. Не обещать незаметного перехвата.

**Главное UX-правило:** приложение НЕ должно показывать кнопку «Продолжить в той же живой сессии», если адаптер умеет только возобновление контекста. Для внешней CLI-сессии перед импортом показать предупреждение о дублирующемся процессе/состоянии и недопустимости параллельной записи.

### 4.1 Адаптеры

Контракт `AgentAdapter`: `detect`, `listSessions`, `capabilities`, `startManaged`, `attach`, `resume`, `sendText`, `sendAttachments`, `streamEvents`, `respondApproval`, `interrupt`, `close`, `dispose`. Все способности объявлять в CapabilitySet: `LIVE_ATTACH`, `RESUME`, `APPROVAL`, `IMAGE_INPUT`, `IMAGE_OUTPUT`, `FILE_OUTBOX`, `SCREEN_CAPTURE`, `STREAMING`, `STOP`, `TERMINAL_VIEW`.

- **Codex:** в первую очередь изучить документированный app-server JSON-RPC для управляемых сессий. Обнаружение внешних CLI историй — отдельный адаптер. Учитывать изменения версий.
- **Claude Code:** интегрировать через поддерживаемые официальные CLI/SDK/hooks интерфейсы там, где они предоставляют корректный жизненный цикл. Не парсить prompts разрешений простыми регулярками как единственный способ.
- **OpenCode:** использовать документированные server/API методы при доступности.
- **Gemini CLI:** использовать поддерживаемые headless/SDK/extension возможности; не предполагать единый API на все версии.
- **Generic PTY:** Windows ConPTY; Linux PTY. Поддерживает любой текстовый CLI при запуске через Host, но показывает сырой terminal view, если структурированные события/approvals нельзя достоверно извлечь. Не имитировать подтверждение инструментов через угадывание текста.

### 4.2 Конфликты управления

- У одной managed-сессии один активный input lease; два клиента могут наблюдать, но управляющий назначается явно.
- Передача контроля `desktop → mobile` и `mobile → desktop` с подтверждением/TTL.
- Избежать двойной отправки команд при reconnection: idempotency key, server ack, per-session monotonic sequence, replay событий.
- Не прерывать длительную задачу при потере связи; после reconnect синхронизировать пропущенные события.

## 5. Требования к Android UI

- Авторизация на своём сервере, server switcher, состояние соединения/шифрования.
- Список компьютеров online/offline + платформа + версия Host.
- Список проектов и сессий, фильтры по агенту, статусам, последние события, быстрый поиск.
- Чат: потоковый текст, Markdown/code/diffs, артефакты, последовательность tool calls, мини-предпросмотр изображений, зум, сохранение файла.
- Compose field: текст, картинки из галереи, share intent, документы через system picker, clipboard. Не передавать медиатеку целиком.
- Approvals: явные allow-once / deny, уровни риска, показывать command, cwd, права и идентификатор компьютера; разрешение «всегда» только при явно выраженной политике Host и подтверждении пользователя.
- Отдельные кнопки `Interrupt`, `Resume`, `Send screenshot request` (последняя доступна лишь если на Host разрешены скриншоты).
- Оффлайн-очередь исходящих черновиков: перед отправкой показать, что запрос ожидает соединения; опасные подтверждения **не ставить в оффлайн-очередь**.
- Уведомления о завершении задачи, запросе разрешения и получении файла. Push — **опциональный модуль**; для Google Play без инфраструктуры разработчика предпочтителен self-hosted push gateway или режим проверок при открытом приложении. Не обещать гарантированные фоновые уведомления на Android при закрытом приложении без отдельного канала доставки/FCM-сервера. Не пересылать содержание переписки в FCM payload; только непрозрачные ID.
- Русский и английский языки, тёмная и светлая тема, доступность (TalkBack, шрифт), восстановление после выгрузки приложения.

## 6. Desktop UI + Host Windows/Linux

**UI:** список управляемых сессий, текущий агент/проект, локальная чат-панель, обнаруженные внешние сессии, экран pairing, состояние Relay, логи, настройки screen capture, разрешения, лимиты доступа, обновления; трей (Windows)/status tray при наличии поддержки на Linux.

**Сервис:** выделенный процесс Host. Запускать в **контексте текущего пользователя**, не от SYSTEM/root, чтобы сохранить доступ к его CLI, HOME, рабочим каталогам и графическому сеансу; не предоставлять remote root shell. Windows: user autostart/Task Scheduler, при необходимости user process supervisor. Linux: `systemd --user` для headless; графический захват зависит от Wayland/X11, PipeWire/xdg-desktop-portal и пользовательского разрешения. Отдельная GUI-сессия может потребоваться для скриншотов.

**Безопасность:** рабочие каталоги allowlist; лимиты и запреты на shell commands согласно политике пользователя; без скрытого сбора экрана; выбор окно/экран, явная индикация и OS-consent там, где требуется. Скриншоты только по локально разрешённым правилам и запросам авторизованных сессий.

## 7. Images / files / screenshot flow

- Android → Host: передача изображения или файла (chunked, checksum, size limit); Host сохраняет в выделенную временную папку с безопасным именем, создаёт доступ к вложению согласно контракту адаптера; показывать пользователю целевой путь/размер, удалять по retention policy.
- Host → Android: агент вызывает **локальный инструмент** `publish_artifact(path, displayName, mediaType)`, который проверяет, что путь в разрешённом пространстве (canonical path; без symlink traversal), ограничивает размер, отправляет файл зашифрованными чанками и добавляет событие `artifact.available`. Поддерживать `image/png`, `image/jpeg`, `image/webp` и произвольные файлы.
- Скриншот: `capture_screen(target=selected_screen|window, publish=true)` выполняется на Host в рамках пользовательского разрешения; **не автоматически на любом удалённом требовании без настройки/согласия**.
- На Android: inline thumbnail, lightbox/zoom, cache cleanup, сохранение через system picker. Приватные вложения не помещать в публичные галереи автоматически.
- Payload storage: Relay держит только зашифрованные временные chunks с TTL и ограничением квоты; по возможности streaming без долговременного хранения. Автоматически очищать отменённые/просроченные uploads.

## 8. Relay Server — API и data model

**Технологии:** Ktor + Kotlin/JVM, WebSocket, kotlinx.serialization, PostgreSQL либо SQLite в режиме одного узла для MVP, миграции Flyway. Caddy/NGINX terminates HTTPS; relay также умеет прямой TLS в нестандартных конфигурациях.

Объекты: `User`, `RelayInstance`, `Device`, `Computer`, `Project`, `SessionDescriptor`, `AccessGrant`, `EventEnvelope`, `ArtifactTransfer`, `AuditRecord`, `PairingChallenge`, `KeyEpoch`.

**Минимальный протокол v1:**

```json
{
  "v": 1,
  "eventId": "uuid",
  "kind": "session.event",
  "senderDeviceId": "device-id",
  "recipientDeviceId": "device-id",
  "sessionId": "opaque-id",
  "seq": 42,
  "createdAt": "ISO-8601",
  "ciphertext": "base64url",
  "nonce": "base64url",
  "keyEpoch": 3
}
```

**Примеры семантических событий внутри E2EE:** `computer.online`, `session.discovered`, `session.snapshot`, `turn.started`, `message.delta`, `turn.finished`, `approval.requested`, `approval.responded`, `artifact.available`, `artifact.chunk`, `control.requested`, `control.granted`, `client.ack`, `error`.

- Relay видит минимальный набор маршрутизирующих метаданных, но не расшифровывает содержимое событий, изображений, код, промпты.
- Сервер должен корректно обрабатывать version negotiation, heartbeats, rate limits, max frame size, upload quota, backpressure, reconnect/resume sequence, replay window.
- Управление аккаунтами **локальное для каждого Relay**; никакого центрального глобального аккаунта.
- Admin API ограничить по ролям, сделать локальный bootstrap token/первого администратора и возможность отключить регистрацию.
- Metadata, audit events и диагностические логи тоже чувствительны: редактировать секреты, минимизировать retention, allow opt-out; plaintext не писать в логи.

## 9. Модель безопасности (не откладывать на потом)

**Threat model:** взлом VPS, скомпрометированный Android, подмена pairing-QR, CDN reverse proxy, неавторизованный доступ к чужому Host, повторная отправка approval, path traversal во вложениях, нежелательное выполнение shell-команд, вредоносные плагины/агенты.

1. TLS 1.2+ (предпочтительно TLS 1.3) для всех подключений; проверка сертификата, никаких глобальных «игнорировать SSL ошибки».
2. E2EE между Host и Android. Использовать проверенные библиотеки и протоколы установления ключа (например, X25519 + HKDF + AEAD с корректным nonce/key rotation), не писать самодельную криптографию. Pairing аутентифицирует ключи; VPS не хранит приватные ключи.
3. Android private keys хранить через Android Keystore при доступности; desktop — DPAPI Windows / Secret Service или другой защищённый user-level key storage Linux с описанным fallback. Не хранить секреты в plain config.
4. Чёткие права `view_session`, `send_message`, `approve_action`, `upload_artifact`, `capture_screen`, `manage_device`, `manage_host` — отдельно и revocable.
5. Отзывать устройство, блокировать скомпрометированный Host, ограничивать попытки авторизации, MFA для администрирования при необходимости.
6. Запрет произвольного исполнения команд *через Relay API*: только авторизованный локальный Host и только его agent actions/explicit terminal mode; terminal mode обязательно требует отдельной настройки и предупреждения.
7. Ротация ключей, тесты восстановления, подтверждение новых устройств на доверенном устройстве, TTL pairing.
8. E2EE ограничивает чтение **на Relay**, но не передачу контекста провайдеру AI: Codex/Claude по-прежнему используют свои облачные API согласно настройкам пользователя.
9. Независимый security review до публичного релиза, SAST/dep audit/SBOM, подпись релизов и reproducible-ish build instructions.

## 10. Google Play и приватность

- Android приложение не должно требовать `VpnService` для основной работы: HTTPS/WSS через обычный сетевой стек, совместимый с действующим VPN пользователя.
- Минимизировать permissions: INTERNET, POST_NOTIFICATIONS только при необходимости, фото/файлы через системные pickers. Отказ от AccessibilityService для удалённого контроля PC; не нужно его запрашивать.
- Заполнить Data safety достоверно по фактической реализации. Опубликовать Privacy Policy (что может проходить через **выбранный пользователем VPS**, что локально хранится, есть ли аналитика, crash reporting, retention, удаление данных).
- Без скрытого фонового доступа к экрану или компьютеру; ясный setup wizard и индикатор активного Host. Учесть target API, правила foreground service и runtime permissions на момент релиза.
- Для ревью Play предоставить демонстрационный аккаунт/инстанс или воспроизводимый демо-режим, чтобы проверяющий увидел core features без самостоятельного развёртывания VPS. Демо не должно выдавать себя за реальные пользовательские сеансы.
- Лицензии и trademark третьих CLI соблюдать; приложение **не аффилировано** с OpenAI, Anthropic, Google и другими, если нет соответствующего соглашения.
- Проверить условия SDK/CLI провайдеров и совместимость с дистрибуцией. Не поставлять API tokens и не обходить платные планы.

## 11. Монетизация и лицензирование

Продукт можно продавать как **готовое удобное приложение**, оставляя Relay self-hosted. Варианты: бесплатный базовый Android-клиент + Pro функции (несколько серверов, расширенная галерея, дополнительные UI/workflow удобства), либо разовая покупка, если правила магазина и биллинга это допускают. **Не делать базовую безопасность, E2EE, отзыв устройств или подтверждения опасных действий платными.**

Если использовать чужой код, выполнить аудит лицензии каждого заимствованного компонента, сохранить copyright/NOTICE/условия и корректно выбрать лицензию монорепозитория. Вдохновение UI/поведением не равно автоматическому разрешению копировать чужой исходный код.

## 12. План реализации / milestones

### M0 — исследование и архитектурный spike

- Зафиксировать версии KMP/Compose/Ktor/Gradle по официальным релизам на момент начала.
- Проверить на Windows и Linux запуск/продолжение управляемого Codex через app-server; составить capability matrix для Claude/OpenCode/Gemini; понять ограничения внешних CLI.
- Создать threat model и ADR о шифровании, pairing, relay visibility, offline queue.
- **Acceptance:** таблица гарантированных и негарантированных сценариев; тестовый Codex managed turn с approval.

### M1 — Relay MVP (headless + GUI) + Host CLI + Desktop UI

- Ktor Relay, Docker Compose, HTTPS/WSS через Caddy, диагностика health/version, device auth и secure pairing.
- Host Windows/Linux, managed Codex adapter, список сессий, текстовый streaming, reconnect.
- Desktop UI с pairing и состоянием Host; запустить Host без UI.
- **Acceptance:** два устройства авторизуются на локальном self-hosted Relay; управление одной managed Codex-сессией; завершение GUI не отключает Host.

### M2 — Android MVP

- Подключение по URL, QR pairing, список устройств/сессий, текстовый чат, потоковые ответы, approvals, handoff и reconnect.
- **Acceptance:** написать из Windows, открыть Android через другую сеть, продолжить managed-сессию, подтвердить действие, вернуться на desktop.

### M3 — E2EE + изображения + файлы

- Проверенная реализация E2EE, ревокация устройств, шифрованная передача вложений, инструмент publish_artifact и capture_screen с локальным согласием.
- **Acceptance:** отправить PNG Android→Codex; Host отправляет screenshot→Android как inline image; тестовый VPS не может прочитать payload; отозванное устройство теряет доступ.

### M4 — другие агенты + внешние сессии

- Claude Code/OpenCode/Gemini, Generic PTY; обнаружение и типизация external/resumable/read-only; политика конфликтов.
- **Acceptance:** capability matrix проходит automated contract tests; UI не выдаёт resumable за managed live.

### M5 — Public beta / Google Play

- User docs, one-command self-host compose, Linux packages (.deb/AppImage по оценке), Windows installer/MSI, Android release AAB, подписи, crash handling, миграции схемы.
- Privacy Policy, Data safety, age/content declarations, официальный test track, security review.
- **Acceptance:** новый пользователь поднимает сервер по документации, привязывает 2 устройства, успешно работает через CDN и после сетевого разрыва.

## 13. Tests / quality gates

- Unit: протокол, crypto wrappers, version negotiation, event ordering, idempotency, capabilities.
- Integration: Ktor Relay + Host + Android simulated client, network drop/reconnect, replay, approvals timeout, very large artifact chunks, expired QR.
- System: Win10/Win11; Linux Ubuntu X11/Wayland (захват может требовать разрешения); Android 11+ (целевой minSdk определить исследованием); разные mobile networks, проксируемые CDN WSS, NAT.
- Security: privilege escalation, XSS в Markdown HTML, command injection, symlink/path traversal, secrets leakage in logs, session impersonation, orphan uploads, lost device revocation.
- UX: сценарий переключения не более нескольких явных действий после первоначальной привязки, доступность офлайн, сообщения об ошибках понятным языком.

## 14. Definition of Done первой публичной версии

Продукт можно считать готовым, если:

- Любой пользователь может установить Relay на **свой** Linux VPS и подключиться с домена с валидным TLS.
- Windows и Linux Host запускаются от имени пользователя и работают после закрытия GUI.
- Android клиент доступен как независимое приложение, не нуждается в обязательном общем аккаунте/стороннем relay.
- Managed Codex сессии поддерживают двусторонний текст, поток событий, корректные approvals, handoff без потери состояния.
- Android отправляет изображение, агент/Host присылает изображение; скриншот создаётся только в рамках явных разрешений.
- E2EE, подтверждение устройства и отзыв ключа реально протестированы.
- Пользователь всегда видит, является ли внешняя сессия LIVE, RESUMABLE или READ-ONLY.
- Отсутствуют критические ошибки безопасности; опубликованы политика приватности, документация развёртывания и источники необходимых лицензий.

## 15. Задание Codex на старт

> Прочитай этот документ целиком. Начни **только с M0**, не пытайся реализовать весь продукт за один заход. Создай монорепозиторий с базовыми Gradle модулями и чистыми интерфейсами; подготовь `docs/architecture.md`, `docs/protocol.md`, `docs/security.md`, `docs/agent-capability-matrix.md`, ADR по transport/E2EE/adapter architecture. Проверь реальные официальные интерфейсы агентов и поддержку Windows/Linux. Не обещай live attach произвольных CLI. После M0 реализуй минимальный вертикальный срез M1: Docker Relay → Host managed Codex → desktop client с отправкой сообщения, streaming и approval. Все секреты — только в test fixtures; не добавляй `trust-all TLS`, открытые remote shell endpoint или команды захвата экрана без согласия. В конце каждого этапа приложи тесты, команды запуска, ограничения и список конкретных задач следующего этапа.

## 16. Режимы Desktop и Relay: обязательная спецификация v0.2

### 16.1 Единые ядра, разные процессы

- `host-engine`: управляет CLI на Windows/Linux, запускается под интерактивным пользователем; GUI может закрываться без остановки Host.
- `relay-engine`: Ktor/JVM, транспорт и маршрутизация. Запускается как `agent-bridge relay --headless --config /etc/agent-bridge/relay.yml`, как Windows Service/systemd либо через переключатель Desktop GUI. GUI и headless используют **один движок, один формат настроек, один протокол**, не копии серверной логики.
- `desktop-app`: Compose Desktop настройки, чат, устройства, управление режимами Host/Relay. GUI **не обязан** постоянно работать для Host/Relay. Если включены оба режима — раздельные процессы или service supervisor, независимый lifecycle, разные права.
- `android-app`: опубликованный в Google Play клиент без собственного VPN-движка.
- Не путать headless VPS с GUI: JVM Runtime и Ktor без графического окружения, X11/Wayland не требуются.

### 16.2 Таблица конфигураций

| Конфигурация | Host | Relay | Предполагаемое соединение |
| --- | --- | --- | --- |
| Домашний ПК + свой VPS | Windows/Linux | Linux VPS | Оба устройства исходящим WSS к VPS |
| Домашний ПК внутри LAN | Windows/Linux | тот же ПК | Android к IP ПК через LAN; HTTPS с доверенным сертификатом |
| Windows сервер | отдельный ПК | Windows Service | WSS к DNS/IP сервера |
| Linux сервер | отдельный ПК | systemd | WSS к DNS/IP сервера |
| Один ПК со всеми функциями | тот же ПК | тот же ПК | LAN либо отдельно настроенная внешняя доступность |
| Пользователь уже использует VLESS | отдельный ПК | его VPS с HTTPS virtual host | VPN остаётся нетронутым, WSS отдельно через CDN/домен |

### 16.3 Установка без Docker

**Linux VPS:** публиковать подписанный `.deb` и по возможности `.rpm`, собственный bundled JRE через jlink/jpackage либо совместимый runtime dependency; устанавливать сервисного пользователя, каталоги `/etc/agent-bridge/`, `/var/lib/agent-bridge/`, systemd unit, CLI `agent-bridge setup`, `agent-bridge doctor`, `agent-bridge relay start`, `agent-bridge update` (либо OS package manager). После `apt install ./agent-bridge-relay.deb` и мастера допускается запуск `systemctl enable --now agent-bridge-relay`. Не открывать публичный API до завершения pairing/настройки аутентификации. Безопасный uninstall и сохранение конфигурации на обновлениях.

**Windows:** установщик `.msi`/`.exe`, режим `Host`, `Relay`, `Both`; Windows Service для Relay, отдельный **пользовательский** Host для ConPTY/взаимодействия с интерактивным desktop. Прозрачный UAC при установке службы; хранение ключей с DPAPI. Нельзя предполагать, что Windows Service в Session 0 способен захватить экран пользователя.

**Desktop GUI:** мастера «Запустить Host», «Запустить Relay», «Запустить оба», «Подключиться к существующему Relay». Экран состояния: адреса, TLS, порт, connected devices, диагностика DNS/WSS, успешный pairing, состояние service. Не включать Relay на публичном интерфейсе без аутентификации/TLS.

### 16.4 Встроенный Relay и внешняя сеть

Relay может слушать `127.0.0.1:port` за reverse proxy или, при явной настройке и TLS, собственный интерфейс. Включение Relay **не равно** получению публичного IP. Для домашней сети без маршрутизируемого адреса предусмотрены: локальная Wi-Fi сеть; существующая VPN-сеть (WireGuard/VLESS-клиент, если обеспечивает доступ к узлу); внешний VPS; пользовательский reverse tunnel. Продукт не должен обещать прямой доступ через произвольный NAT.

### 16.5 Сосуществование с VPN на Android

Android-приложение использует обычные HTTPS/WSS соединения через ОС, **не вызывает `VpnService`**, не создаёт TUN и не модифицирует системные маршруты. Поэтому оно не заменяет активный VPN пользователя; трафик обычно идёт через VPN, если его правила так настроены. Настройки split tunnel, DNS, блокировки домена, firewall и доступность CDN могут повлиять на работу — на экране диагностики показать причину и рекомендации, не предлагать отключение VPN как обязательное действие.

**VLESS не является transport protocol приложения.** В сценарии `VLESS + CDN + домен` существующая настройка Xray/VLESS остаётся, а отдельный `agents.example.org` (или path, если маршрутизация позволяет) обслуживает TLS/WSS для Agent Bridge. Проверить, что CDN проксирует WebSocket, заголовки Upgrade/Connection и ожидаемые таймауты; включить heartbeat, backoff, resume и chunked upload. Для Relay за reverse proxy предусмотреть forwarded headers только от доверенных proxy и rate limiting.

### 16.6 Pairing и эксплуатация

По умолчанию сервер запускается с `no public enrollment`; первичная привязка — через CLI/GUI локального администратора, одноразовый код/QR и проверку fingerprint. Ключи на Host и Android. Шифрование файлов, скриншотов и содержимого чатов сквозное (E2EE) до отправки. Сам Relay видит минимальные routing metadata и размеры сообщений; отсутствие просмотра контента не означает отсутствия метаданных. Ограничить размеры/скорости, TTL неполученных пакетов, квоты вложений, rotation/revocation, структурированные журналы без payload.

### 16.7 Google Play, UX и границы продукта

- В первом релизе **не использовать** `android.permission.BIND_VPN_SERVICE` / `VpnService`; собственный VPN не нужен. Это уменьшает сложность и количество policy-требований, но не даёт автоматического одобрения Google Play.
- При желании будущий транспорт через VPN — **только подключение по IP/DNS уже существующей сети**, без создания своего Android VPN.
- Мастер настройки: `Подключиться к своему серверу` → `URL` → `Проверка TLS` → `Pairing QR` → `Выбор Host` → `Сессии`; отдельный режим LAN discovery только внутри локальной сети.
- Показать уведомления об approvals, новые файлы и скриншоты; push требует дополнительного проектирования приватности и может включать внешнего push-провайдера. Отправлять во внешние push-сервисы **только обезличенный wake-up event**, без AI текста, имён проектов и содержимого файлов; при строгом zero-third-party режиме — polling/foreground connection с ограничениями Android.
- Прямое подключение к чужому публичному Relay без доверенного TLS и pairing запрещено по умолчанию.

### 16.8 Критерии приёмки для Codex

1. Linux VPS: установка `.deb` на чистый сервер **без Docker и GUI**, `systemctl status`, WSS за Caddy/Nginx, сохранение конфигурации после обновления.
2. Windows: GUI позволяет включить Relay, остановить GUI и подтвердить, что служба Relay продолжает работать.
3. Linux Desktop: Host+Relay одновременно, безопасное завершение независимо друг от друга.
4. Android: с уже включённым сторонним VLESS/WireGuard VPN открывает пользовательский HTTPS Relay и проводит pairing; VPN остаётся активным (при корректных DNS/route).
5. Созданная нашим Host сессия Codex: Desktop → Android → Desktop, непрерывная история, поток событий и разрешения.
6. Внешняя уже запущенная сессия отображает capability state `observe` / `resume` / `live-control`; UI не обещает `live-control`, если адаптер не умеет этого.
7. Скриншот передаётся от Host на Android как inline preview только после разрешения на захват; файлы шифруются до VPS.
8. Никакого обращения к серверам разработчика в базовом режиме, кроме явно разрешённых пользователем сервисов (например, AI-провайдера самим CLI).
9. Тесты: reconnect после смены Wi-Fi на LTE, токен revoke, MITM/TLS reject, replay approval, large file, CDN WebSocket timeout, mobile background restrictions.

## 17. Задание Codex на старт для версии v0.2

1. Прочитай весь PRD и выпиши ADR для `KMP Shared`, `Host Engine`, `Relay Engine`, `transport`, `crypto`, `OS service`.
2. Создай Gradle monorepo и минимальные сборки `androidApp`, `desktopApp`, `relayHeadless`; `relayHeadless` и Desktop должны использовать одну библиотеку `relay-engine`.
3. Реализуй простое подключение к Relay через WSS **с тестовыми ключами только в dev-среде**; до появления полноценного аутентифицированного E2EE не публикуй сервис на публичном интерфейсе.
4. Реализуй installer/systemd unit для headless Linux и service wrapper для Windows с smoke tests.
5. Разработай Codex Adapter spike; задокументируй, какие функции можно реализовать структурно, а какие зависят от способа запуска CLI.
6. Построй сквозной интеграционный тест Desktop Host ↔ headless Relay ↔ Android, затем pairing/E2EE, approvals, images, и только после этого расширяй поддержку агентов.
7. Перед каждой вехой обновляй compatibility matrix и tests; не подменяй тесты декларациями в README.

## 18. Справочные источники (проверять версии перед реализацией)

- Compose Multiplatform: https://github.com/JetBrains/compose-multiplatform
- Codex App Server: https://openai.com/index/unlocking-the-codex-harness/
- Cloudflare WebSocket docs: https://developers.cloudflare.com/network/websockets/
- Google Play VPN policy: https://support.google.com/googleplay/android-developer/answer/12564964
- Google Play sensitive API policy: https://support.google.com/googleplay/android-developer/answer/16558241



### Официальные ссылки для transport/релиза (октябрь 2026)

- Android VpnService (ограничение на активный VPN): https://developer.android.com/develop/connectivity/vpn
- Google Play VPN policy: https://support.google.com/googleplay/android-developer/answer/16558241
- Compose Multiplatform supported targets: https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html

> При реализации проверять действующие версии библиотек и политики на дату выпуска.

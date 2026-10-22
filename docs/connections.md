# Все способы подключения

## Как выбрать режим

| Задача | Что запускать | Адрес | Где работают агенты |
|---|---|---|---|
| Только этот Windows/Linux компьютер | Установленный Desktop | автоматически | на этом компьютере |
| Телефон рядом с Windows ПК | `start-android.cmd` из Android Host ZIP | USB, ввод не нужен | на Windows ПК |
| Телефон и ноутбук через интернет | Relay на VPS + обычный `connect-vps.cmd` | `https://cdn.restorkava.ru` | на Windows ПК |
| Проекты лежат на VPS | Relay и Host на VPS + `-ClientOnly` на Windows | `https://cdn.restorkava.ru` | на VPS |
| Разработка из исходников | `start-local-router.ps1` | `http://127.0.0.1:8787` | на машине разработки |

Relay маршрутизирует зашифрованные команды. Host запускает CLI, читает рабочие
папки и хранит каталог вкладок. Поэтому расположение Host определяет, какие файлы
видят агенты.

## Быстрая проверка через один Router

Запустите установленный `AgentBridge.exe`. На первом экране нажмите большую кнопку
**Запустить один Router**. Приложение само поднимет один локальный Relay и Host и
подключит UI. URL, порт, token и key вводить не нужно. После появления статуса
**Один Router работает** создавайте вкладки Codex, Claude Code и OpenCode ниже.

Чтобы подключить уже установленное Android-приложение к этому же Router, соедините
телефон по USB и нажмите в Desktop UI **Подключить телефон по USB**. UI сам выполнит
`adb reverse`, передаст адрес и временные ключи и откроет приложение. На компьютере
должен быть доступен `adb`, на телефоне — включён USB debugging.

Для удалённого телефона выберите в Android режим VPS: один Router будет работать
на сервере, а Windows Host и оба UI подключатся к нему.

## 1. Локальный Desktop

Windows: установите `AgentBridge-Windows-x64-0.1.0.exe` или распакуйте portable ZIP
и запустите `AgentBridge.exe`. Linux: установите DEB командой:

```bash
sudo apt install ./AgentBridge-Linux-amd64-0.1.0.deb
```

Приложение само поднимает loopback Relay и Host. URL, token и key вводить не нужно.
Хотя бы один поддерживаемый CLI должен быть установлен и авторизован для текущего
пользователя ОС.

## 2. Android по USB

1. Включите режим разработчика и USB debugging.
2. Установите Android Platform Tools, чтобы `adb` был в `PATH`.
3. Распакуйте `AgentBridge-Android-Host-Windows-x64-0.1.0.zip`.
4. Подключите один телефон, подтвердите RSA-разрешение и запустите
   `start-android.cmd`.

Launcher установит APK, создаст временные ключи, выполнит `adb reverse` и запустит
Host. Терминал должен оставаться открытым.

## 3. VPS Relay, Host на Windows

Это основной удалённый режим. На VPS находятся только Relay и TLS reverse proxy.
Windows Host устанавливает исходящее WebSocket-соединение, поэтому домашний IP,
NAT и проброс портов не нужны. Точные команды для вашего домена:
[Развёртывание на cdn.restorkava.ru](vps-quickstart.md).

На сервер можно поставить Relay из GitHub Release через sparse checkout: VPS
получит только `deployment/vps`, а bootstrap скачает готовый серверный пакет.

На Windows запускайте обычный `connect-vps.cmd`. Закрытие Desktop завершает
запущенный этим launcher процесс Host, поэтому для ночной работы окно Desktop и
launcher должны оставаться запущенными.

## 4. VPS Relay и Host на Linux VPS

Android и Desktop управляют CLI на сервере. Рабочие пути указываются в Linux-виде,
например `/srv/projects/my-app`. CLI и его credentials должны принадлежать тому же
Linux-пользователю, под которым работает Host. Windows Desktop запускается с
`connect-vps.cmd -ClientOnly`.

Этот режим нельзя совмещать со вторым Host под тем же Relay: идентификатор Host
пока один.

## 5. Ручное подключение клиента

В UI выберите **VPS** и заполните три значения отдельно либо вставьте код:

```text
https://cdn.restorkava.ru|TOKEN|BASE64URL_KEY
```

URL должен быть корневым HTTPS URL без завершающего пути. Key после base64url
декодирования должен содержать ровно 32 байта. Desktop также читает переменные
`AGENT_BRIDGE_RELAY_URL`, `AGENT_BRIDGE_DEV_TOKEN` и `AGENT_BRIDGE_DEV_KEY`.

Android production-конфигурация принимает HTTPS. Незашифрованный HTTP разрешён
только в debug-сборке для loopback/USB разработки.

## 6. Запуск из исходников

Локальный Desktop:

```powershell
.\scripts\start-local-router.ps1
```

Android по USB без Desktop:

```powershell
.\scripts\start-local-router.ps1 -Android -NoDesktop
```

Ручной запуск трёх процессов описан в [README](../README.md#локальный-зашифрованный-dev-чат).

## Вкладки и несколько агентов

Каталог вкладок хранится на Host в `~/.agentbridge/tabs.json` (на Windows — в
`%USERPROFILE%\.agentbridge\tabs.json`). Можно сохранить до 100 вкладок и держать
до 20 активных сессий. Каждая вкладка содержит имя, тип агента, рабочую папку,
режим автоодобрения и session id. Телефон и Desktop показывают один каталог,
когда подключены к одному Host.

Relay текущей версии допускает по одному активному подключению каждого типа:
один Host, один Desktop и один Android. Несколько вкладок работают внутри этого
Host и не требуют отдельных портов или серверов.

## Проверка и типовые ошибки

```bash
curl http://127.0.0.1:8787/health
curl https://cdn.restorkava.ru/health
curl https://cdn.restorkava.ru/.well-known/agent-bridge
sudo systemctl status agent-bridge-relay caddy --no-pager
```

- Локальный `curl` работает, публичный нет: проверьте DNS, firewall, Caddy и 80/443.
- Ошибка сертификата: A/AAAA ведёт не на VPS, порт 443 закрыт или сертификат ещё не получен.
- `401 Unauthorized`: token различается на Relay, Host и клиенте.
- Соединение есть, команд нет: проверьте одинаковый key.
- Агент не найден: запустите CLI под пользователем Host и проверьте `PATH` и авторизацию.
- Вторая машина выбивает первую: подключён второй клиент с тем же device id.
- После обрыва нет старых событий: постоянная replay-очередь ещё не реализована;
  перезапустите Host и клиент, затем возобновите сохранённую вкладку.

Секреты находятся в `/etc/agent-bridge/relay.env` на VPS и
`%APPDATA%\AgentBridge\vps-connection.conf` на Windows. При утрате устройства
смените одновременно token и key и перезапустите Relay.

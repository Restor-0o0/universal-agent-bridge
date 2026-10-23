# Развёртывание на `cdn.restorkava.ru`

Один Relay поддерживает несколько изолированных пользователей и компьютеров.
После установки добавляйте пространства командой
`sudo /opt/agent-bridge/add-workspace.sh NAME`. Подробности:
[несколько пользователей на одном Router](multi-user-router.md).

Рекомендуемая схема для первого запуска:

```text
Android ── HTTPS/WSS ──┐
Desktop ─ HTTPS/WSS ───┼── cdn.restorkava.ru:443 ── Relay на VPS
Windows Host + CLI ────┘
```

На VPS работает только Relay. Codex, Claude Code и OpenCode запускаются на
Windows-ноутбуке, там же остаются проекты и каталог вкладок. Все соединения с
ноутбука исходящие, поэтому проброс портов на домашнем роутере не нужен.

## Перед установкой

Проверьте в панели DNS, что A-запись `cdn.restorkava.ru` указывает на публичный
IPv4 VPS. Добавляйте AAAA только при рабочем публичном IPv6. На VPS должны быть
доступны TCP-порты 80 и 443, а SSH-порт нельзя закрывать.

Проверьте, не занят ли домен существующим сайтом и какой reverse proxy уже работает:

```bash
sudo ss -ltnp | grep -E ':(80|443|8787)\b' || true
sudo systemctl status caddy nginx --no-pager || true
curl -I https://cdn.restorkava.ru
```

Если `cdn.restorkava.ru` уже раздаёт нужные файлы или сайт, безопаснее выделить
Agent Bridge отдельный поддомен, например `agents.restorkava.ru`. Клиенту нужен
корневой HTTPS URL без дополнительного пути, поэтому адрес вида
`https://cdn.restorkava.ru/bridge` не подходит.

### Если TCP 443 занят VPN REALITY

Не переносите REALITY с 443 и не пытайтесь одновременно привязать Caddy к этому
порту. Создайте в DNS отдельную A-запись `agents.restorkava.ru`, направленную на
тот же VPS, и отключите для неё проксирование Cloudflare (режим **DNS only**).
Agent Bridge поддерживает HTTPS URL с явным портом, поэтому Caddy можно запустить
на TCP 8443:

```caddyfile
agents.restorkava.ru:8443 {
    reverse_proxy 127.0.0.1:8787
}
```

Откройте TCP 80 для получения TLS-сертификата и TCP 8443 для приложения, оставив
VPN на TCP 443:

```bash
sudo ufw allow 80/tcp
sudo ufw allow 8443/tcp
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
curl https://agents.restorkava.ru:8443/health
```

В Desktop и Android используйте адрес `https://agents.restorkava.ru:8443`.
Подключение работает и когда клиент использует VPN на этом же VPS: сервисы
занимают разные порты, а все соединения Host и приложений остаются исходящими.

## 1. Загрузить пакет на VPS

### Вариант A: установить напрямую из GitHub

После публикации хотя бы одного тега-релиза серверу достаточно получить только
каталог развёртывания:

```bash
git clone --filter=blob:none --sparse https://github.com/OWNER/REPOSITORY.git agent-bridge-deploy
cd agent-bridge-deploy
git sparse-checkout set deployment/vps
chmod +x deployment/vps/install-from-git.sh
./deployment/vps/install-from-git.sh
```

Скрипт скачает `AgentBridge-Relay-JVM-0.1.0.zip` из последнего GitHub Release,
поставит Java и утилиты при необходимости и вызовет серверный установщик. Для
обновления:

```bash
cd agent-bridge-deploy
git pull
./deployment/vps/install-from-git.sh
```

Чтобы поставить конкретную версию:

```bash
AGENT_BRIDGE_VERSION=v0.1.0 ./deployment/vps/install-from-git.sh
```

Для приватного репозитория установите и авторизуйте GitHub CLI `gh`; bootstrap
автоматически использует `gh release download`. Обычный `curl` рассчитан на
публичный Release.

Релиз создаётся автоматически после отправки тега:

```powershell
git tag v0.1.0
git push origin v0.1.0
```

GitHub Actions соберёт пакеты и приложит их к Release. Не создавайте Release
вручную до завершения workflow с тем же тегом.

### Вариант B: скопировать локально собранный ZIP

С Windows, из корня проекта:

```powershell
scp .\dist\AgentBridge-Relay-JVM-0.1.0.zip root@IP_СЕРВЕРА:/tmp/
```

Войдите по SSH и установите зависимости (команды рассчитаны на Ubuntu 24.04):

```bash
ssh root@IP_СЕРВЕРА
apt update
apt install -y openjdk-17-jre-headless unzip openssl curl
rm -rf /tmp/agent-bridge-release
mkdir /tmp/agent-bridge-release
unzip /tmp/AgentBridge-Relay-JVM-0.1.0.zip -d /tmp/agent-bridge-release
cd /tmp/agent-bridge-release/relay-vps
chmod +x install-relay.sh
sudo ./install-relay.sh
curl http://127.0.0.1:8787/health
```

Последняя команда должна вывести `ok`. Установщик копирует Relay в
`/opt/agent-bridge/relay`, создаёт отдельного пользователя, генерирует token и
32-байтный ключ в `/etc/agent-bridge/relay.env` и включает systemd service.
Порт 8787 слушает только loopback VPS. Не публикуйте его в firewall.

## 2. Подключить домен и HTTPS через Caddy

Если порты 80/443 свободны и на сервере нет Nginx, установите Caddy:

```bash
sudo apt install -y caddy
```

Затем создайте `/etc/caddy/Caddyfile`:

```caddyfile
cdn.restorkava.ru {
    reverse_proxy 127.0.0.1:8787
}
```

Если в Caddy уже есть другие домены, добавьте этот блок к существующему файлу,
не заменяя остальные блоки. Если блок `cdn.restorkava.ru` уже существует, весь
этот поддомен следует выделить Agent Bridge или создать `agents.restorkava.ru`.

Проверка и применение:

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
curl https://cdn.restorkava.ru/health
curl https://cdn.restorkava.ru/.well-known/agent-bridge
```

Первая команда `curl` должна вернуть `ok`, вторая — JSON discovery-документ.
Caddy сам получает и обновляет TLS-сертификат и проксирует WebSocket.

Если включён UFW:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

Перед `ufw enable` убедитесь, что правило соответствует вашему реальному SSH-порту.

### Если на сервере уже работает Nginx

Не ставьте Caddy поверх занятого порта 443. Добавьте proxy в существующий TLS-блок
`server` для `cdn.restorkava.ru`:

```nginx
location / {
    proxy_pass http://127.0.0.1:8787;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 1d;
}
```

Сертификат и `listen 443 ssl` остаются в существующей конфигурации Nginx. Затем:

```bash
sudo nginx -t
sudo systemctl reload nginx
curl https://cdn.restorkava.ru/health
```

В этом варианте сервис Caddy не нужен. Если домен уже обслуживает другой сайт,
используйте новый поддомен: приложение не поддерживает базовый URL с путём.

## 3. Получить данные подключения

На VPS:

```bash
sudo cat /etc/agent-bridge/relay.env
```

Код для Android имеет формат:

```text
https://cdn.restorkava.ru|TOKEN|KEY
```

Не отправляйте этот код в публичный чат и не добавляйте `relay.env` в Git.

## 4. Подключить Windows Host и Desktop

Распакуйте `AgentBridge-VPS-Client-Windows-x64-0.1.0.zip` и дважды нажмите
`connect-vps.cmd`. При первом запуске укажите URL `https://cdn.restorkava.ru`,
затем значения `AGENT_BRIDGE_DEV_TOKEN` и `AGENT_BRIDGE_DEV_KEY`.

Launcher сохраняет настройки в `%APPDATA%\AgentBridge\vps-connection.conf`,
запускает Host и Desktop и показывает готовый код для телефона. Чтобы сменить адрес
или ключи:

```powershell
.\connect-vps.cmd -Reset
```

На ноутбуке заранее должен быть установлен и авторизован хотя бы один CLI:
Codex, Claude Code или OpenCode. Пути к исполняемым файлам вводить не нужно.

## 5. Подключить Android

Установите `AgentBridge-Android-debug-0.1.0.apk`. В приложении выберите **VPS**,
вставьте весь код `URL|TOKEN|KEY`, нажмите **Применить код**, затем
**Подключиться**. Разрешите уведомления и включите будильник помощи, если он нужен.

## Вариант: агенты и проекты находятся на VPS

Этот вариант нужен, когда CLI должны работать с файлами Linux-сервера. Пакет Relay
также содержит каталог `host`.

1. Установите нужные CLI на VPS и авторизуйте их под обычным Linux-пользователем.
2. Прочитайте token/key из `/etc/agent-bridge/relay.env`.
3. Под этим же пользователем запустите Host:

```bash
export AGENT_BRIDGE_DEV_TOKEN='TOKEN'
export AGENT_BRIDGE_DEV_KEY='KEY'
chmod +x /tmp/agent-bridge-release/relay-vps/host/bin/hostApp
/tmp/agent-bridge-release/relay-vps/host/bin/hostApp \
  --connect-relay-dev http://127.0.0.1:8787
```

Host следует держать в `tmux`/`screen` или оформить пользовательским systemd unit.
Он должен работать под пользователем, у которого настроены CLI и доступ к проектам.
На Windows в таком режиме запускайте только интерфейс:

```powershell
.\connect-vps.cmd -ClientOnly
```

К одному Relay можно подключить несколько Host. Для каждого пользователя задайте
отдельное пространство, а каждому компьютеру — уникальный `HOST_ID` внутри него.

## Обновление, смена ключей и диагностика

Для обновления снова распакуйте новый ZIP и запустите `sudo ./install-relay.sh`.
Существующий `relay.env` сохраняется. Чтобы отозвать старые клиенты, замените обе
строки в `/etc/agent-bridge/relay.env`, перезапустите Relay и настройте клиенты заново.

```bash
sudo systemctl restart agent-bridge-relay
sudo systemctl status agent-bridge-relay --no-pager
sudo journalctl -u agent-bridge-relay -n 100 --no-pager
sudo journalctl -u caddy -n 100 --no-pager
```

На Windows логи Host: `%APPDATA%\AgentBridge\vps-host.log` и
`vps-host-error.log`. Полная таблица вариантов и типовые ошибки:
[Подключения](connections.md).

Каждое пространство использует собственный пароль Router и AES-256-GCM ключ.
После утраты телефона удалите или замените данные соответствующего пространства;
индивидуальный отзыв одного устройства пока не реализован.

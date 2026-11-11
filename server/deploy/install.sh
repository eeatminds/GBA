#!/usr/bin/env bash
# Installs (or updates) the GBA/GBC Emulator room server on an Ubuntu server
# (22.04, 24.04 or 26.04), behind HTTPS with a free, auto-renewing certificate.
#
# Before running it, point your domain's DNS "A" record at this server's IP.
# Then, on the server:
#
#   curl -fsSL https://raw.githubusercontent.com/eeatminds/GBA/main/server/deploy/install.sh | sudo bash -s -- gba.eeatminds.in
#
# Run the same command again any time to update the server to the latest code.
set -euo pipefail

DOMAIN="${1:-}"
REPO="${REPO:-https://github.com/eeatminds/GBA.git}"
BRANCH="${BRANCH:-main}"
APP_DIR=/opt/gba-rooms
SERVICE=gba-rooms
ENV_FILE=/etc/gba-rooms.env
PORT=8080
CADDYFILE=/etc/caddy/Caddyfile

say() { printf '\n==> %s\n' "$*"; }
fail() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || fail "Run this as root, for example with sudo."
[[ -n "$DOMAIN" ]] || fail "Give the server's address, for example: install.sh gba.eeatminds.in"
grep -qi ubuntu /etc/os-release || fail "This script is for Ubuntu."

# Caddy needs ports 80 and 443 for HTTPS. Stop if another web server already holds them.
if command -v ss >/dev/null; then
  other=$(ss -ltnpH '( sport = :80 or sport = :443 )' 2>/dev/null | grep -v '"caddy"' || true)
  if [[ -n "$other" ]]; then
    printf '%s\n' "$other"
    fail "Another program already uses port 80 or 443 (shown above, often nginx or apache). Stop it, or ask for a setup that works with it."
  fi
fi

say "Installing system packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q curl git ca-certificates gnupg debian-keyring debian-archive-keyring apt-transport-https

# Warn (but go on) when the domain doesn't point here yet: the certificate is retried until it does.
public_ip=$(curl -fsS --max-time 10 https://api.ipify.org || true)
domain_ip=$(getent ahostsv4 "$DOMAIN" | awk 'NR==1 {print $1}' || true)
if [[ -z "$domain_ip" ]]; then
  echo "Note: $DOMAIN doesn't resolve yet. Add an A record pointing to ${public_ip:-this server}."
elif [[ -n "$public_ip" && "$domain_ip" != "$public_ip" ]]; then
  echo "Note: $DOMAIN points to $domain_ip, but this server is $public_ip. HTTPS starts working once DNS points here."
fi

if ! command -v node >/dev/null || (( $(node -p 'process.versions.node.split(".")[0]') < 20 )); then
  say "Installing Node.js 22"
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y -q nodejs
fi

if ! command -v caddy >/dev/null; then
  say "Installing Caddy (HTTPS)"
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -q
  apt-get install -y -q caddy
fi

say "Getting the server code ($BRANCH)"
id -u gbarooms >/dev/null 2>&1 || useradd --system --home-dir "$APP_DIR" --shell /usr/sbin/nologin gbarooms
if [[ -d "$APP_DIR/.git" ]]; then
  # The folder belongs to the service user, so tell git it is safe to update it as root.
  git -c safe.directory="$APP_DIR" -C "$APP_DIR" fetch -q --depth 1 origin "$BRANCH"
  git -c safe.directory="$APP_DIR" -C "$APP_DIR" reset -q --hard FETCH_HEAD
else
  rm -rf "$APP_DIR"
  git clone -q --depth 1 --branch "$BRANCH" "$REPO" "$APP_DIR"
fi
(cd "$APP_DIR/server" && npm ci --omit=dev --no-audit --no-fund)
chown -R gbarooms:gbarooms "$APP_DIR"

# Settings live outside the code folder, so updates keep them.
if [[ ! -f "$ENV_FILE" ]]; then
  cat > "$ENV_FILE" <<EOF
# Room server settings. After changing them: sudo systemctl restart $SERVICE
PORT=$PORT
HOST=127.0.0.1
APP_NAME=GBA/GBC Emulator
APP_SCHEME=retrolink
# Fill these in after the first Play Store upload, so invite links open the app directly:
ANDROID_PACKAGE=
ANDROID_CERT_SHA256=
PLAY_STORE_URL=
EOF
fi

# Pictures for the games page (optional): <game>.webp, .png or .jpg, e.g. crystal.png.
install -d -m 755 /etc/gba-rooms/covers

say "Starting the room server"
cat > "/etc/systemd/system/$SERVICE.service" <<EOF
[Unit]
Description=GBA/GBC Emulator room server
After=network-online.target
Wants=network-online.target

[Service]
User=gbarooms
WorkingDirectory=$APP_DIR/server
EnvironmentFile=$ENV_FILE
ExecStart=$(command -v node) src/server.js
Restart=always
RestartSec=2
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable -q "$SERVICE"
systemctl restart "$SERVICE"

say "Setting up HTTPS for $DOMAIN"
block="# gba-rooms begin (managed by install.sh)
$DOMAIN {
	encode gzip
	reverse_proxy 127.0.0.1:$PORT
}
# gba-rooms end"
# A fresh Caddy install only has its example site: replace it. Otherwise keep the other sites.
if [[ ! -s "$CADDYFILE" ]] || ! grep -qvE '^\s*(#.*)?$|^\s*:80\s*\{|^\s*root \* /usr/share/caddy|^\s*file_server|^\s*\}' "$CADDYFILE"; then
  [[ -s "$CADDYFILE" ]] && cp "$CADDYFILE" "$CADDYFILE.bak"
  printf '%s\n' "$block" > "$CADDYFILE"
else
  sed -i '/# gba-rooms begin/,/# gba-rooms end/d' "$CADDYFILE"
  printf '\n%s\n' "$block" >> "$CADDYFILE"
fi
caddy validate --config "$CADDYFILE" --adapter caddyfile >/dev/null
systemctl enable -q caddy
systemctl reload caddy 2>/dev/null || systemctl restart caddy

if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow 80/tcp >/dev/null
  ufw allow 443/tcp >/dev/null
fi

say "Checking"
for _ in $(seq 1 15); do
  curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 && break
  sleep 1
done
curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null || fail "The room server didn't start. See: journalctl -u $SERVICE -n 50"

for _ in $(seq 1 30); do
  if curl -fsS --max-time 5 "https://$DOMAIN/health" >/dev/null 2>&1; then
    say "Done. The room server is live at https://$DOMAIN"
    exit 0
  fi
  sleep 3
done
say "The room server runs, but https://$DOMAIN isn't reachable yet."
echo "Check that $DOMAIN points to ${public_ip:-this server} and that ports 80 and 443 are open in your hosting panel's firewall."
echo "HTTPS starts by itself once they are. Logs: journalctl -u caddy -n 50"

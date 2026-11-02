# GBA/GBC Emulator room server

A small Node.js server that lets players meet in online rooms. It passes link-cable and
controller data between phones. It never receives games (ROMs) or save files.

- **Trade & battle rooms** connect separate Game Boy Advance games (up to 4 players, 5 with the wireless adapter).
- **Play together rooms** let 2 to 4 players share one multiplayer game.

## Put it on your own Ubuntu server (VPS)

Works on Ubuntu 22.04, 24.04 or 26.04. The script installs everything, keeps the server
running after restarts, and gets a free HTTPS certificate that renews by itself.

1. In your domain's DNS settings, add an **A record** for the address you want
   (for example `gba` in `eeatminds.in`) pointing to the server's IP address.
2. Make sure ports 80 and 443 are open in your hosting panel's firewall.
3. Log in to the server and run:

   ```bash
   curl -fsSL https://raw.githubusercontent.com/eeatminds/GBA/main/server/deploy/install.sh | sudo bash -s -- gba.eeatminds.in
   ```

4. It ends with "Done. The room server is live at https://gba.eeatminds.in".

Run the same command again later to update the server. Settings are in `/etc/gba-rooms.env`
(restart with `sudo systemctl restart gba-rooms` after changing them), and the logs are at
`journalctl -u gba-rooms`.

## Put it online (free, no coding)

1. Push this repository to GitHub.
2. Create a free account on [Render](https://render.com) and connect your GitHub.
3. In Render, choose **New → Blueprint** and pick this repository. Render reads `render.yaml`
   and starts the server.
4. Copy the address Render gives you, for example `https://retrolink-rooms.onrender.com`.
5. In the app, open the **Online** tab, tap **Set server address** and paste it.
   To build it into the app instead, set `app.onlineServerUrl` in `gradle.properties`.

Free Render servers go to sleep after 15 minutes without players, and the first room after
that takes about a minute to open. A paid plan (or any other host below) avoids that.

## Other ways to run it

Any host that runs Node.js 20+ or Docker and supports WebSockets works (Fly.io, Railway, a VPS).

```bash
cd server
npm ci --omit=dev
PORT=8080 node src/server.js
```

With Docker:

```bash
docker build -t retrolink-rooms server
```

```bash
docker run -p 8080:8080 retrolink-rooms
```

Put it behind HTTPS (most hosts do this for you). The app connects to `wss://<your host>/ws`.

## Settings

| Variable | What it does |
|---|---|
| `PORT` | Port to listen on (default 8080). |
| `HOST` | Address to listen on (default all). The install script uses `127.0.0.1`, behind HTTPS. |
| `APP_NAME` | Name shown on invite pages. |
| `APP_SCHEME` | Link scheme the app opens, `retrolink` by default. Must match `app.linkScheme`. |
| `PLAY_STORE_URL` | Optional Play Store link shown on invite pages. |
| `ANDROID_PACKAGE`, `ANDROID_CERT_SHA256` | Your app id and signing certificate fingerprint. With both set, invite links (`https://<host>/r/CODE`) open the app directly. Find the fingerprint in Play Console → Test and release → App integrity → App signing. |

## Endpoints

| Path | Use |
|---|---|
| `GET /health` | Health check. |
| `GET /api/rooms` | Public rooms with free places. `?system=snes` filters by console. |
| `GET /api/rooms/<CODE>` | One room. |
| `POST /api/report` | Room reports from the app. They are written to the server log; check it regularly. |
| `GET /r/<CODE>` | Invite page with an "Open in app" button. |
| `GET /.well-known/assetlinks.json` | Android App Links verification. |
| `/ws` | WebSocket used by the app during a game. |

## Tests

```bash
npm test
```

# ChatApp

ChatApp includes chat, shared files, and a Games area. Race Park supports live rooms for up to six players through the same server.

Race Park joining options:

- **Quick Match** joins an available room or creates one.
- **Create private room** generates a three-letter invite code.
- **Join by code** opens a specific private room.
- **Share in chat** posts the room code into the conversation.

Current release: **v1.3.0** (Android version code 5).

## Independently updated games

ChatApp supports downloadable HTML5 game packs. Each game is a folder under `server/game-packs` containing `game.properties`, `index.html`, and its private assets. The Android Games tab downloads these folders into private app storage. Increasing a game's version makes an **Update** button appear, so game updates do not require a new ChatApp APK. Downloaded games remain installed and can launch offline (online multiplayer still requires the server). See `server/game-packs/README.md` and copy `_template` to start a game.

Native Kotlin games such as Race Park are still bundled into the APK and use the central catalog in `app/src/main/java/com/example/chatapp/games`.

A local real-time chat app with an Android Jetpack Compose client and a Kotlin/Ktor WebSocket server. It does not require an external API or cloud account.

## Run it in Android Studio

1. Let Gradle finish syncing.
2. Open `server/src/main/kotlin/Main.kt` and run its `main` function.
3. Start an Android emulator.
4. Select the `app` run configuration and press Run.
5. Enter a name and join the chat.

Run two emulators, or launch the app twice on different emulators, to test real-time messages between users.

The client is configured to use encrypted transport through `wss://somechatapp.ddns.net/chat`. Shared files use `https://somechatapp.ddns.net`.

## Public access setup

Ktor listens privately on `127.0.0.1:8080`. Caddy provides public HTTPS/WSS encryption. To reach it from the internet:

1. Keep the No-IP hostname `somechatapp.ddns.net` updated to the current public IP.
2. Forward external TCP ports `80` and `443` to this computer's local IPv4 address.
3. Allow inbound TCP ports `80` and `443` through Windows Firewall.
4. Put `caddy.exe` beside `Caddyfile`, then run `start-secure-server.bat`.

Transport is encrypted, but the app still has no user authentication. Anyone who discovers the hostname can currently join the chat and access shared files.

## Shared files

The app's **Files** tab uploads to `server-data/public` on the server computer. Individual files are limited to 100 MB and the shared folder is limited to 500 MB. Files in this directory are intentionally excluded from Git.

Because the current server has no authentication, anyone who can reach the public server address can upload or download these files. Do not store sensitive material there.

## 24-hour activity history

Chat messages and username join/leave activity are retained on the server for 24 hours in `server/server-data/history/events.log` when using the secure launcher. Recent activity is replayed when a client joins and expired records are removed automatically. The history file is excluded from Git.

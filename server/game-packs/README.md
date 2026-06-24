# ChatApp server game packs

Each independently updated game lives in its own folder here. ChatApp discovers valid folders from `/games/catalog` and downloads a ZIP from `/games/{id}/download`; no Android app rebuild is required.

To add a game:

1. Copy `_template` and rename the copy with a lowercase ID such as `my-game`.
2. Rename `game.properties.example` to `game.properties`.
3. Set the same ID in `game.properties`.
4. Build the game inside that folder, using `index.html` as its entry point.
5. Press **Refresh** on ChatApp's Games tab, then download or update the game. Restarting the server is not required.

Increasing `version` updates the version shown in ChatApp immediately. HTML, CSS, JavaScript, images, audio, and other static assets may be kept inside the folder. Game URLs cannot leave `https://somechatapp.ddns.net/games/` inside the app player.

Server-delivered game packs are suitable for HTML5/JavaScript games. Native Kotlin games such as Race Park remain bundled with the Android app.

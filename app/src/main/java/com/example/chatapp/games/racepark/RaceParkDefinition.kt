package com.example.chatapp.games.racepark

import com.example.chatapp.games.GameDefinition

object RaceParkDefinition {
    val game = GameDefinition(
        id = "race-park",
        name = "Race Park",
        version = "1.0.0",
        description = "A full-screen track racer with quick matches, private rooms, and live multiplayer cars.",
        shortDescription = "Tank controls",
        playerCount = "1–6 players",
        iconLetter = "R",
        accentColor = 0xFFEF584A,
        activityClass = RaceGameActivity::class.java,
    )
}

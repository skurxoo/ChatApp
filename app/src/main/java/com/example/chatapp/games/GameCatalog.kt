package com.example.chatapp.games

import com.example.chatapp.games.racepark.RaceParkDefinition

/** The one place that controls which games appear in ChatApp. */
object GameCatalog {
    val games: List<GameDefinition> = listOf(
        RaceParkDefinition.game,
    )

    init {
        require(games.map { it.id }.distinct().size == games.size) { "Game IDs must be unique" }
    }
}

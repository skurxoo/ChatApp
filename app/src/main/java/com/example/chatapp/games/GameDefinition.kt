package com.example.chatapp.games

import android.app.Activity

/** A small manifest every bundled ChatApp game provides. */
data class GameDefinition(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val shortDescription: String,
    val playerCount: String,
    val iconLetter: String,
    val accentColor: Long,
    val activityClass: Class<out Activity>,
    val delivery: GameDelivery = GameDelivery.BUNDLED,
    val launchUrl: String? = null,
    val packageUrl: String? = null,
)

enum class GameDelivery { BUNDLED, SERVER }

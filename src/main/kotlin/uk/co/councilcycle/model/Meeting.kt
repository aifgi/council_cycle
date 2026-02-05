package uk.co.councilcycle.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class Meeting(
    val councilId: String,
    val title: String,
    val url: String,
    @Serializable(with = LocalDateSerializer::class)
    val date: LocalDate,
    val committee: String = "",
)

package org.baumweg.buszaragoza

data class Stop(
    val id: String,
    val name: String,
    val line: String,
    val type: TransportType,
    val order: Int = 0,
    val code: String = id,
    val direction: Int = 0,
    val destination: String = "",
)

data class Arrival(
    val line: String,
    val destination: String,
    val minutes: List<Int>,
)

data class Favorite(
    val id: String,
    val line: String,
    val stopId: String,
    val stopName: String,
    val type: TransportType,
    val code: String = stopId,
    val direction: Int = 0,
    val destination: String = "",
    val description: String = "",
)

data class LineSchedule(
    val first: LineDeparture? = null,
    val last: LineDeparture? = null,
)

data class LineDeparture(
    val time: String,
    val from: String,
    val to: String,
)

data class LineInfo(
    val line: String,
    val type: TransportType,
    val stops: List<Stop>,
) {
    val firstStop: Stop? get() = stops.firstOrNull()
    val lastStop: Stop? get() = stops.lastOrNull()
    val destinationLabel: String get() = lastStop?.name ?: "Destino no disponible"
}

enum class TransportType { BUS, TRAM }

sealed interface SearchSuggestion {
    val label: String

    data class Line(val line: String, override val label: String) : SearchSuggestion
    data class StopResult(val stop: Stop) : SearchSuggestion {
        override val label: String = "${stop.name} · ${stop.line}"
    }
}

sealed interface Screen {
    data object Home : Screen
    data object Favorites : Screen
    data class Line(val line: String) : Screen
    data class StopDetail(val stop: Stop) : Screen
    data object Backup : Screen
}

data class UiState(
    val screen: Screen = Screen.Home,
    val query: String = "",
    val loading: Boolean = false,
    val message: String? = null,
    val favorites: List<Favorite> = emptyList(),
    val results: List<Stop> = emptyList(),
    val suggestions: List<SearchSuggestion> = emptyList(),
    val currentLine: LineInfo? = null,
    val arrivals: List<Arrival> = emptyList(),
    val lastUpdatedMillis: Long? = null,
)

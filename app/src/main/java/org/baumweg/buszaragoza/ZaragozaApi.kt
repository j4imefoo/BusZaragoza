package org.baumweg.buszaragoza

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

class ZaragozaApi {
    private val base = "https://www.zaragoza.es/sede/servicio/urbanismo-infraestructuras/transporte-urbano"
    private val avanza = "https://zaragoza.avanzagrupo.com/wp-admin/admin-ajax.php"
    private val tranviaAjax = "https://www.tranviasdezaragoza.es/wp-admin/admin-ajax.php"
    private val fallbackBusLines = listOf(
        "21", "22", "23", "25", "28", "29", "30", "31", "32", "33", "34", "35", "36", "38", "39", "40",
        "41", "42", "43", "44", "50", "51", "52", "53", "54", "55", "56", "57", "58", "59", "60",
        "C1", "C4", "Ci1", "Ci2", "N1", "N2", "N3", "N4", "N5", "N6", "N7"
    )
    private val visibleBusLinePattern = Regex("^(?:\\d{2}|C\\d|Ci\\d|N\\d)$", RegexOption.IGNORE_CASE)
    private val unusableVisibleBusLines = setOf("CI3", "CI4", "EM1", "EM2")
    private val scheduleTtlMillis = 30 * 60 * 1000L
    private val lineCache = ConcurrentHashMap<String, LineInfo>()
    private val scheduleCache = ConcurrentHashMap<String, CachedSchedule>()
    private val allStopsLock = Any()
    @Volatile private var busLinesCache: List<String>? = null
    @Volatile private var allStopsCache: List<Stop>? = null

    private data class CachedSchedule(val value: LineSchedule, val savedAt: Long)

    fun warmCache() {
        busLines()
    }

    fun isLineQuery(query: String): Boolean = isExactLineQuery(query)

    fun suggestions(query: String, limit: Int = 8): List<SearchSuggestion> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val needle = normalize(q)
        val lineSuggestions = matchingLines(q).map { line -> SearchSuggestion.Line(line, lineLabel(line)) }
        val stopSuggestions = allStopsCache.orEmpty()
            .asSequence()
            .filter { normalize(it.name).contains(needle) || it.code.contains(q, ignoreCase = true) }
            .distinctBy { "${it.type}-${it.line}-${it.id}-${it.direction}" }
            .take(limit)
            .map { SearchSuggestion.StopResult(it) }
            .toList()
        return (lineSuggestions + stopSuggestions).take(limit)
    }

    fun search(query: String): List<Stop> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val lines = matchingLines(q)
        if (lines.size == 1 && isExactLineQuery(q)) return lineInfo(lines.first()).stops
        if (lines.isNotEmpty() && normalize(q) == "circular") return lines.flatMap { lineInfo(it).stops }.take(80)

        val needle = normalize(q)
        return allStops()
            .asSequence()
            .filter { normalize(it.name).contains(needle) || it.code.contains(q, ignoreCase = true) }
            .distinctBy { "${it.type}-${it.line}-${it.id}-${it.direction}" }
            .take(80)
            .toList()
    }

    fun lineInfo(lineRaw: String): LineInfo {
        val line = canonicalLine(lineRaw)
        return lineCache.getOrPut(line.lowercase(Locale.ROOT)) {
            if (line.equals("tranvia", true) || line.equals("tranvía", true) || line.equals("Tranvía", true) || line == "1") {
                LineInfo("Tranvía", TransportType.TRAM, tramStops())
            } else {
                val json = getJson("$base/linea-autobus/${encode(line)}")
                val features = json.optJSONArray("features") ?: return@getOrPut LineInfo(line, TransportType.BUS, emptyList())
                val runs = mutableListOf<MutableList<Pair<Int, Stop>>>()
                var currentRun = mutableListOf<Pair<Int, Stop>>()
                for (i in 0 until features.length()) {
                    val feature = features.getJSONObject(i)
                    if (feature.optJSONObject("geometry")?.optString("type") != "Point") {
                        if (currentRun.isNotEmpty()) {
                            runs += currentRun
                            currentRun = mutableListOf()
                        }
                        continue
                    }
                    val props = feature.optJSONObject("properties") ?: continue
                    val id = props.optString("description").replace("Poste", "").trim()
                    val title = props.optString("title").cleanupStopName()
                    if (id.isNotBlank() && title.isNotBlank()) currentRun += i to Stop(id, title, line, TransportType.BUS, i)
                }
                if (currentRun.isNotEmpty()) runs += currentRun

                val stops = runs.flatMapIndexed { direction, run ->
                    val destination = run.lastOrNull()?.second?.name.orEmpty()
                    run.mapIndexed { order, (_, stop) -> stop.copy(order = order, direction = direction, destination = destination) }
                }
                LineInfo(line, TransportType.BUS, stops)
            }
        }
    }

    fun arrivals(stop: Stop): List<Arrival> {
        return when (stop.type) {
            TransportType.BUS -> busArrivals(stop.id).filter { it.line.equals(stop.line, true) }.ifEmpty { busArrivals(stop.id) }
            TransportType.TRAM -> tramArrivals(stop)
        }
    }

    fun lineSchedule(lineRaw: String, directionIndex: Int): LineSchedule {
        val line = canonicalLine(lineRaw)
        val date = LocalDate.now().toString()
        return cachedSchedule("$date-$line-$directionIndex") {
            if (line.equals("Tranvía", true)) return@cachedSchedule tramLineSchedule(directionIndex)
            val directions = avanzaDirections(line)
            val (directionValue, directionLabel) = directions.getOrNull(directionIndex) ?: return@cachedSchedule LineSchedule()
            val fullRoute = directionLabel.fullRoute()
            val body = "selectLinea=${encode(line)}&selectSentido=${encode(directionValue)}&times-date=${encode(date)}&times-date-submit=${encode("Cambiar")}"
            val html = request(
                "https://zaragoza.avanzagrupo.com/lineas-y-horarios/?selectLinea=${encode(line)}&selectSentido=${encode(directionValue)}",
                method = "POST",
                body = body,
                accept = "text/html",
                referer = "https://zaragoza.avanzagrupo.com/lineas-y-horarios/",
            )
            val firstRows = parseScheduleRows(html, "Primeras salidas")
            val lastRows = parseScheduleRows(html, "Últimas salidas")
            val first = firstRows.firstFullRoute(fullRoute)
            val last = lastRows.lastFullRoute(fullRoute)
            LineSchedule(
                first = first,
                last = last,
                firstWasFiltered = first != null && firstRows.firstOrNull() != first,
                lastWasFiltered = last != null && lastRows.lastOrNull() != last,
                firstPartials = firstRows.before(first).filterNotFullRoute(fullRoute).take(3),
                lastPartials = lastRows.after(last).filterNotFullRoute(fullRoute).take(4),
            )
        }
    }

    private fun allStops(): List<Stop> {
        allStopsCache?.let { return it }
        return synchronized(allStopsLock) {
            allStopsCache ?: (listOf("Tranvía") + busLines())
                .flatMap { line -> runCatching { lineInfo(line).stops }.getOrDefault(emptyList()) }
                .distinctBy { "${it.type}-${it.line}-${it.id}-${it.direction}" }
                .also { allStopsCache = it }
        }
    }

    private fun cachedBusLines(): List<String> = busLinesCache ?: fallbackBusLines

    private fun busLines(): List<String> {
        busLinesCache?.let { return it }
        val loaded = runCatching {
            val html = request("https://zaragoza.avanzagrupo.com/lineas-y-horarios/", accept = "text/html")
            Regex("<option[^>]*value=[\\\"']([^\\\"']+)[\\\"'][^>]*>.*?</option>", RegexOption.DOT_MATCHES_ALL)
                .findAll(html)
                .map { it.groupValues[1].trim() }
                .filter { visibleBusLinePattern.matches(it) }
                .filterNot { unusableVisibleBusLines.contains(it.uppercase(Locale.ROOT)) }
                .distinctBy { it.uppercase(Locale.ROOT) }
                .toList()
        }.getOrDefault(fallbackBusLines)
        return loaded.ifEmpty { fallbackBusLines }.also { busLinesCache = it }
    }

    private fun cachedSchedule(key: String, loader: () -> LineSchedule): LineSchedule {
        val now = System.currentTimeMillis()
        scheduleCache[key]?.takeIf { now - it.savedAt < scheduleTtlMillis }?.let { return it.value }
        return loader().also { scheduleCache[key] = CachedSchedule(it, now) }
    }

    private fun matchingLines(query: String): List<String> {
        val q = query.trim()
        val needle = normalize(q)
        val lines = cachedBusLines()
        if (needle == "circular") return lines.filter { it.startsWith("Ci", ignoreCase = true) }
        if (q.equals("tranvia", true) || q.equals("tranvía", true) || q == "1") return listOf("Tranvía")
        return (listOf("Tranvía") + lines).filter { normalize(it).startsWith(needle) || normalize(lineLabel(it)).contains(needle) }
    }

    private fun isExactLineQuery(query: String): Boolean {
        val q = query.trim()
        return q.equals("tranvia", true) || q.equals("tranvía", true) || q == "1" || cachedBusLines().any { it.equals(q, ignoreCase = true) }
    }

    private fun canonicalLine(lineRaw: String): String {
        val line = lineRaw.trim()
        return when {
            line.equals("tranvia", true) || line.equals("tranvía", true) || line == "1" -> "Tranvía"
            else -> cachedBusLines().firstOrNull { it.equals(line, ignoreCase = true) } ?: line.uppercase(Locale.ROOT)
        }
    }

    private fun lineLabel(line: String): String = when {
        line.equals("Tranvía", true) -> "Tranvía"
        line.startsWith("Ci", ignoreCase = true) -> "Circular $line"
        else -> "Línea $line"
    }

    private fun avanzaDirections(line: String): List<Pair<String, String>> {
        val html = request(
            avanza,
            method = "POST",
            body = "action=get_direction_list&selectLinea=${encode(line)}",
            accept = "text/html",
            referer = "https://zaragoza.avanzagrupo.com/lineas-y-horarios/",
        )
        return Regex("<option[^>]*value=\"([^\"]+)\"[^>]*>(.*?)</option>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .map { it.groupValues[1] to it.groupValues[2].cleanHtml() }
            .filter { (value, _) -> value != "directionDefault" && value.isNotBlank() }
            .toList()
    }

    private fun parseScheduleRows(html: String, title: String): List<LineDeparture> {
        val table = Regex("<h3[^>]*>\\s*${Regex.escape(title)}\\s*</h3>(.*?</table>)", RegexOption.DOT_MATCHES_ALL)
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: return emptyList()
        return Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
            .findAll(table)
            .mapNotNull { rowMatch ->
                val cells = Regex("<td[^>]*>(.*?)</(?:td|th)>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(rowMatch.groupValues[1])
                    .map { it.groupValues[1].cleanHtml() }
                    .toList()
                if (cells.size >= 3) LineDeparture(cells[0], cells[1], cells[2]) else null
            }
            .toList()
    }

    private data class FullRoute(val from: String, val to: String)

    private fun String.fullRoute(): FullRoute? {
        val parts = split(Regex("\\s+-\\s+"), limit = 2)
        if (parts.size != 2) return null
        return FullRoute(parts[0].routeKey(), parts[1].routeKey())
    }

    private fun List<LineDeparture>.firstFullRoute(route: FullRoute?): LineDeparture? = route?.let { fullRoute ->
        firstOrNull { departure -> departure.matchesFullRoute(fullRoute) }
    }

    private fun List<LineDeparture>.lastFullRoute(route: FullRoute?): LineDeparture? = route?.let { fullRoute ->
        lastOrNull { departure -> departure.matchesFullRoute(fullRoute) }
    }

    private fun List<LineDeparture>.before(item: LineDeparture?): List<LineDeparture> {
        val index = item?.let { indexOf(it) } ?: size
        return take(index.coerceAtLeast(0))
    }

    private fun List<LineDeparture>.after(item: LineDeparture?): List<LineDeparture> {
        val index = item?.let { indexOf(it) } ?: -1
        return drop(index + 1)
    }

    private fun List<LineDeparture>.filterNotFullRoute(route: FullRoute?): List<LineDeparture> {
        return route?.let { fullRoute -> filterNot { it.matchesFullRoute(fullRoute) } } ?: emptyList()
    }

    private fun LineDeparture.matchesFullRoute(route: FullRoute): Boolean {
        return from.routeKey() == route.from && to.routeKey() == route.to
    }

    private fun String.routeKey(): String = cleanHtml()
        .uppercase(Locale.ROOT)
        .replace("Á", "A").replace("É", "E").replace("Í", "I").replace("Ó", "O").replace("Ú", "U").replace("Ü", "U")
        .replace("º", "").replace("ª", "")
        .replace(Regex("[^A-Z0-9]+"), "")

    private fun tramLineSchedule(directionIndex: Int): LineSchedule {
        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        val stops = lineInfo("Tranvía")
            .stops
            .filter { it.direction == directionIndex }
            .sortedBy { it.order }
        val source = stops.firstOrNull() ?: return LineSchedule()
        val destination = stops.lastOrNull() ?: return LineSchedule()
        val nonce = tramNonce()
        val firstCandidates = tramScheduleTimes(nonce, source.id, destination.id, date, "05:00")
            .ifEmpty { tramScheduleTimes(nonce, source.id, destination.id, date, "06:00") }
        val first = firstCandidates
            .filter { it.time.serviceDayMinutes() >= 180 }
            .minByOrNull { it.time.serviceDayMinutes() }
            ?: firstCandidates.minByOrNull { it.time.clockMinutes() }
        val lastCandidates = tramScheduleTimes(nonce, source.id, destination.id, date, "02:00")
            .ifEmpty { tramScheduleTimes(nonce, source.id, destination.id, date, "23:50") }
            .ifEmpty { tramScheduleTimes(nonce, source.id, destination.id, date, "23:00") }
        val last = lastCandidates.maxByOrNull { it.time.serviceDayMinutes() }
        return LineSchedule(first = first, last = last)
    }

    private fun tramScheduleTimes(nonce: String, sourceId: String, destinationId: String, date: String, hour: String): List<LineDeparture> {
        val body = "_ajax_nonce=${encode(nonce)}&action=dosnet_tranvias_busqueda_por_fecha&id_source=${encode(sourceId)}&id_destination=${encode(destinationId)}&fecha=${encode(date)}&hours1=${encode(hour)}"
        val text = request(tranviaAjax, method = "POST", body = body, accept = "application/json", referer = "https://www.tranviasdezaragoza.es/nuestra-linea/")
        val json = JSONObject(text)
        val sourceName = json.optString("source_name")
        val destinationName = json.optString("destination_name")
        val times = json.optJSONArray("times") ?: return emptyList()
        return buildList {
            val seen = mutableSetOf<String>()
            for (i in 0 until times.length()) {
                val item = times.getJSONObject(i)
                val sourceTime = item.optString("source")
                val destinationTime = item.optString("destination")
                if (sourceTime.isNotBlank() && seen.add("$sourceTime-$destinationTime")) {
                    add(LineDeparture(sourceTime, sourceName, destinationName))
                }
            }
        }
    }

    private fun String.clockMinutes(): Int {
        val parts = split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return 0
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return hour * 60 + minute
    }

    private fun String.serviceDayMinutes(): Int {
        val minutes = clockMinutes()
        return if (minutes < 180) minutes + 24 * 60 else minutes
    }

    private fun busArrivals(stopId: String): List<Arrival> {
        val official = runCatching {
            val json = getJson("$base/poste-autobus/tuzsa-${encode(stopId)}")
            val features = json.optJSONArray("features") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until features.length()) {
                    val destinos = features.getJSONObject(i).optJSONObject("properties")?.optJSONArray("destinos") ?: continue
                    for (j in 0 until destinos.length()) {
                        val d = destinos.getJSONObject(j)
                        add(Arrival(d.optString("linea"), d.optString("destino").title(), listOfNotNull(d.optString("primero").minutes(), d.optString("segundo").minutes()).take(2)))
                    }
                }
            }
        }.getOrDefault(emptyList())
        return official.ifEmpty { busArrivalsAvanza(stopId) }
    }

    private fun busArrivalsAvanza(stopId: String): List<Arrival> {
        val body = "action=tiempos_de_llegada&selectPoste=${encode(stopId)}"
        val html = request(avanza, method = "POST", body = body, accept = "text/html")
        val pattern = Pattern.compile("<div class=\"info-linea\">\\s*(.*?)\\s*</div>.*?<div class=\"info-sentido\">\\s*(.*?)\\s*</div>.*?<div class=\"info-tiempo[^>]*>\\s*(.*?)\\s*</div>", Pattern.DOTALL)
        val matcher = pattern.matcher(html)
        val grouped = linkedMapOf<Pair<String, String>, MutableList<Int>>()
        while (matcher.find()) {
            val line = matcher.group(1).orEmpty().stripHtml().trim()
            val dest = matcher.group(2).orEmpty().stripHtml().trim().title()
            val min = matcher.group(3).orEmpty().stripHtml().minutes()
            if (line.isNotBlank() && min != null) grouped.getOrPut(line to dest) { mutableListOf() }.add(min)
        }
        return grouped.map { (key, mins) -> Arrival(key.first, key.second, mins.sorted().take(2)) }
    }

    private fun tramStops(): List<Stop> {
        val nonce = tramNonce()
        val text = request(
            tranviaAjax,
            method = "POST",
            body = "_ajax_nonce=${encode(nonce)}&action=dosnet_tranvias_lineas",
            accept = "application/json",
            referer = "https://www.tranviasdezaragoza.es/nuestra-linea/",
        )
        val json = JSONObject(text)
        val stops0 = json.optJSONArray("stops_0") ?: JSONArray()
        val stops1 = json.optJSONArray("stops_1") ?: JSONArray()
        val result = mutableListOf<Stop>()
        result += parseTramStops(stops0, direction = 0, destination = "Parque Goya")
        result += parseTramStops(stops1, direction = 1, destination = "Mago de Oz")
        return result.sortedWith(compareBy<Stop> { it.direction }.thenBy { it.order })
    }

    private fun parseTramStops(array: JSONArray, direction: Int, destination: String): List<Stop> = buildList {
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val id = o.optString("id")
            val code = o.optString("name")
            val title = o.optString("displayName").ifBlank { code }
            if (id.isNotBlank() && title.isNotBlank()) {
                add(Stop(id = id, name = title.title(), line = "Tranvía", type = TransportType.TRAM, order = o.optInt("position", i), code = code, direction = direction, destination = destination))
            }
        }
    }

    private fun tramArrivals(stop: Stop): List<Arrival> {
        val nonce = tramNonce()
        val body = "_ajax_nonce=${encode(nonce)}&action=dosnet_tranvias_busqueda_en_tiempo_real&id_stop=${encode(stop.id)}&sentido=${stop.direction}&qr=0"
        val text = request(tranviaAjax, method = "POST", body = body, accept = "application/json", referer = "https://www.tranviasdezaragoza.es/nuestra-linea/")
        val json = JSONObject(text)
        if (json.optBoolean("noresult") || json.optInt("realtime_error") == 1 || json.optString("ocupacion_estado") == "error") return emptyList()
        val result = json.optJSONArray("result") ?: return emptyList()
        return buildList {
            for (i in 0 until result.length()) {
                val item = result.getJSONObject(i)
                val minutes = item.optString("minutos").minutes() ?: item.optInt("minutos", -1).takeIf { it >= 0 }
                val destination = item.optString("destino").ifBlank { json.optString("sense") }.title()
                if (minutes != null) add(Arrival("Tranvía", destination, listOf(minutes)))
            }
        }.take(2)
    }

    private fun tramNonce(): String {
        val text = request(tranviaAjax, method = "POST", body = "action=dosnet_tranvias_get_nonce", accept = "application/json", referer = "https://www.tranviasdezaragoza.es/nuestra-linea/")
        val json = JSONObject(text)
        return json.optString("data").ifBlank { error("No se pudo obtener nonce del tranvía") }
    }

    private fun getJson(url: String): JSONObject {
        val text = request(url, accept = "application/geo+json")
        if (text.isBlank() || text.startsWith("error:")) error("Respuesta vacía del servicio")
        return JSONObject(text)
    }

    private fun request(url: String, method: String = "GET", body: String? = null, accept: String, referer: String? = null): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Mozilla/5.0 BusZaragoza/1.0 Android")
            referer?.let { setRequestProperty("Referer", it) }
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        body?.let { conn.outputStream.use { os -> os.write(it.toByteArray()) } }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use { s -> BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).readText() }.orEmpty()
        if (code !in 200..299) error("HTTP $code")
        return text
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun normalize(s: String): String = s.lowercase(Locale.ROOT)
        .replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u").replace("ü", "u")

    private fun String.cleanupStopName() = replace("Parada De ", "", true).replace("Parada ", "", true).trim().title()
    private fun String.title() = lowercase(Locale("es", "ES")).split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale("es", "ES")) } }
    private fun String.stripHtml() = replace(Regex("<.*?>"), "")
    private fun String.cleanHtml() = stripHtml()
        .replace("&#8211;", "–")
        .replace("&hyphen;", "-")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .trim()
        .replace(Regex("\\s+"), " ")
    private fun String.minutes(): Int? {
        val raw = lowercase(Locale.ROOT)
        if (raw.contains("próximo") || raw.contains("proximo") || raw.contains("inminente")) return 0
        return Regex("\\d+").find(raw)?.value?.toIntOrNull()
    }
}

package org.baumweg.buszaragoza

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.baumweg.buszaragoza.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { BusZaragozaApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusZaragozaApp() {
    val context = LocalContext.current
    val api = remember { ZaragozaApi() }
    val store = remember { FavoritesStore(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var state by remember { mutableStateOf(UiState(favorites = store.load())) }
    var favoriteDialogStop by remember { mutableStateOf<Stop?>(null) }
    var favoriteDescription by remember { mutableStateOf("") }
    var editingFavorite by remember { mutableStateOf<Favorite?>(null) }
    var editingFavoriteName by remember { mutableStateOf("") }

    fun show(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    fun saveFavorites(favorites: List<Favorite>) {
        store.save(favorites)
        state = state.copy(favorites = favorites)
    }
    fun search() {
        val q = state.query.trim()
        if (q.isBlank()) return
        if (api.isLineQuery(q)) {
            state = state.copy(screen = Screen.Line(q), loading = true, currentLine = null, results = emptyList(), suggestions = emptyList())
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { api.lineInfo(q) } }
                state = state.copy(loading = false, currentLine = result.getOrNull(), message = result.exceptionOrNull()?.message)
            }
            return
        }
        state = state.copy(loading = true, message = null, results = emptyList(), suggestions = emptyList(), currentLine = null)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.search(q) } }
            state = state.copy(loading = false, results = result.getOrDefault(emptyList()), message = result.exceptionOrNull()?.message)
            if (state.results.isEmpty() && result.isSuccess) show("No hay resultados")
        }
    }
    fun openLine(line: String) {
        state = state.copy(screen = Screen.Line(line), loading = true, currentLine = null, results = emptyList())
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.lineInfo(line) } }
            state = state.copy(loading = false, currentLine = result.getOrNull(), message = result.exceptionOrNull()?.message)
        }
    }
    fun refreshArrivals(stop: Stop) {
        state = state.copy(loading = true, arrivals = emptyList())
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.arrivals(stop) } }
            state = state.copy(loading = false, arrivals = result.getOrDefault(emptyList()), lastUpdatedMillis = System.currentTimeMillis(), message = result.exceptionOrNull()?.message)
        }
    }
    fun openStop(stop: Stop) {
        state = state.copy(screen = Screen.StopDetail(stop), arrivals = emptyList(), lastUpdatedMillis = null)
        refreshArrivals(stop)
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { api.warmCache() } }
        val q = state.query
        if (q.isNotBlank()) state = state.copy(suggestions = api.suggestions(q))
    }

    LaunchedEffect(state.screen) {
        val stop = (state.screen as? Screen.StopDetail)?.stop ?: return@LaunchedEffect
        while (true) {
            delay(30_000)
            refreshArrivals(stop)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(store.exportJson().toByteArray()) } }
            .onSuccess { show("Backup exportado") }
            .onFailure { show("No se pudo exportar") }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            store.importJson(text)
        }.onSuccess { count ->
            state = state.copy(favorites = store.load())
            show("Backup importado: $count favoritos")
        }.onFailure { show("Backup inválido") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(state.screen)) },
                navigationIcon = {
                    if (state.screen !is Screen.Home) IconButton(onClick = { state = state.copy(screen = Screen.Home) }) { Icon(Icons.Outlined.ArrowBack, "Volver") }
                },
                actions = {
                    IconButton(onClick = { state = state.copy(screen = Screen.Backup) }) { Icon(Icons.Outlined.Backup, "Backup") }
                    IconButton(onClick = { state = state.copy(screen = Screen.Favorites) }) { Icon(Icons.Outlined.Favorite, "Favoritos") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        when (val screen = state.screen) {
            Screen.Home -> HomeScreen(
                state = state,
                onQuery = { query -> state = state.copy(query = query, suggestions = api.suggestions(query)) },
                onSearch = { search() },
                onSuggestion = { suggestion ->
                    when (suggestion) {
                        is SearchSuggestion.Line -> openLine(suggestion.line)
                        is SearchSuggestion.StopResult -> openStop(suggestion.stop)
                    }
                },
                onLine = { openLine(it) },
                onStop = { openStop(it) },
                padding = padding,
            )
            Screen.Favorites -> FavoritesScreen(
                favorites = state.favorites,
                onOpen = { openStop(Stop(id = it.stopId, name = it.stopName, line = it.line, type = it.type, code = it.code, direction = it.direction, destination = it.destination)) },
                onEdit = {
                    editingFavorite = it
                    editingFavoriteName = favoriteTitle(it)
                },
                onDelete = { saveFavorites(state.favorites.filterNot { fav -> fav.id == it.id }) },
                padding = padding,
            )
            is Screen.Line -> LineScreen(state, api = api, onStop = { openStop(it) }, padding = padding)
            is Screen.StopDetail -> StopScreen(state, screen.stop, onRefresh = { refreshArrivals(screen.stop) }, onSave = {
                favoriteDescription = ""
                favoriteDialogStop = screen.stop
            }, padding = padding)
            Screen.Backup -> BackupScreen(onExport = { exportLauncher.launch("bus-zaragoza-favoritos.json") }, onImport = { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, padding = padding)
        }
    }

    favoriteDialogStop?.let { stop ->
        AlertDialog(
            onDismissRequest = { favoriteDialogStop = null },
            title = { Text("Guardar favorito") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${stop.line} · ${stop.name}")
                    OutlinedTextField(
                        value = favoriteDescription,
                        onValueChange = { favoriteDescription = it },
                        label = { Text("Descripción opcional") },
                        placeholder = { Text("Casa, trabajo, cole...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val fav = Favorite(
                        id = favoriteId(stop),
                        line = stop.line,
                        stopId = stop.id,
                        stopName = stop.name,
                        type = stop.type,
                        code = stop.code,
                        direction = stop.direction,
                        destination = stop.destination,
                        description = favoriteDescription.trim(),
                    )
                    val updated = state.favorites.filterNot { it.id == fav.id } + fav
                    saveFavorites(updated)
                    favoriteDialogStop = null
                    show("Favorito guardado")
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { favoriteDialogStop = null }) { Text("Cancelar") } },
        )
    }
    editingFavorite?.let { fav ->
        AlertDialog(
            onDismissRequest = { editingFavorite = null },
            title = { Text("Editar nombre") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${fav.line} · ${fav.stopName}")
                    OutlinedTextField(
                        value = editingFavoriteName,
                        onValueChange = { editingFavoriteName = it },
                        label = { Text("Nombre visible") },
                        placeholder = { Text(favoriteDefaultTitle(fav)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val updatedFavorite = fav.copy(description = editingFavoriteName.trim())
                    saveFavorites(state.favorites.map { if (it.id == fav.id) updatedFavorite else it })
                    editingFavorite = null
                    show("Favorito actualizado")
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { editingFavorite = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun HomeScreen(
    state: UiState,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onSuggestion: (SearchSuggestion) -> Unit,
    onLine: (String) -> Unit,
    onStop: (Stop) -> Unit,
    padding: PaddingValues,
) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Línea o parada") },
                placeholder = { Text("38, Ci1, Circular, Tranvía, Romareda...") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
            )
            if (state.suggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.suggestions.forEach { suggestion ->
                        SuggestionRow(suggestion = suggestion, onClick = { onSuggestion(suggestion) })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) { Text("Buscar") }
        }
        if (state.loading) item { LoadingCard("Consultando transporte urbano...") }
        if (state.results.isNotEmpty()) {
            item { Text("Resultados", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(state.results) { stop -> StopCard(stop, onClick = { onStop(stop) }, onLine = { onLine(stop.line) }) }
        } else if (!state.loading) {
            item { HelpCard() }
        }
    }
}

@Composable
private fun FavoritesScreen(favorites: List<Favorite>, onOpen: (Favorite) -> Unit, onEdit: (Favorite) -> Unit, onDelete: (Favorite) -> Unit, padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (favorites.isEmpty()) item { EmptyCard("Sin favoritos", "Busca una línea y guarda las paradas que uses a menudo.") }
        items(favorites) { fav ->
            ElevatedCard(onClick = { onOpen(fav) }, modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(favoriteTitle(fav)) },
                    supportingContent = {
                        Text(
                            if (fav.description.isBlank()) {
                                "Parada ${fav.code} · ${fav.stopName}${if (fav.destination.isNotBlank()) " · hacia ${fav.destination}" else ""}"
                            } else {
                                "${favoriteDefaultTitle(fav)} · Parada ${fav.code}${if (fav.destination.isNotBlank()) " · hacia ${fav.destination}" else ""}"
                            }
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Favorite, null) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { onEdit(fav) }) { Icon(Icons.Outlined.Edit, "Editar nombre") }
                            IconButton(onClick = { onDelete(fav) }) { Icon(Icons.Outlined.Delete, "Eliminar") }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun LineScreen(state: UiState, api: ZaragozaApi, onStop: (Stop) -> Unit, padding: PaddingValues) {
    val info = state.currentLine
    val directions = info?.stops?.groupBy { stop -> stop.direction }?.toSortedMap().orEmpty()
    var selectedDirection by remember(info?.line, info?.stops?.size) { mutableStateOf(directions.keys.firstOrNull() ?: 0) }
    val selectedStops = directions[selectedDirection].orEmpty().sortedBy { stop -> stop.order }
    val favorites = state.favorites
    val firstStop = selectedStops.firstOrNull()
    val lastStop = selectedStops.lastOrNull()
    var lineSchedule by remember(info?.line, selectedDirection) { mutableStateOf<LineSchedule?>(null) }
    var scheduleLoading by remember(info?.line, selectedDirection) { mutableStateOf(false) }

    LaunchedEffect(info?.line, selectedDirection) {
        val line = info?.line ?: return@LaunchedEffect
        scheduleLoading = true
        lineSchedule = withContext(Dispatchers.IO) { runCatching { api.lineSchedule(line, selectedDirection) }.getOrNull() }
        scheduleLoading = false
    }

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.loading) item { LoadingCard("Cargando paradas...") }
        info?.let {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Línea ${it.line}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (directions.size > 1) {
                            val directionKeys = directions.keys.toList()
                            val firstDirection = directionKeys.first()
                            val secondDirection = directionKeys.getOrElse(1) { firstDirection }
                            val checked = selectedDirection == secondDirection
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text("Sentido", fontWeight = FontWeight.SemiBold)
                                DirectionSwitch(
                                    checked = checked,
                                    onToggle = { selectedDirection = if (checked) firstDirection else secondDirection },
                                )
                            }
                        }
                        Text("Inicio: ${firstStop?.name ?: "—"}")
                        Text("Final: ${lastStop?.name ?: "—"}")
                        Text("Primera: ${scheduleTime(lineSchedule?.first, scheduleLoading)}")
                        Text("Última: ${scheduleTime(lineSchedule?.last, scheduleLoading)}")
                    }
                }
            }
            items(selectedStops) { stop ->
                StopCard(stop, onClick = { onStop(stop) }, onLine = null, isFavorite = favorites.any { favorite -> favoriteMatches(stop, favorite) })
            }
        }
    }
}

@Composable
private fun StopScreen(state: UiState, stop: Stop, onRefresh: () -> Unit, onSave: () -> Unit, padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stop.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${stop.line} · Parada ${stop.code}${if (stop.destination.isNotBlank()) " · hacia ${stop.destination}" else ""}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onSave) { Icon(Icons.Outlined.Save, null); Text(" Guardar") }
                        FilledTonalButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, null); Text(" Refrescar") }
                    }
                    state.lastUpdatedMillis?.let { Text("Actualizado ${time(it)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (state.loading) item { LoadingCard("Actualizando tiempos...") }
        if (!state.loading && state.arrivals.isEmpty()) item { EmptyCard("Sin estimación", state.message ?: "No hay próximos vehículos ahora mismo.") }
        items(state.arrivals) { arrival -> ArrivalCard(arrival) }
    }
}

@Composable
private fun BackupScreen(onExport: () -> Unit, onImport: () -> Unit, padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Copia local de favoritos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Exporta o restaura un JSON local. Sin nube, sin cuentas, sin inventos raros.")
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Exportar backup") }
        FilledTonalButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Importar backup") }
    }
}

@Composable
private fun DirectionSwitch(checked: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .width(52.dp)
            .height(32.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .padding(4.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(12.dp)),
        )
    }
}

@Composable
private fun SuggestionRow(suggestion: SearchSuggestion, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        when (suggestion) {
            is SearchSuggestion.Line -> ListItem(
                headlineContent = { Text(suggestion.label) },
                supportingContent = { Text("Abrir línea") },
                leadingContent = { Icon(Icons.Outlined.DirectionsBus, null) },
            )
            is SearchSuggestion.StopResult -> ListItem(
                headlineContent = { Text(suggestion.stop.name) },
                supportingContent = { Text("${suggestion.stop.line} · Parada ${suggestion.stop.code}${if (suggestion.stop.destination.isNotBlank()) " · hacia ${suggestion.stop.destination}" else ""}") },
                leadingContent = { Icon(Icons.Outlined.Search, null) },
            )
        }
    }
}

@Composable
private fun StopCard(stop: Stop, onClick: () -> Unit, onLine: (() -> Unit)?, isFavorite: Boolean = false) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stop.name) },
            supportingContent = { Text("${stop.line} · Parada ${stop.code}${if (stop.destination.isNotBlank()) " · hacia ${stop.destination}" else ""}") },
            leadingContent = {
                Icon(
                    if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.DirectionsBus,
                    null,
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = { onLine?.let { AssistChip(onClick = it, label = { Text("Ver línea") }) } }
        )
    }
}

@Composable
private fun ArrivalCard(arrival: Arrival) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${arrival.line} hacia ${arrival.destination}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val text = if (arrival.minutes.isEmpty()) "Sin estimación" else arrival.minutes.take(2).joinToString(" · ") { if (it == 0) "Ahora" else "$it min" }
            Text(text, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun LoadingCard(text: String) { Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(); Text(text) } } }
@Composable
private fun EmptyCard(title: String, subtitle: String) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle) } } }
@Composable
private fun HelpCard() = EmptyCard("Busca rápido", "Escribe una línea, Circular, Tranvía o una referencia. Ejemplos: 38, Ci1, Romareda, Plaza Aragón.")
private fun titleFor(screen: Screen) = when (screen) { Screen.Home -> "Bus Zaragoza"; Screen.Favorites -> "Favoritos"; is Screen.Line -> "Línea ${screen.line}"; is Screen.StopDetail -> screen.stop.line; Screen.Backup -> "Backup" }
private fun favoriteId(stop: Stop): String = "${stop.type}-${stop.line}-${stop.id}-${stop.direction}"
private fun favoriteDefaultTitle(favorite: Favorite): String = "${favorite.line} · ${favorite.stopName}"
private fun favoriteTitle(favorite: Favorite): String = favorite.description.ifBlank { favoriteDefaultTitle(favorite) }
private fun favoriteMatches(stop: Stop, favorite: Favorite): Boolean = favorite.id == favoriteId(stop)
private fun scheduleTime(departure: LineDeparture?, loading: Boolean): String = if (loading) "…" else departure?.time ?: "—"
private fun time(millis: Long): String = SimpleDateFormat("HH:mm:ss", Locale("es", "ES")).format(Date(millis))

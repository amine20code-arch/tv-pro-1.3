package com.streamtv.iptv

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val FAV_KEY = "__fav__"
const val RECENT_KEY = "__recent__"

fun HistoryEntity.toItem() = ChannelEntity(id = itemId, playlistId = playlistId, kind = kind, name = name, logo = logo, streamUrl = url, tvgId = tvgId)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(AppDb.get(app))
    private val dao = repo.dao
    val settings = AppSettings(app)
    private val prefs = app.getSharedPreferences("ui", 0)
    private val adult = Regex("(?i)adult|xxx|porn|18\\+|sex")

    /** One playback session shared by the preview window and the fullscreen player. */
    val session = PlaybackSession(app, settings, repo) { c, p, d -> saveProgress(c, p, d) }

    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val profile = MutableStateFlow<ProfileEntity?>(null)
    val profiles = dao.profiles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playlists = dao.playlists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val itemCount = dao.count().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val theme = MutableStateFlow(runCatching { AppTheme.valueOf(prefs.getString("theme", "MIDNIGHT")!!) }.getOrDefault(AppTheme.MIDNIGHT))

    // UI state kept here so it survives leaving / re-entering a screen (e.g. fullscreen and back)
    var section by mutableStateOf(Section.LIVE)
    val cats = mutableStateMapOf<String, String>()
    val selected = mutableStateMapOf<String, ChannelEntity>()
    val scrollPos = HashMap<String, Int>()
    var restoreFocus by mutableStateOf(false)

    val history: Flow<List<HistoryEntity>> = profile.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.history(p.id) }
    val favorites: Flow<List<ChannelEntity>> = profile.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.favorites(p.id) }

    init { viewModelScope.launch { if (dao.profileCount() == 0) dao.insertProfile(ProfileEntity(name = "Default")) } }

    fun historyOf(kind: String): Flow<List<HistoryEntity>> = profile.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.historyOf(p.id, kind) }
    fun favoritesOf(kind: String): Flow<List<ChannelEntity>> = profile.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.favoritesOf(p.id, kind) }

    fun setTheme(t: AppTheme) { theme.value = t; prefs.edit().putString("theme", t.name).apply() }
    fun select(p: ProfileEntity?) { session.stop(); profile.value = p }
    fun addProfile(name: String, pin: String, kids: Boolean) = viewModelScope.launch { dao.insertProfile(ProfileEntity(name = name.ifBlank { "Profile" }, pin = pin, isKids = kids)) }
    fun deleteProfile(p: ProfileEntity) = viewModelScope.launch { if (profiles.value.size > 1) dao.deleteProfile(p.id) }

    /** Parental control: kids profiles (and the global option) never see adult categories. */
    fun groups(kind: String): Flow<List<GroupCount>> = combine(dao.groups(kind, "cat_$kind"), profile) { g, p ->
        if (p?.isKids == true || settings.hideAdult.on) g.filterNot { adult.containsMatchIn(it.groupTitle) } else g
    }
    fun channels(kind: String, group: String) = dao.byGroup(kind, group, 5000)
    suspend fun featured(kind: String) = dao.featured(kind, 8)
    suspend fun search(q: String) = dao.search(q, 80)
    suspend fun episodes(i: ChannelEntity) = repo.episodes(i)
    suspend fun trailer(i: ChannelEntity) = repo.trailer(i)

    fun toggleFav(i: ChannelEntity) = viewModelScope.launch {
        val p = profile.value ?: return@launch
        if (dao.isFav(p.id, i.id) > 0) dao.removeFav(p.id, i.id) else dao.addFav(FavEntity(p.id, i.id))
    }

    fun saveProgress(i: ChannelEntity, pos: Long, dur: Long) = viewModelScope.launch {
        val p = profile.value ?: return@launch
        dao.upsertHistory(HistoryEntity(p.id, i.id, i.playlistId, i.kind, i.name, i.logo, i.streamUrl, i.tvgId, pos, dur, System.currentTimeMillis()))
    }

    fun clearHistory() = viewModelScope.launch { profile.value?.let { dao.clearHistory(it.id) } }
    fun clearFavorites() = viewModelScope.launch { profile.value?.let { dao.clearFavs(it.id) } }

    // ---- playback entry points
    fun playFull(list: List<ChannelEntity>, idx: Int, resume: Long) {
        session.play(list, idx, if (settings.resume.on) resume else 0L, full = true, preview = false)
    }

    fun exitFullscreen() {
        if (session.previewMode) { session.saveNow(); session.fullscreen = false; restoreFocus = true }
        else session.stop()
    }

    // ---- sources
    private fun runImport(block: suspend () -> Result<Int>) = viewModelScope.launch {
        busy.value = true; status.value = "Importation en cours... (les grandes listes peuvent prendre une minute)"
        block().onSuccess { status.value = "Importation terminée : $it éléments" }
            .onFailure { status.value = "Échec : ${it.message ?: "serveur injoignable"}. Les données déjà enregistrées restent disponibles." }
        busy.value = false
    }
    private val progress: (String) -> Unit = { status.value = it }
    fun addM3u(n: String, url: String, epg: String) = runImport { repo.addM3u(n.ifBlank { "Playlist" }, url, epg.trim(), settings.epgHours.int) }
    fun addXtream(n: String, s: String, u: String, p: String) = runImport { repo.addXtream(n.ifBlank { "Xtream" }, s, u, p, settings.epgHours.int, progress) }
    fun addStalker(n: String, portal: String, mac: String) = runImport { repo.addStalker(n.ifBlank { "Stalker" }, portal, mac, progress) }

    fun importM3uUri(uri: Uri) = runImport {
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Fichier M3U"
        val input = getApplication<Application>().contentResolver.openInputStream(uri)
        if (input == null) Result.failure(IllegalStateException("Fichier illisible")) else repo.addM3uStream(name, input)
    }

    fun importM3uPath(path: String, name: String) = runImport {
        repo.addM3uStream(name.ifBlank { "Fichier M3U" }, java.io.File(path).inputStream())
    }

    fun removePlaylist(p: PlaylistEntity) = viewModelScope.launch { repo.removePlaylist(p) }

    fun refreshEpg(p: PlaylistEntity) = viewModelScope.launch {
        busy.value = true; status.value = "Mise à jour du guide pour ${p.name}..."
        repo.refreshEpg(p, settings.epgHours.int.coerceAtLeast(6))
            .onSuccess { status.value = "Guide mis à jour ($it programmes)" }
            .onFailure { status.value = "Guide indisponible : ${it.message}" }
        busy.value = false
    }

    // ---- Stalker categories (films / séries) are filled on demand
    val loadingCat = mutableStateOf(false)
    val catHasMore = mutableStateMapOf<String, Boolean>()

    fun loadCategory(kind: String, group: String, force: Boolean = false) {
        if (loadingCat.value) return
        viewModelScope.launch {
            loadingCat.value = true
            repo.loadStalkerCategory(kind, group, 3, force)
                .onSuccess { catHasMore["$kind|$group"] = it }
                .onFailure { status.value = "Chargement impossible : ${it.message}" }
            loadingCat.value = false
        }
    }

    /** Plays a raw link right away (movie-like extensions get a seek bar, everything else is treated as live). */
    fun playUrl(url: String) {
        val u = url.trim()
        if (u.isEmpty()) return
        val isFile = Regex("(?i)\\.(mp4|mkv|avi|mov|webm|m4v)(\\?.*)?$").containsMatchIn(u)
        session.play(listOf(ChannelEntity(playlistId = 0, kind = if (isFile) "movie" else "live", name = "Lecture directe", streamUrl = u)), 0, full = true, preview = false)
    }

    override fun onCleared() { session.release(); super.onCleared() }
}

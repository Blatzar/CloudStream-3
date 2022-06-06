package com.lagradost.cloudstream3.ui.player

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.google.android.exoplayer2.util.MimeTypes
import com.hippo.unifile.UniFile
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.player.CustomDecoder.Companion.updateForcedEncoding
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper.Companion.toSubtitleMimeType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.ResultFragment
import com.lagradost.cloudstream3.ui.result.SyncViewModel
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.isTvSettings
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showDialog
import com.lagradost.cloudstream3.utils.SubtitleHelper.languages
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.hideSystemUI
import com.lagradost.cloudstream3.utils.UIHelper.popCurrentPage
import kotlinx.android.synthetic.main.dialog_online_subtitles.*
import kotlinx.android.synthetic.main.fragment_player.*
import kotlinx.android.synthetic.main.player_custom_layout.*
import kotlinx.android.synthetic.main.player_select_source_and_subs.*
import kotlinx.coroutines.Job

class GeneratorPlayer : FullScreenPlayer() {
    companion object {
        private var lastUsedGenerator: IGenerator? = null
        fun newInstance(generator: IGenerator, syncData: HashMap<String, String>? = null): Bundle {
            Log.i(TAG, "newInstance = $syncData")
            lastUsedGenerator = generator
            return Bundle().apply {
                if (syncData != null)
                    putSerializable("syncData", syncData)
            }
        }
    }

    private var titleRez = 3
    private var limitTitle = 0

    private lateinit var viewModel: PlayerGeneratorViewModel //by activityViewModels()
    private lateinit var sync: SyncViewModel
    private var currentLinks: Set<Pair<ExtractorLink?, ExtractorUri?>> = setOf()
    private var currentSubs: Set<SubtitleData> = setOf()

    private var currentSelectedLink: Pair<ExtractorLink?, ExtractorUri?>? = null
    private var currentSelectedSubtitles: SubtitleData? = null
    private var currentMeta: Any? = null
    private var nextMeta: Any? = null
    private var isActive: Boolean = false
    private var isNextEpisode: Boolean = false // this is used to reset the watch time

    private var preferredAutoSelectSubtitles: String? = null // null means do nothing, "" means none

    private fun startLoading() {
        player.release()
        currentSelectedSubtitles = null
        isActive = false
        overlay_loading_skip_button?.isVisible = false
        player_loading_overlay?.isVisible = true
    }

    private fun setSubtitles(sub: SubtitleData?): Boolean {
        currentSelectedSubtitles = sub
        return player.setPreferredSubtitles(sub)
    }

    override fun embeddedSubtitlesFetched(subtitles: List<SubtitleData>) {
        viewModel.addSubtitles(subtitles.toSet())
    }

    private fun noSubtitles(): Boolean {
        return setSubtitles(null)
    }

    private fun getPos(): Long {
        val durPos = DataStoreHelper.getViewPos(viewModel.getId()) ?: return 0L
        if (durPos.duration == 0L) return 0L
        if (durPos.position * 100L / durPos.duration > 95L) {
            return 0L
        }
        return durPos.position
    }

    var currentVerifyLink: Job? = null

    private fun loadExtractorJob(extractorLink: ExtractorLink?) {
        currentVerifyLink?.cancel()
        extractorLink?.let {
            currentVerifyLink = ioSafe {
                if (it.extractorData != null) {
                    getApiFromNameNull(it.source)?.extractorVerifierJob(it.extractorData)
                }
            }
        }
    }

    private fun loadLink(link: Pair<ExtractorLink?, ExtractorUri?>?, sameEpisode: Boolean) {
        if (link == null) return

        // manage UI
        player_loading_overlay?.isVisible = false
        uiReset()
        currentSelectedLink = link
        currentMeta = viewModel.getMeta()
        nextMeta = viewModel.getNextMeta()
        isActive = true
        setPlayerDimen(null)
        setTitle()

        loadExtractorJob(link.first)
        // load player
        context?.let { ctx ->
            val (url, uri) = link
            player.loadPlayer(
                ctx,
                sameEpisode,
                url,
                uri,
                startPosition = if (sameEpisode) null else {
                    if (isNextEpisode) 0L else getPos()
                },
                currentSubs,
                (if (sameEpisode) currentSelectedSubtitles else null) ?: getAutoSelectSubtitle(
                    currentSubs,
                    settings = true,
                    downloads = true
                ),
            )
        }
    }

    private fun sortLinks(useQualitySettings: Boolean = true): List<Pair<ExtractorLink?, ExtractorUri?>> {
        return currentLinks.sortedBy {
            val (linkData, _) = it
            var quality = linkData?.quality ?: Qualities.Unknown.value

            // we set all qualities above current max as reverse
            if (useQualitySettings && quality > currentPrefQuality) {
                quality = currentPrefQuality - quality - 1
            }
            // negative because we want to sort highest quality first
            -(quality)
        }
    }

    private fun openOnlineSubPicker(context: Context, query: String, imdbId: Long?) {
        val dialog = Dialog(context, R.style.AlertDialogCustomBlack)
        dialog.setContentView(R.layout.dialog_online_subtitles)

        val arrayAdapter =
            ArrayAdapter<String>(dialog.context, R.layout.sort_bottom_single_choice)

        dialog.show()

        dialog.cancel_btt.setOnClickListener {
            dialog.dismissSafe()
        }

        dialog.subtitle_adapter.choiceMode = AbsListView.CHOICE_MODE_SINGLE
        dialog.subtitle_adapter.adapter = arrayAdapter
        val adapter = dialog.subtitle_adapter.adapter as? ArrayAdapter<String>

        var currentSubtitles: List<SubtitleData> = emptyList()
        var currentSubtitle: SubtitleData? = null

        dialog.subtitle_adapter.setOnItemClickListener { parent, view, position, id ->
            currentSubtitle = currentSubtitles.getOrNull(position) ?: return@setOnItemClickListener
        }

        var currentLanguageTwoLetters: String? = null

        fun setSubtitles(list: List<SubtitleData>) {
            currentSubtitles = list
            adapter?.clear()
            adapter?.addAll(currentSubtitles.map { it.name })
        }

        dialog.subtitles_search.setOnQueryTextListener(object :
            androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                dialog.search_loading_bar?.show()
                val subs =
                    listOf(
                        // Testing
                        SubtitleData(query.toString(), "URL", SubtitleOrigin.OPEN_SUBTITLES, ""),
                        SubtitleData(query.toString(), "URL", SubtitleOrigin.OPEN_SUBTITLES, ""),
                        SubtitleData(query.toString(), "URL", SubtitleOrigin.OPEN_SUBTITLES, ""),
                        SubtitleData(query.toString(), "URL", SubtitleOrigin.OPEN_SUBTITLES, ""),
                    )
                setSubtitles(subs)
                dialog.search_loading_bar?.hide()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                return true
            }
        })

        dialog.search_filter.setOnClickListener {
            val names = languages.map {
                val emoji = SubtitleHelper.getFlagFromIso(it.ISO_639_1)
                val name = it.languageName //SubtitleHelper.fromTwoLettersToLanguage(it)
                val fullName = "${emoji?.let { "$it " } ?: ""}$name"
                Pair(it, fullName)
            }

            activity?.let { act ->
                act.showDialog(
                    names.map { it.second },
                    -1,
                    "Subtitle Language",
                    true,
                    {}) { index ->
                    currentLanguageTwoLetters = languages[index].ISO_639_1
                }
            }
        }

        dialog.apply_btt.setOnClickListener {
            currentSubtitle?.let { currentSubtitle ->
                viewModel.addSubtitles(setOf(currentSubtitle))
            }
            dialog.dismissSafe()
        }

        dialog.show()
        dialog.subtitles_search.setQuery(query, true)
    }

    private fun openSubPicker() {
        try {
            subsPathPicker.launch(
                arrayOf(
                    "text/plain",
                    "text/str",
                    "application/octet-stream",
                    MimeTypes.TEXT_UNKNOWN,
                    MimeTypes.TEXT_VTT,
                    MimeTypes.TEXT_SSA,
                    MimeTypes.APPLICATION_TTML,
                    MimeTypes.APPLICATION_MP4VTT,
                    MimeTypes.APPLICATION_SUBRIP,
                )
            )
        } catch (e: Exception) {
            logError(e)
        }
    }

    // Open file picker
    private val subsPathPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            normalSafeApiCall {
                // It lies, it can be null if file manager quits.
                if (uri == null) return@normalSafeApiCall
                val ctx = context ?: AcraApplication.context ?: return@normalSafeApiCall
                // RW perms for the path
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                ctx.contentResolver.takePersistableUriPermission(uri, flags)

                val file = UniFile.fromUri(ctx, uri)
                println("Loaded subtitle file. Selected URI path: $uri - Name: ${file.name}")
                // DO NOT REMOVE THE FILE EXTENSION FROM NAME, IT'S NEEDED FOR MIME TYPES
                val name = file.name ?: uri.toString()

                val subtitleData = SubtitleData(
                    name,
                    uri.toString(),
                    SubtitleOrigin.DOWNLOADED_FILE,
                    name.toSubtitleMimeType()
                )

                setSubtitles(subtitleData)

                // this is used instead of observe, because observe is too slow
                val subs = currentSubs.toMutableSet()
                subs.add(subtitleData)
                player.setActiveSubtitles(subs)
                player.reloadPlayer(ctx)

                viewModel.addSubtitles(setOf(subtitleData))

                selectSourceDialog?.dismissSafe()

                showToast(
                    activity,
                    String.format(ctx.getString(R.string.player_loaded_subtitles), name),
                    Toast.LENGTH_LONG
                )
            }
        }

    var selectSourceDialog: AlertDialog? = null
    override fun showMirrorsDialogue() {
        try {
            currentSelectedSubtitles = player.getCurrentPreferredSubtitle()
            context?.let { ctx ->
                val isPlaying = player.getIsPlaying()
                player.handleEvent(CSPlayerEvent.Pause)
                val currentSubtitles = sortSubs(currentSubs)

                val sourceBuilder = AlertDialog.Builder(ctx, R.style.AlertDialogCustomBlack)
                    .setView(R.layout.player_select_source_and_subs)

                val sourceDialog = sourceBuilder.create()
                selectSourceDialog = sourceDialog
                sourceDialog.show()
                val providerList = sourceDialog.sort_providers
                val subtitleList = sourceDialog.sort_subtitles

                val loadFromFileFooter: TextView =
                    layoutInflater.inflate(R.layout.sort_bottom_footer_add_choice, null) as TextView

                loadFromFileFooter.text = ctx.getString(R.string.player_load_subtitles)
                loadFromFileFooter.setOnClickListener {
                    openSubPicker()
                }
                subtitleList.addFooterView(loadFromFileFooter)
                val loadFromOpenSubsFooter: TextView =
                    layoutInflater.inflate(R.layout.sort_bottom_footer_add_choice, null) as TextView

                loadFromOpenSubsFooter.text =
                    "Load from Internet" //ctx.getString(R.string.player_load_subtitles)

                loadFromOpenSubsFooter.setOnClickListener {
                    println("VIDEO TITLE ${getPlayerVideoTitle()}")
                    openOnlineSubPicker(it.context, getPlayerVideoTitle(), null)
                }
                subtitleList.addFooterView(loadFromOpenSubsFooter)


                var sourceIndex = 0
                var startSource = 0

                val sortedUrls = sortLinks(useQualitySettings = false)
                if (sortedUrls.isEmpty()) {
                    sourceDialog.findViewById<LinearLayout>(R.id.sort_sources_holder)?.isGone = true
                } else {
                    startSource = sortedUrls.indexOf(currentSelectedLink)
                    sourceIndex = startSource

                    val sourcesArrayAdapter =
                        ArrayAdapter<String>(ctx, R.layout.sort_bottom_single_choice)

                    sourcesArrayAdapter.addAll(sortedUrls.map { (link, uri) ->
                        val name = link?.name ?: uri?.name ?: "NULL"
                        "$name ${Qualities.getStringByInt(link?.quality)}"
                    })

                    providerList.choiceMode = AbsListView.CHOICE_MODE_SINGLE
                    providerList.adapter = sourcesArrayAdapter
                    providerList.setSelection(sourceIndex)
                    providerList.setItemChecked(sourceIndex, true)

                    providerList.setOnItemClickListener { _, _, which, _ ->
                        sourceIndex = which
                        providerList.setItemChecked(which, true)
                    }
                }

                var shouldDismiss = true

                fun dismiss() {
                    if (isPlaying) {
                        player.handleEvent(CSPlayerEvent.Play)
                    }
                    activity?.hideSystemUI()
                }

                sourceDialog.setOnDismissListener {
                    if (shouldDismiss) dismiss()
                    selectSourceDialog = null
                }

                val subtitleIndexStart = currentSubtitles.indexOf(currentSelectedSubtitles) + 1
                var subtitleIndex = subtitleIndexStart

                val subsArrayAdapter =
                    ArrayAdapter<String>(ctx, R.layout.sort_bottom_single_choice)
                subsArrayAdapter.add(getString(R.string.no_subtitles))
                subsArrayAdapter.addAll(currentSubtitles.map { it.name })

                subtitleList.adapter = subsArrayAdapter
                subtitleList.choiceMode = AbsListView.CHOICE_MODE_SINGLE

                subtitleList.setSelection(subtitleIndex)
                subtitleList.setItemChecked(subtitleIndex, true)

                subtitleList.setOnItemClickListener { _, _, which, _ ->
                    subtitleIndex = which
                    subtitleList.setItemChecked(which, true)
                }

                sourceDialog.cancel_btt?.setOnClickListener {
                    sourceDialog.dismissSafe(activity)
                }

                sourceDialog.subtitles_encoding_format?.apply {
                    val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)

                    val prefNames = ctx.resources.getStringArray(R.array.subtitles_encoding_list)
                    val prefValues = ctx.resources.getStringArray(R.array.subtitles_encoding_values)

                    val value = settingsManager.getString(
                        ctx.getString(R.string.subtitles_encoding_key),
                        null
                    )
                    val index = prefValues.indexOf(value)
                    text = prefNames[if (index == -1) 0 else index]
                }

                sourceDialog.subtitles_click_settings?.setOnClickListener {
                    val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)

                    val prefNames = ctx.resources.getStringArray(R.array.subtitles_encoding_list)
                    val prefValues = ctx.resources.getStringArray(R.array.subtitles_encoding_values)

                    val currentPrefMedia =
                        settingsManager.getString(
                            getString(R.string.subtitles_encoding_key),
                            null
                        )

                    shouldDismiss = false
                    sourceDialog.dismissSafe(activity)

                    val index = prefValues.indexOf(currentPrefMedia)
                    activity?.showDialog(
                        prefNames.toList(),
                        if (index == -1) 0 else index,
                        ctx.getString(R.string.subtitles_encoding),
                        true,
                        {}) {
                        settingsManager.edit()
                            .putString(
                                ctx.getString(R.string.subtitles_encoding_key),
                                prefValues[it]
                            )
                            .apply()

                        updateForcedEncoding(ctx)
                        dismiss()
                        player.seekTime(-1) // to update subtitles, a dirty trick
                    }
                }

                sourceDialog.apply_btt?.setOnClickListener {
                    var init = false
                    if (sourceIndex != startSource) {
                        init = true
                    }
                    if (subtitleIndex != subtitleIndexStart) {
                        init = init || if (subtitleIndex <= 0) {
                            noSubtitles()
                        } else {
                            currentSubtitles.getOrNull(subtitleIndex - 1)?.let {
                                setSubtitles(it)
                            } ?: false
                        }
                    }
                    if (init) {
                        sortedUrls.getOrNull(sourceIndex)?.let {
                            loadLink(it, true)
                        }
                    }
                    sourceDialog.dismissSafe(activity)
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    private fun noLinksFound() {
        showToast(activity, R.string.no_links_found_toast, Toast.LENGTH_SHORT)
        activity?.popCurrentPage()
    }

    private fun startPlayer() {
        if (isActive) return // we don't want double load when you skip loading

        val links = sortLinks()
        if (links.isEmpty()) {
            noLinksFound()
            return
        }
        loadLink(links.first(), false)
    }

    override fun nextEpisode() {
        isNextEpisode = true
        player.release()
        viewModel.loadLinksNext()
    }

    override fun prevEpisode() {
        isNextEpisode = true
        player.release()
        viewModel.loadLinksPrev()
    }

    override fun nextMirror() {
        val links = sortLinks()
        if (links.isEmpty()) {
            noLinksFound()
            return
        }

        val newIndex = links.indexOf(currentSelectedLink) + 1
        if (newIndex >= links.size) {
            noLinksFound()
            return
        }

        loadLink(links[newIndex], true)
    }

    override fun onDestroy() {
        ResultFragment.updateUI()
        currentVerifyLink?.cancel()
        super.onDestroy()
    }

    var maxEpisodeSet: Int? = null

    override fun playerPositionChanged(posDur: Pair<Long, Long>) {
        val (position, duration) = posDur
        viewModel.getId()?.let {
            DataStoreHelper.setViewPos(it, position, duration)
        }
        val percentage = position * 100L / duration

        val nextEp = percentage >= NEXT_WATCH_EPISODE_PERCENTAGE
        val resumeMeta = if (nextEp) nextMeta else currentMeta
        if (resumeMeta == null && nextEp) {
            // remove last watched as it is the last episode and you have watched too much
            when (val newMeta = currentMeta) {
                is ResultEpisode -> {
                    DataStoreHelper.removeLastWatched(newMeta.parentId)
                }
                is ExtractorUri -> {
                    DataStoreHelper.removeLastWatched(newMeta.parentId)
                }
            }
        } else {
            // save resume
            when (resumeMeta) {
                is ResultEpisode -> {
                    DataStoreHelper.setLastWatched(
                        resumeMeta.parentId,
                        resumeMeta.id,
                        resumeMeta.episode,
                        resumeMeta.season,
                        isFromDownload = false
                    )
                }
                is ExtractorUri -> {
                    DataStoreHelper.setLastWatched(
                        resumeMeta.parentId,
                        resumeMeta.id,
                        resumeMeta.episode,
                        resumeMeta.season,
                        isFromDownload = true
                    )
                }
            }
        }

        var isOpVisible = false
        when (val meta = currentMeta) {
            is ResultEpisode -> {
                if (percentage >= UPDATE_SYNC_PROGRESS_PERCENTAGE && (maxEpisodeSet
                        ?: -1) < meta.episode
                ) {
                    context?.let { ctx ->
                        val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)
                        if (settingsManager.getBoolean(
                                ctx.getString(R.string.episode_sync_enabled_key),
                                true
                            )
                        )
                            maxEpisodeSet = meta.episode
                        sync.modifyMaxEpisode(meta.episode)
                    }
                }

                if (meta.tvType.isAnimeOp())
                    isOpVisible = percentage < SKIP_OP_VIDEO_PERCENTAGE
            }
        }
        player_skip_op?.isVisible = isOpVisible
        player_skip_episode?.isVisible = !isOpVisible && viewModel.hasNextEpisode() == true

        if (percentage >= PRELOAD_NEXT_EPISODE_PERCENTAGE) {
            viewModel.preLoadNextLinks()
        }
    }

    private fun getAutoSelectSubtitle(
        subtitles: Set<SubtitleData>,
        settings: Boolean,
        downloads: Boolean
    ): SubtitleData? {
        val langCode = preferredAutoSelectSubtitles ?: return null
        val lang = SubtitleHelper.fromTwoLettersToLanguage(langCode) ?: return null
        if (downloads) {
            return subtitles.firstOrNull { sub ->
                (sub.origin == SubtitleOrigin.DOWNLOADED_FILE && sub.name == context?.getString(
                    R.string.default_subtitles
                ))
            }
        }

        sortSubs(subtitles).firstOrNull { sub ->
            val t = sub.name.replace(Regex("[^A-Za-z]"), " ").trim()
            (settings || (downloads && sub.origin == SubtitleOrigin.DOWNLOADED_FILE)) && t == lang || t.startsWith(
                "$lang "
            ) || t == langCode
        }?.let { sub ->
            return sub
        }

        // post check in case both did not catch anything
        if (downloads) {
            return subtitles.firstOrNull { sub ->
                (sub.origin == SubtitleOrigin.DOWNLOADED_FILE || sub.name == context?.getString(
                    R.string.default_subtitles
                ))
            }
        }

        return null
    }

    private fun autoSelectFromSettings(): Boolean {
        // auto select subtitle based of settings
        val langCode = preferredAutoSelectSubtitles

        if (!langCode.isNullOrEmpty() && player.getCurrentPreferredSubtitle() == null) {
            getAutoSelectSubtitle(currentSubs, settings = true, downloads = false)?.let { sub ->
                context?.let { ctx ->
                    if (setSubtitles(sub)) {
                        player.reloadPlayer(ctx)
                        player.handleEvent(CSPlayerEvent.Play)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun autoSelectFromDownloads(): Boolean {
        if (player.getCurrentPreferredSubtitle() == null) {
            getAutoSelectSubtitle(currentSubs, settings = false, downloads = true)?.let { sub ->
                context?.let { ctx ->
                    if (setSubtitles(sub)) {
                        player.reloadPlayer(ctx)
                        player.handleEvent(CSPlayerEvent.Play)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun autoSelectSubtitles() {
        normalSafeApiCall {
            if (!autoSelectFromSettings()) {
                autoSelectFromDownloads()
            }
        }
    }

    private fun getPlayerVideoTitle(): String {
        var headerName: String? = null
        var subName: String? = null
        var episode: Int? = null
        var season: Int? = null
        var tvType: TvType? = null

        when (val meta = currentMeta) {
            is ResultEpisode -> {
                headerName = meta.headerName
                subName = meta.name
                episode = meta.episode
                season = meta.season
                tvType = meta.tvType
            }
            is ExtractorUri -> {
                headerName = meta.headerName
                subName = meta.name
                episode = meta.episode
                season = meta.season
                tvType = meta.tvType
            }
        }

        //Generate video title
        val playerVideoTitle = if (headerName != null) {
            (headerName +
                    if (tvType.isEpisodeBased() && episode != null)
                        if (season == null)
                            " - ${getString(R.string.episode)} $episode"
                        else
                            " \"${getString(R.string.season_short)}${season}:${getString(R.string.episode_short)}${episode}\""
                    else "") + if (subName.isNullOrBlank() || subName == headerName) "" else " - $subName"
        } else {
            ""
        }
        return playerVideoTitle
    }


    @SuppressLint("SetTextI18n")
    fun setTitle() {
        var playerVideoTitle = getPlayerVideoTitle()

            //Hide title, if set in setting
            if (limitTitle < 0) {
                player_video_title?.visibility = View.GONE
            } else {
                //Truncate video title if it exceeds limit
                val differenceInLength = playerVideoTitle.length - limitTitle
                val margin = 3 //If the difference is smaller than or equal to this value, ignore it
                if (limitTitle > 0 && differenceInLength > margin) {
                    playerVideoTitle = playerVideoTitle.substring(0, limitTitle - 1) + "..."
                }
            }

            player_episode_filler_holder?.isVisible = isFiller ?: false
            player_video_title?.text = playerVideoTitle
        }

        val isFiller: Boolean? = (currentMeta as? ResultEpisode)?.isFiller

        player_episode_filler_holder?.isVisible = isFiller ?: false
        player_video_title?.text = playerVideoTitle
    }

    @SuppressLint("SetTextI18n")
    fun setPlayerDimen(widthHeight: Pair<Int, Int>?) {
        val extra = if (widthHeight != null) {
            val (width, height) = widthHeight
            "${width}x${height}"
        } else {
            ""
        }

        val source = currentSelectedLink?.first?.name ?: currentSelectedLink?.second?.name
        ?: "NULL"

        player_video_title_rez?.text = when (titleRez) {
            0 -> ""
            1 -> extra
            2 -> source
            3 -> "$source - $extra"
            else -> ""
        }
    }

    override fun playerDimensionsLoaded(widthHeight: Pair<Int, Int>) {
        setPlayerDimen(widthHeight)
    }

    private fun unwrapBundle(savedInstanceState: Bundle?) {
        Log.i(TAG, "unwrapBundle = $savedInstanceState")
        savedInstanceState?.let { bundle ->
            sync.addSyncs(bundle.getSerializable("syncData") as? HashMap<String, String>?)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // this is used instead of layout-television to follow the settings and some TV devices are not classified as TV for some reason
        layout =
            if (context?.isTvSettings() == true) R.layout.fragment_player_tv else R.layout.fragment_player

        viewModel = ViewModelProvider(this)[PlayerGeneratorViewModel::class.java]
        sync = ViewModelProvider(this)[SyncViewModel::class.java]

        viewModel.attachGenerator(lastUsedGenerator)
        unwrapBundle(savedInstanceState)
        unwrapBundle(arguments)

        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        context?.let { ctx ->
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)
            titleRez = settingsManager.getInt(getString(R.string.prefer_limit_title_rez_key), 3)
            limitTitle = settingsManager.getInt(getString(R.string.prefer_limit_title_key), 0)
            updateForcedEncoding(ctx)
        }

        unwrapBundle(savedInstanceState)
        unwrapBundle(arguments)

        sync.updateUserData()

        preferredAutoSelectSubtitles = SubtitlesFragment.getAutoSelectLanguageISO639_1()

        if (currentSelectedLink == null) {
            viewModel.loadLinks()
        }

        overlay_loading_skip_button?.setOnClickListener {
            startPlayer()
        }

        player_loading_go_back?.setOnClickListener {
            player.release()
            activity?.popCurrentPage()
        }

        observe(viewModel.loadingLinks) {
            when (it) {
                is Resource.Loading -> {
                    startLoading()
                }
                is Resource.Success -> {
                    // provider returned false
                    //if (it.value != true) {
                    //    showToast(activity, R.string.unexpected_error, Toast.LENGTH_SHORT)
                    //}
                    startPlayer()
                }
                is Resource.Failure -> {
                    showToast(activity, it.errorString, Toast.LENGTH_LONG)
                    startPlayer()
                }
            }
        }

        observe(viewModel.currentLinks) {
            currentLinks = it
            val turnVisible = it.isNotEmpty()
            val wasGone = overlay_loading_skip_button?.isGone == true
            overlay_loading_skip_button?.isVisible = turnVisible
            if (turnVisible && wasGone) {
                overlay_loading_skip_button?.requestFocus()
            }
        }

        observe(viewModel.currentSubs) { set ->
            currentSubs = set
            player.setActiveSubtitles(set)

            autoSelectSubtitles()
        }
    }
}
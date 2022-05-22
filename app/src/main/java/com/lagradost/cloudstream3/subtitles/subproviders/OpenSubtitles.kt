package com.lagradost.cloudstream3.subtitles.subproviders

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.subtitles.AbstractSubProvider
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class OpenSubtitles: AbstractSubProvider() {
    override val name = "Opensubtitles"

    val host = "https://api.opensubtitles.com/api/v1"
    val apiKey = ""
    val TAG = "ApiError"

    data class OAuthToken (
        @JsonProperty("token") var token: String? = null,
        @JsonProperty("status") var status: Int? = null
    )
    data class Results(
        @JsonProperty("data") var data: List<ResultData>? = listOf()
    )
    data class ResultData(
        @JsonProperty("id") var id: String? = null,
        @JsonProperty("type") var type: String? = null,
        @JsonProperty("attributes") var attributes: ResultAttributes? = ResultAttributes()
    )
    data class ResultAttributes(
        @JsonProperty("subtitle_id") var subtitleId: String? = null,
        @JsonProperty("language") var language: String? = null,
        @JsonProperty("release") var release: String? = null,
        @JsonProperty("url") var url: String? = null,
        @JsonProperty("files") var files: List<ResultFiles>? = listOf()
    )
    data class ResultFiles(
        @JsonProperty("file_id") var fileId: Int? = null,
        @JsonProperty("cd_number") var cdNumber: Int? = null,
        @JsonProperty("file_name") var fileName: String? = null
    )

    /*
        Authorize app to connect to API, using username/password.
        Required to run at startup.
        Returns OAuth entity with valid access token.
     */
    override suspend fun authorize(ouath: SubtitleOAuthEntity): SubtitleOAuthEntity {
        val _ouath = SubtitleOAuthEntity(
            user = ouath.user,
            pass = ouath.pass,
            access_token = ouath.access_token
        )
        try {
            val data = app.post(
                url = "$host/login",
                headers = mapOf(
                    Pair("Api-Key", apiKey),
                    Pair("Content-Type", "application/json")
                ),
                data = mapOf(
                    Pair("username", _ouath.user),
                    Pair("password", _ouath.pass)
                )
            )
            if (data.code != 401) {
                Log.i(TAG, "Result => ${data.text}")
                tryParseJson<OAuthToken>(data.text)?.let {
                    _ouath.access_token = it.token ?: _ouath.access_token
                }
                Log.i(TAG, "OAuth => ${_ouath.toJson()}")
            }
        } catch (e: Exception) {
            logError(e)
        }
        return _ouath
    }

    /*
        Fetch subtitles using token authenticated on previous method (see authorize).
        Returns list of Subtitles which user can select to download (see load).
     */
    override suspend fun search(query: SubtitleSearch): List<SubtitleEntity> {
        val imdb_id = query.imdb ?: 0
        val search_query_url = when (imdb_id > 0) {
            //Use imdb_id to search if its valid
            true -> "$host/subtitles?imdb_id=$imdb_id&languages=${query.lang}"
            false -> "$host/subtitles?query=${query.query}&languages=${query.lang}"
        }
        try {
            val req = app.get(
                url =search_query_url,
                headers = mapOf(
                    Pair("Api-Key", apiKey),
                    Pair("Content-Type", "application/json")
                )
            )
            Log.i(TAG, "Search Req => ${req.text}")
            tryParseJson<Results>(req.text)?.let {
                it.data?.forEach { item ->
                    Log.i(TAG, "Result id/name => ${item.id} / ${item.attributes?.release}")
                    item.attributes?.files?.forEach { file ->
                        Log.i(TAG, "Result file => ${file.fileId} / ${file.fileName}")
                    }
                }
            }
        } catch (e: Exception) {
            logError(e)
            Log.i(TAG, "search^")
        }
        return listOf()
    }
    /*
        Process data returned from search.
        Returns string url for the subtitle file.
     */
    override suspend fun load(ouath: SubtitleOAuthEntity, data: SubtitleEntity): String {
        return ""
    }
}
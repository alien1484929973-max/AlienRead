package io.legado.app.help.config

import android.graphics.BlurMaskFilter
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import io.legado.app.constant.AppPattern
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.splitNotBlank
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap

object PrivacyBlurConfig {

    data class Rule(val cover: Int = 0, val title: Int = 0)

    private data class SourceGroups(val value: List<String>, val expiresAt: Long)

    private const val sourceCacheDuration = 30_000L
    private val sourceGroups = ConcurrentHashMap<String, SourceGroups>()

    @Volatile
    private var groupRules = loadGroupRules()

    val globalCoverRadius: Int
        get() {
            val prefs = appCtx.defaultSharedPreferences
            return if (prefs.contains(PreferKey.coverBlurRadius)) {
                appCtx.getPrefInt(PreferKey.coverBlurRadius).coerceIn(0, 25)
            } else if (appCtx.getPrefBoolean(PreferKey.blurBookCover)) {
                25
            } else {
                0
            }
        }

    val globalTitleRadius: Int
        get() = appCtx.getPrefInt(PreferKey.titleBlurRadius).coerceIn(0, 25)

    val configuredGroups: Set<String>
        get() = groupRules.keys

    fun coverRadius(sourceOrigin: String?): Int = resolve(sourceOrigin).cover

    fun titleRadius(sourceOrigin: String?): Int = resolve(sourceOrigin).title

    fun getGroupRule(group: String): Rule? = groupRules[group]

    fun setGroupRule(group: String, cover: Int, title: Int) {
        groupRules = groupRules.toMutableMap().apply {
            put(group, Rule(cover.coerceIn(0, 25), title.coerceIn(0, 25)))
        }
        saveGroupRules()
    }

    fun removeGroupRule(group: String) {
        groupRules = groupRules.toMutableMap().apply { remove(group) }
        saveGroupRules()
    }

    fun reload() {
        groupRules = loadGroupRules()
        sourceGroups.clear()
    }

    private fun resolve(sourceOrigin: String?): Rule {
        val defaultRule = Rule(globalCoverRadius, globalTitleRadius)
        if (sourceOrigin.isNullOrBlank() || groupRules.isEmpty()) return defaultRule
        val matchedRules = getSourceGroups(sourceOrigin).mapNotNull(groupRules::get)
        if (matchedRules.isEmpty()) return defaultRule
        return Rule(
            cover = matchedRules.maxOf { it.cover },
            title = matchedRules.maxOf { it.title }
        )
    }

    private fun getSourceGroups(sourceOrigin: String): List<String> {
        val now = SystemClock.elapsedRealtime()
        sourceGroups[sourceOrigin]?.takeIf { it.expiresAt > now }?.let { return it.value }
        val groups = appDb.bookSourceDao.getBookSourcePart(sourceOrigin)
            ?.bookSourceGroup
            ?.splitNotBlank(AppPattern.splitGroupRegex)
            ?.toList()
            .orEmpty()
        sourceGroups[sourceOrigin] = SourceGroups(groups, now + sourceCacheDuration)
        return groups
    }

    private fun loadGroupRules(): Map<String, Rule> {
        return GSON.fromJsonObject<Map<String, Rule>>(
            appCtx.getPrefString(PreferKey.sourceGroupBlurRules)
        ).getOrNull().orEmpty()
    }

    private fun saveGroupRules() {
        appCtx.putPrefString(PreferKey.sourceGroupBlurRules, GSON.toJson(groupRules))
        sourceGroups.clear()
    }
}

fun TextView.setBookTitle(name: CharSequence?, sourceOrigin: String?) {
    text = name
    val radius = PrivacyBlurConfig.titleRadius(sourceOrigin)
    paint.maskFilter = if (radius > 0) {
        BlurMaskFilter(radius.toFloat(), BlurMaskFilter.Blur.NORMAL)
    } else {
        null
    }
    setLayerType(if (radius > 0) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_NONE, null)
    invalidate()
}

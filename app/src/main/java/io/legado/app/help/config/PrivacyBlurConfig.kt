package io.legado.app.help.config

import android.content.res.ColorStateList
import android.graphics.BlurMaskFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.legado.app.R
import io.legado.app.constant.AppPattern
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.splitNotBlank
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap

object PrivacyBlurConfig {

    const val COVER_MAX_RADIUS = 50
    const val TEXT_MAX_RADIUS = 25

    data class Rule(val cover: Int = 0, val title: Int = 0)

    private data class SourceGroups(val value: List<String>, val expiresAt: Long)

    private const val sourceCacheDuration = 30_000L
    private val sourceGroups = ConcurrentHashMap<String, SourceGroups>()

    @Volatile
    private var groupRules = loadGroupRules()

    @Volatile
    private var temporarilyRevealed = false

    val globalCoverRadius: Int
        get() {
            val prefs = appCtx.defaultSharedPreferences
            return if (prefs.contains(PreferKey.coverBlurRadius)) {
                appCtx.getPrefInt(PreferKey.coverBlurRadius).coerceIn(0, COVER_MAX_RADIUS)
            } else if (appCtx.getPrefBoolean(PreferKey.blurBookCover)) {
                25
            } else {
                0
            }
        }

    val globalTitleRadius: Int
        get() = appCtx.getPrefInt(PreferKey.titleBlurRadius).coerceIn(0, TEXT_MAX_RADIUS)

    val configuredGroups: Set<String>
        get() = groupRules.keys

    val hasAnyBlur: Boolean
        get() = globalCoverRadius > 0 || globalTitleRadius > 0 ||
                groupRules.values.any { it.cover > 0 || it.title > 0 }

    fun coverRadius(sourceOrigin: String?): Int = resolve(sourceOrigin).cover

    fun titleRadius(sourceOrigin: String?): Int = resolve(sourceOrigin).title

    fun hasBlur(sourceOrigin: String?): Boolean = resolveConfigured(sourceOrigin).let {
        it.cover > 0 || it.title > 0
    }

    fun setTemporarilyRevealed(value: Boolean): Boolean {
        if (temporarilyRevealed == value) return false
        temporarilyRevealed = value
        return true
    }

    fun getGroupRule(group: String): Rule? = groupRules[group]

    fun setGroupRule(group: String, cover: Int, title: Int) {
        groupRules = groupRules.toMutableMap().apply {
            put(
                group,
                Rule(
                    cover.coerceIn(0, COVER_MAX_RADIUS),
                    title.coerceIn(0, TEXT_MAX_RADIUS)
                )
            )
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
        if (temporarilyRevealed) return Rule()
        return resolveConfigured(sourceOrigin)
    }

    private fun resolveConfigured(sourceOrigin: String?): Rule {
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

fun TextView.setBookText(value: CharSequence?, sourceOrigin: String?) {
    text = value
    val radius = PrivacyBlurConfig.titleRadius(sourceOrigin)
    applyPrivacyBlur(radius)
    val iconId = when (id) {
        R.id.tv_author -> R.id.iv_author
        R.id.tv_read -> R.id.iv_read
        R.id.tv_last -> R.id.iv_last
        else -> View.NO_ID
    }
    if (iconId != View.NO_ID) {
        (parent as? ViewGroup)?.findViewById<View>(iconId)?.applyPrivacyBlur(radius)
    }
}

fun TextView.applyBookTextBlur(sourceOrigin: String?) {
    applyPrivacyBlur(PrivacyBlurConfig.titleRadius(sourceOrigin))
}

private fun View.applyPrivacyBlur(radius: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setRenderEffect(
            if (radius > 0) {
                RenderEffect.createBlurEffect(
                    radius.toFloat(),
                    radius.toFloat(),
                    Shader.TileMode.CLAMP
                )
            } else {
                null
            }
        )
    } else if (this is TextView) {
        applyLegacyTextBlur(radius)
    }
}

private fun TextView.applyLegacyTextBlur(radius: Int) {
    paint.maskFilter = if (radius > 0) {
        BlurMaskFilter(radius.toFloat(), BlurMaskFilter.Blur.NORMAL)
    } else {
        null
    }
    setLayerType(if (radius > 0) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_NONE, null)
    invalidate()
}

fun View.applyBookInfoBlur(sourceOrigin: String?) {
    applyPrivacyBlurRecursively(PrivacyBlurConfig.titleRadius(sourceOrigin))
}

private fun View.applyPrivacyBlurRecursively(radius: Int) {
    when (this) {
        is ViewGroup -> for (index in 0 until childCount) {
            getChildAt(index).applyPrivacyBlurRecursively(radius)
        }

        is TextView,
        is ImageView -> applyPrivacyBlur(radius)
    }
}

fun View.holdToRevealPrivacy(positionKey: String, onChanged: () -> Unit) {
    (this as? FloatingActionButton)?.apply {
        setCustomSize(36.dpToPx())
        setMaxImageSize(16.dpToPx())
        backgroundTintList = ColorStateList.valueOf(context.accentColor)
        alpha = 0.72f
    }
    restorePrivacyPosition(positionKey)
    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        restorePrivacyPosition(positionKey)
    }
    var downRawX = 0f
    var downRawY = 0f
    var startX = 0f
    var startY = 0f
    var dragging = false
    val startDragging = Runnable { dragging = true }
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = view.x
                startY = view.y
                dragging = false
                view.postDelayed(startDragging, ViewConfiguration.getLongPressTimeout().toLong())
                if (PrivacyBlurConfig.setTemporarilyRevealed(true)) onChanged()
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    val parentView = view.parent as? ViewGroup
                    if (parentView != null) {
                        parentView.requestDisallowInterceptTouchEvent(true)
                        val maxX = (parentView.width - view.width).coerceAtLeast(0).toFloat()
                        val maxY = (parentView.height - view.height).coerceAtLeast(0).toFloat()
                        view.x = (startX + event.rawX - downRawX).coerceIn(0f, maxX)
                        view.y = (startY + event.rawY - downRawY).coerceIn(0f, maxY)
                    }
                }
                true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_OUTSIDE -> {
                view.removeCallbacks(startDragging)
                if (dragging) view.savePrivacyPosition(positionKey)
                if (PrivacyBlurConfig.setTemporarilyRevealed(false)) onChanged()
                if (event.actionMasked == MotionEvent.ACTION_UP && !dragging) view.performClick()
                dragging = false
                true
            }

            else -> true
        }
    }
}

private fun View.restorePrivacyPosition(positionKey: String) {
    post {
        val parentView = parent as? ViewGroup ?: return@post
        val maxX = (parentView.width - width).coerceAtLeast(0).toFloat()
        val maxY = (parentView.height - height).coerceAtLeast(0).toFloat()
        val prefs = appCtx.defaultSharedPreferences
        val savedX = prefs.getFloat("$positionKey.x", Float.NaN)
        val savedY = prefs.getFloat("$positionKey.y", Float.NaN)
        if (!savedX.isNaN()) x = savedX.coerceIn(0f, 1f) * maxX
        if (!savedY.isNaN()) y = savedY.coerceIn(0f, 1f) * maxY
    }
}

private fun View.savePrivacyPosition(positionKey: String) {
    val parentView = parent as? ViewGroup ?: return
    val maxX = (parentView.width - width).coerceAtLeast(0).toFloat()
    val maxY = (parentView.height - height).coerceAtLeast(0).toFloat()
    appCtx.defaultSharedPreferences.edit()
        .putFloat("$positionKey.x", if (maxX > 0f) x / maxX else 0f)
        .putFloat("$positionKey.y", if (maxY > 0f) y / maxY else 0f)
        .apply()
}

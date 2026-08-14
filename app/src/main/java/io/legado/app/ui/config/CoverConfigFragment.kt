package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.config.PrivacyBlurConfig
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.BookCover
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.inputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.FileOutputStream

class CoverConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val requestCodeCover = 111
    private val requestCodeCoverDark = 112
    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                requestCodeCover -> setCoverFromUri(PreferKey.defaultCover, uri)
                requestCodeCoverDark -> setCoverFromUri(PreferKey.defaultCoverDark, uri)
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        if (!requireContext().defaultSharedPreferences.contains(PreferKey.coverBlurRadius)
            && getPrefBoolean(PreferKey.blurBookCover)
        ) {
            putPrefInt(PreferKey.coverBlurRadius, 25)
        }
        addPreferencesFromResource(R.xml.pref_config_cover)
        upPreferenceSummary(PreferKey.defaultCover, getPrefString(PreferKey.defaultCover))
        upPreferenceSummary(PreferKey.defaultCoverDark, getPrefString(PreferKey.defaultCoverDark))
        findPreference<SwitchPreference>(PreferKey.coverShowAuthor)
            ?.isEnabled = getPrefBoolean(PreferKey.coverShowName)
        findPreference<SwitchPreference>(PreferKey.coverShowAuthorN)
            ?.isEnabled = getPrefBoolean(PreferKey.coverShowNameN)
        upGroupBlurSummary()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.cover_config)
        listView.setEdgeEffectColor(primaryColor)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        when (key) {
            PreferKey.defaultCover,
            PreferKey.defaultCoverDark -> {
                upPreferenceSummary(key, getPrefString(key))
            }

            PreferKey.coverShowName -> {
                findPreference<SwitchPreference>(PreferKey.coverShowAuthor)
                    ?.isEnabled = getPrefBoolean(key)
                BookCover.upDefaultCover()
            }

            PreferKey.coverShowNameN -> {
                findPreference<SwitchPreference>(PreferKey.coverShowAuthorN)
                    ?.isEnabled = getPrefBoolean(key)
                BookCover.upDefaultCover()
            }

            PreferKey.coverShowAuthor,
            PreferKey.coverShowAuthorN -> {
                BookCover.upDefaultCover()
            }

            PreferKey.coverBlurRadius,
            PreferKey.titleBlurRadius,
            PreferKey.sourceGroupBlurRules -> {
                postEvent(EventBus.BOOKSHELF_REFRESH, "")
                upGroupBlurSummary()
            }
        }
    }

    @SuppressLint("PrivateResource")
    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "coverRule" -> showDialogFragment(CoverRuleConfigDialog())
            PreferKey.sourceGroupBlurRules -> selectBlurGroup()
            PreferKey.defaultCover ->
                if (getPrefString(preference.key).isNullOrEmpty()) {
                    selectImage.launch {
                        requestCode = requestCodeCover
                        mode = HandleFileContract.IMAGE
                    }
                } else {
                    context?.selector(
                        items = arrayListOf(
                            getString(R.string.delete),
                            getString(R.string.select_image)
                        )
                    ) { _, i ->
                        if (i == 0) {
                            removePref(preference.key)
                            BookCover.upDefaultCover()
                        } else {
                            selectImage.launch {
                                requestCode = requestCodeCover
                                mode = HandleFileContract.IMAGE
                            }
                        }
                    }
                }

            PreferKey.defaultCoverDark ->
                if (getPrefString(preference.key).isNullOrEmpty()) {
                    selectImage.launch {
                        requestCode = requestCodeCoverDark
                        mode = HandleFileContract.IMAGE
                    }
                } else {
                    context?.selector(
                        items = arrayListOf(
                            getString(R.string.delete),
                            getString(R.string.select_image)
                        )
                    ) { _, i ->
                        if (i == 0) {
                            removePref(preference.key)
                            BookCover.upDefaultCover()
                        } else {
                            selectImage.launch {
                                requestCode = requestCodeCoverDark
                                mode = HandleFileContract.IMAGE
                            }
                        }
                    }
                }
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.defaultCover,
            PreferKey.defaultCoverDark -> preference.summary = if (value.isNullOrBlank()) {
                getString(R.string.select_image)
            } else {
                value
            }

            else -> preference.summary = value
        }
    }

    private fun selectBlurGroup() {
        val groups = (appDb.bookSourceDao.allGroups() + PrivacyBlurConfig.configuredGroups)
            .distinct()
        if (groups.isEmpty()) {
            toastOnUi(R.string.no_source_group)
            return
        }
        requireContext().selector(R.string.source_group_blur, groups) { _, group, _ ->
            showGroupBlurMenu(group)
        }
    }

    private fun showGroupBlurMenu(group: String) {
        val savedRule = PrivacyBlurConfig.getGroupRule(group)
        val rule = savedRule ?: PrivacyBlurConfig.Rule(
            PrivacyBlurConfig.globalCoverRadius,
            PrivacyBlurConfig.globalTitleRadius
        )
        val items = mutableListOf(
            getString(R.string.group_cover_blur_value, rule.cover),
            getString(R.string.group_title_blur_value, rule.title)
        )
        if (savedRule != null) items.add(getString(R.string.delete_group_blur_rule))
        requireContext().selector(group, items) { _, index ->
            when (index) {
                0 -> selectGroupBlurRadius(group, rule, true)
                1 -> selectGroupBlurRadius(group, rule, false)
                else -> {
                    PrivacyBlurConfig.removeGroupRule(group)
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    upGroupBlurSummary()
                }
            }
        }
    }

    private fun selectGroupBlurRadius(
        group: String,
        rule: PrivacyBlurConfig.Rule,
        cover: Boolean
    ) {
        NumberPickerDialog(requireContext())
            .setTitle(getString(if (cover) R.string.blur_book_cover else R.string.blur_book_title))
            .setMinValue(0)
            .setMaxValue(25)
            .setValue(if (cover) rule.cover else rule.title)
            .show { value ->
                PrivacyBlurConfig.setGroupRule(
                    group,
                    if (cover) value else rule.cover,
                    if (cover) rule.title else value
                )
                postEvent(EventBus.BOOKSHELF_REFRESH, "")
                upGroupBlurSummary()
            }
    }

    private fun upGroupBlurSummary() {
        findPreference<Preference>(PreferKey.sourceGroupBlurRules)?.summary =
            getString(R.string.source_group_blur_summary, PrivacyBlurConfig.configuredGroups.size)
    }

    private fun setCoverFromUri(preferenceKey: String, uri: Uri) {
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                FileOutputStream(file).use {
                    inputStream.copyTo(it)
                }
                putPrefString(preferenceKey, file.absolutePath)
                BookCover.upDefaultCover()
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

}

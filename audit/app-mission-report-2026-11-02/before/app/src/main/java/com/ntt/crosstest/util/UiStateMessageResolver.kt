package com.ntt.crosstest.util

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.Localizer
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Dynamically resolves persistent state strings according to the active in-app locale.
 * When language is switched, state messages seamlessly re-evaluate to the new language
 * without requiring operation re-execution or screen recreation.
 */
object UiStateMessageResolver {

    private data class FormatArg(val position: Int, val type: Char)
    private data class CompiledFormat(val regex: Regex, val args: List<FormatArg>)
    private class FormatEntry(
        val resId: Int,
        val viCompiled: CompiledFormat?,
        val enCompiled: CompiledFormat?,
        val maxPos: Int
    )

    private val exactMap = ConcurrentHashMap<String, Int>()
    private val formatEntries = CopyOnWriteArrayList<FormatEntry>()
    @Volatile private var isInitialized = false
    private val initLock = Any()

    private val monitoredResIds = intArrayOf(
        R.string.error_network,
        R.string.error_generic,
        R.string.error_report_context_conflict,
        R.string.error_report_requires_review,
        R.string.error_report_server_configuration,
        R.string.error_report_storage,
        R.string.error_account_locked,
        R.string.locked_insufficient_credits,
        R.string.error_same_screenshot,
        R.string.error_report_already_reviewed,
        R.string.error_report_expired,
        R.string.error_report_not_found,
        R.string.campaign_resubmit_new_photo_required,
        R.string.error_permission_denied,
        R.string.mission_err_not_found,
        R.string.mission_status_expired,
        R.string.mission_manage_not_paused,
        R.string.mission_status_stopped,
        R.string.mission_status_full,
        R.string.campaign_not_found,
        R.string.error_campaign_not_open,
        R.string.campaign_owner_cant_join,
        R.string.campaign_owner_cant_report,
        R.string.error_email_not_confirmed,
        R.string.error_multi_slot_not_configured,
        R.string.error_rpc_not_available,
        R.string.error_email_already_registered,
        R.string.error_invalid_credentials,
        R.string.error_password_too_short,
        R.string.error_rate_limited,
        R.string.campaign_login_required,
        R.string.app_update_publish_failed,
        R.string.app_update_apply_error,
        R.string.app_update_close_failed,
        R.string.app_update_close_error,
        R.string.campaign_update_claim_failed,
        R.string.campaign_update_claim_network_error,
        R.string.repo_connection_failed,
        R.string.warning_tester_msg,
        R.string.warning_owner_msg,
        R.string.owner_approve_warning_fallback,
        R.string.owner_reject_success,
        R.string.admin_removed_campaign_reason,
        R.string.oad_reject_reason_1,
        R.string.oad_reject_reason_2,
        R.string.oad_reject_reason_3,
        R.string.oad_reject_fallback_reason,
    )

    private fun compileTemplate(template: String): CompiledFormat? {
        if (!template.contains('%')) return null
        val specifierRegex = Regex("""%(\d+)\$([a-zA-Z])|%([a-zA-Z])""")
        val matches = specifierRegex.findAll(template).toList()
        if (matches.isEmpty()) return null

        val argList = mutableListOf<FormatArg>()
        val sb = StringBuilder("^")
        var lastIdx = 0
        var autoPos = 1

        for (m in matches) {
            val literal = template.substring(lastIdx, m.range.first)
            sb.append(Regex.escape(literal))
            lastIdx = m.range.last + 1

            val pos = m.groups[1]?.value?.toIntOrNull() ?: (autoPos++)
            val type = m.groups[2]?.value?.firstOrNull() ?: m.groups[3]?.value?.firstOrNull() ?: 's'
            argList.add(FormatArg(pos, type))

            when (type.lowercaseChar()) {
                'd', 'i' -> sb.append("""(-?\d+)""")
                'f', 'e', 'g' -> sb.append("""(-?\d+(?:\.\d+)?)""")
                else -> sb.append("""(.*?)""")
            }
        }
        val trailing = template.substring(lastIdx)
        sb.append(Regex.escape(trailing))
        sb.append("$")

        return CompiledFormat(Regex(sb.toString()), argList)
    }

    private fun getAppContext(): Context? {
        TestCheoApp.get()?.let { return it }
        try {
            val clazz = Class.forName("org.robolectric.RuntimeEnvironment")
            val method = clazz.getMethod("getApplication")
            val app = method.invoke(null) as? Application
            if (app != null) return app
        } catch (_: Throwable) {}
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val method = atClass.getMethod("currentApplication")
            val app = method.invoke(null) as? Application
            if (app != null) return app
        } catch (_: Throwable) {}
        return null
    }

    private fun ensureInitialized(context: Context) {
        if (isInitialized) return
        synchronized(initLock) {
            if (isInitialized) return
            try {
                // First index monitored IDs for fast certainty
                for (resId in monitoredResIds) {
                    val vi = getLocalized(context, resId, "vi")
                    val en = getLocalized(context, resId, "en")
                    if (vi.isNotBlank()) exactMap[vi] = resId
                    if (en.isNotBlank()) exactMap[en] = resId
                }

                // Index all string resources in R.string
                val fields = R.string::class.java.fields
                for (field in fields) {
                    if (!Modifier.isStatic(field.modifiers) || field.type != Int::class.javaPrimitiveType) {
                        continue
                    }
                    val resId = try { field.getInt(null) } catch (_: Throwable) { continue }
                    val viText = getLocalized(context, resId, "vi")
                    val enText = getLocalized(context, resId, "en")
                    if (viText.isBlank() && enText.isBlank()) continue

                    val viHasPercent = viText.contains('%')
                    val enHasPercent = enText.contains('%')

                    if (!viHasPercent && !enHasPercent) {
                        if (viText.isNotBlank()) exactMap.putIfAbsent(viText, resId)
                        if (enText.isNotBlank()) exactMap.putIfAbsent(enText, resId)
                    } else {
                        val viComp = if (viHasPercent) compileTemplate(viText) else null
                        val enComp = if (enHasPercent) compileTemplate(enText) else null
                        val maxPos = maxOf(
                            viComp?.args?.maxOfOrNull { it.position } ?: 0,
                            enComp?.args?.maxOfOrNull { it.position } ?: 0,
                            1
                        )
                        if (viComp != null || enComp != null) {
                            formatEntries.add(FormatEntry(resId, viComp, enComp, maxPos))
                        }
                    }
                }
            } catch (_: Throwable) {}
            isInitialized = true
        }
    }

    fun resolve(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        val context = getAppContext() ?: return raw
        ensureInitialized(context)
        val localizer = Localizer(context)

        // 1. Exact string match
        exactMap[raw]?.let { resId ->
            return localizer.get(resId)
        }

        // 2. Parameterized format match
        for (entry in formatEntries) {
            val enMatch = entry.enCompiled?.regex?.matchEntire(raw)
            if (enMatch != null && entry.enCompiled != null) {
                val extractedArgs = arrayOfNulls<Any>(entry.maxPos)
                for (i in entry.enCompiled.args.indices) {
                    val argDef = entry.enCompiled.args[i]
                    val rawVal = enMatch.groupValues[i + 1]
                    val parsedVal: Any = when (argDef.type.lowercaseChar()) {
                        'd', 'i' -> rawVal.toIntOrNull() ?: rawVal.toLongOrNull() ?: rawVal
                        'f', 'e', 'g' -> rawVal.toDoubleOrNull() ?: rawVal
                        else -> rawVal
                    }
                    val pos0 = argDef.position - 1
                    if (pos0 in extractedArgs.indices) {
                        extractedArgs[pos0] = parsedVal
                    }
                }
                val nonNullArgs = extractedArgs.map { it ?: "" }.toTypedArray()
                return localizer.get(entry.resId, *nonNullArgs)
            }

            val viMatch = entry.viCompiled?.regex?.matchEntire(raw)
            if (viMatch != null && entry.viCompiled != null) {
                val extractedArgs = arrayOfNulls<Any>(entry.maxPos)
                for (i in entry.viCompiled.args.indices) {
                    val argDef = entry.viCompiled.args[i]
                    val rawVal = viMatch.groupValues[i + 1]
                    val parsedVal: Any = when (argDef.type.lowercaseChar()) {
                        'd', 'i' -> rawVal.toIntOrNull() ?: rawVal.toLongOrNull() ?: rawVal
                        'f', 'e', 'g' -> rawVal.toDoubleOrNull() ?: rawVal
                        else -> rawVal
                    }
                    val pos0 = argDef.position - 1
                    if (pos0 in extractedArgs.indices) {
                        extractedArgs[pos0] = parsedVal
                    }
                }
                val nonNullArgs = extractedArgs.map { it ?: "" }.toTypedArray()
                return localizer.get(entry.resId, *nonNullArgs)
            }
        }

        return raw
    }

    private fun getLocalized(context: Context, @StringRes resId: Int, lang: String): String {
        return try {
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(lang))
            val localizedContext = context.createConfigurationContext(config)
            localizedContext.getString(resId)
        } catch (_: Exception) {
            ""
        }
    }
}

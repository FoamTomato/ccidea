package com.ccidea.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "CcideaSettings", storages = [Storage("ccidea.xml")])
class CcideaSettings : PersistentStateComponent<CcideaSettings.State> {

    data class State(
        var refreshSeconds: Int = 30,
        var warnPercent: Int = 80,
        var errorPercent: Int = 95,
        var resetSoonMinutes: Int = 10,
        var claudeConfigDir: String = "",
        var currencyDisplay: String = "USD",
        var offlineOnly: Boolean = false,
        var customTokenLimit: Long = 0,
        /** Block-usage notifications (80%/95%/reset-soon). Off by default — the auto
         *  detected token limit is unreliable when usage spans multiple IDEs/CLIs. */
        var enableBlockNotifications: Boolean = false,
        /** Auto-expire toast notifications after this many seconds (0 = no auto-close). */
        var notificationAutoCloseSeconds: Int = 60,
        var showStatusBarWidget: Boolean = true,
        /** "system" | "en" | "zh" — controls which CcideaBundle locale the UI reads. */
        var uiLanguage: String = "system",
        /**
         * If true, charge 1-hour cache writes at 2× the 5-minute rate (Anthropic's real
         * pricing). Default false so totals match `ccusage` (which uses LiteLLM's single
         * cache_creation_input_token_cost regardless of TTL).
         */
        var oneHourCachePremium: Boolean = false,
        /** "smart" (1.2k / 3.4M) | "raw" (5,434,148) | "kilo" (5,434k). */
        var tokenUnit: String = "smart",
        /** Pause the 30s poll when the Ccidea tool window is hidden. */
        var pollOnlyWhenVisible: Boolean = true,
        // Chart visibility toggles (R1).
        var showDailyStackedBar: Boolean = true,
        var showDailyTokenTrend: Boolean = true,
        var showDailyCostTrend: Boolean = true,
        var showBlocksBurnRate: Boolean = true,
        var showPatternsHeatmap: Boolean = true,
        var showPatternsHitRatio: Boolean = true
    )

    private var state = State()
    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    companion object {
        fun getInstance(): CcideaSettings = ApplicationManager.getApplication().service()
    }
}

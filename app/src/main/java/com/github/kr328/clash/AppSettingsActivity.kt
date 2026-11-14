package com.github.kr328.clash

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.AppSettingsDesign
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.design.store.UiStore.Companion.mainActivityAlias
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.ApplicationObserver
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class AppSettingsActivity : BaseActivity<AppSettingsDesign>(), Behavior {
    override suspend fun main() {
        val design = AppSettingsDesign(
            this,
            uiStore,
            ServiceStore(this),
            this,
            clashRunning,
            ::onHideIconChange,
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        AppSettingsDesign.Request.ReCreateAllActivities ->
                            ApplicationObserver.createdActivities.forEach { activity ->
                                activity.recreate()
                            }
                        AppSettingsDesign.Request.StartAccessControlList ->
                            startActivity(AccessControlActivity::class.intent)
                        AppSettingsDesign.Request.ShowHotspotInfo ->
                            showHotspotInfo()
                    }
                }
            }
        }
    }

    private fun showHotspotInfo() {
        val addresses = Dashboard.localAddresses().map { "$it:${Dashboard.PROXY_PORT}" }

        val message = if (addresses.isEmpty()) {
            getString(DesignR.string.hotspot_no_address)
        } else {
            getString(DesignR.string.hotspot_info_message) + "\n\n" +
                    addresses.joinToString("\n") + "\n\n" +
                    getString(DesignR.string.hotspot_info_warning)
        }

        AlertDialog.Builder(this)
            .setTitle(DesignR.string.hotspot_address)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override var autoRestart: Boolean
        get() {
            val status = packageManager.getComponentEnabledSetting(
                RestartReceiver::class.componentName
            )

            return status == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        set(value) {
            val status = if (value)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            packageManager.setComponentEnabledSetting(
                RestartReceiver::class.componentName,
                status,
                PackageManager.DONT_KILL_APP,
            )
        }

    private fun onHideIconChange(hide: Boolean) {
        val newState = if (hide) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        packageManager.setComponentEnabledSetting(
            mainActivityAlias,
            newState,
            PackageManager.DONT_KILL_APP
        )
    }
}

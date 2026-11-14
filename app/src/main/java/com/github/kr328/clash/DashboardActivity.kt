package com.github.kr328.clash

import androidx.activity.OnBackPressedCallback
import com.github.kr328.clash.design.DashboardDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.R as DesignR
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class DashboardActivity : BaseActivity<DashboardDesign>() {
    override suspend fun main() {
        val design = DashboardDesign(this)

        setContentDesign(design)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!design.goBack()) finish()
            }
        })

        try {
            design.load(Dashboard.url())
        } catch (e: Exception) {
            design.showToast(DesignR.string.dashboard_unavailable, ToastDuration.Long)
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStop -> finish()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        DashboardDesign.Request.Close -> finish()
                    }
                }
            }
        }
    }
}

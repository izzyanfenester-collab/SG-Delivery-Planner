package com.izzyan.sgdeliveryplanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider

/** DEBUG only, synthetic Room records supplied by instrumentation. Both versions
 * use the same device, typography, backdrop and viewport. The current version is
 * the actual production History composable, including all original handlers.
 */
class HistorySpacingVerificationActivity884:ComponentActivity() {
    internal lateinit var vm:PlannerViewModel
    internal var baseline by mutableStateOf(false)
    internal var fontScale by mutableFloatStateOf(1f)
    internal var widthDp by mutableIntStateOf(400)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        vm=ViewModelProvider(this)[PlannerViewModel::class.java]
        setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,fontScale)) {
                IzzDeliveryTheme(dark=true) {
                    MarinaBayBackdrop(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                            horizontalAlignment=Alignment.CenterHorizontally) {
                            Text("HISTORY SPACING · SYNTHETIC QA RECORDS",color=SGColors.Gold,
                                fontSize=10.sp,modifier=Modifier.padding(4.dp))
                            Box(Modifier.widthIn(max=widthDp.dp).fillMaxWidth().weight(1f)) {
                                key(baseline,fontScale,widthDp) {
                                    if(baseline) BaselineHistory883(vm) else History(vm)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

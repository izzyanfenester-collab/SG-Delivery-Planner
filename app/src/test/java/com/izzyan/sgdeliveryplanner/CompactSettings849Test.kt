package com.izzyan.sgdeliveryplanner
import org.junit.Assert.*
import org.junit.Test
class CompactSettings849Test {
    @Test fun postalStatusIsNotFalseSuccess() {
        assertEquals(SGColors.SuccessText, settingsPostalColor("Up to date",false))
        assertEquals(SGColors.Error, settingsPostalColor("Update failed",false))
        assertEquals(SGColors.Warning, settingsPostalColor("Awaiting verification",false))
        assertEquals(SGColors.Warning, settingsPostalColor("Up to date",true))
    }
    @Test fun appStatusReflectsRealCheck() {
        val state = AppUpdateState(848,null,null,null,0,"")
        assertEquals(SGColors.Silver,settingsAppColor(state,false))
        assertEquals(SGColors.Error,settingsAppColor(state.copy(error="Offline"),false))
        assertEquals(SGColors.Warning,settingsAppColor(state,true))
        assertEquals(SGColors.SuccessText,settingsAppColor(state.copy(latestBuild=848,lastChecked=100),false))
    }
}
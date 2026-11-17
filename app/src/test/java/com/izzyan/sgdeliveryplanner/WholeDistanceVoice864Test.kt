package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

/** Synthetic GPS trace replay at step distances; no live device claim. */
class WholeDistanceVoice864Test {
    private val cue=NavigationPanelCue(NavigationSign.TURN_RIGHT)
    private fun phrase(distance:Double,previous:Double?=null,speed:Float=18f,force:Boolean=false)=
        PandaVoiceMapper.maneuver("turn","right","Turn right",null,cue,distance,speed,
            previousDistance=previous,force=force)

    @Test fun wholeKilometresFrom25Through19ToOneNeverSpeakPoints() {
        for (km in listOf(25,19,10,5,3,2,1)) {
            val mark=km*1000
            val previous=mark+35.0
            val current=mark-12.0
            val p=phrase(current,previous,speed=25f)
            assertTrue("missing ${km}km",p.parts.isNotEmpty())
            assertFalse(p.parts.map{it.clip}.contains("word_point"))
            assertFalse(p.parts.map{it.clip}.any{it.contains("decimal")})
        }
        assertTrue(PandaVoiceMapper.composeAtDistance("turn","right","Turn right",
            null,cue,1900).isEmpty())
        assertTrue(PandaVoiceMapper.composeAtDistance("turn","right","Turn right",
            null,cue,10500).isEmpty())
    }

    @Test fun metresUnderOneKilometreNeverTurnIntoDecimalKms() {
        for (mark in listOf(900,800,500,300,200,100,50)) {
            val p=phrase(mark-8.0,mark+35.0)
            assertTrue("missing ${mark}m",p.parts.isNotEmpty())
            assertFalse(p.parts.any{it.clip=="word_point" || it.clip=="unit_kilometres"})
        }
        val eightHundred=phrase(795.0,820.0)
        assertTrue(eightHundred.parts.isNotEmpty())
    }

    @Test fun oneKilometreCrossingOneThousandEightyToNineHundredSeventy() {
        val p=phrase(970.0,1080.0,speed=20f)
        assertEquals(listOf("turn_right_1km"),p.parts.map{it.clip})
        assertFalse(PandaVoiceMapper.maneuver("turn","right","Turn right",null,
            cue,1900.0,20f,force=true).parts.isNotEmpty())
    }

    @Test fun forceAfterRerouteDoesNotInventShorterDistance() {
        for (m in listOf(3700.0,5700.0,10500.0,1900.0)) {
            assertTrue("unexpected shortened voice at ${m}m",phrase(m,null,force=true).parts.isEmpty())
        }
        val arrivedAtThreeKm=phrase(2970.0,3050.0)
        assertEquals(listOf("distance_in_3km","turn_right"),arrivedAtThreeKm.parts.map{it.clip})
    }

    @Test fun gatePreventsDuplicateCheckpointAndResetsAfterNewRoute() {
        val gate=PandaVoiceGate()
        val before=gate.previousDistance(6,1080.0)
        assertNull(before)
        val previous=gate.previousDistance(6,970.0)
        val p=phrase(970.0,previous,20f)
        assertTrue(gate.eligible(6,p,970.0))
        gate.mark(6,p,970.0)
        val repeated=phrase(965.0,970.0,20f)
        assertTrue(repeated.parts.isEmpty())
        assertFalse(gate.eligible(6,p,965.0))
        gate.reset()
        assertNull(gate.previousDistance(6,1080.0))
        val next=phrase(970.0,gate.previousDistance(6,970.0),20f)
        assertTrue(gate.eligible(6,next,970.0))
    }

    @Test fun impossibleOldCrossingsAreSkippedAndNavigationDistanceNotModified() {
        assertNull(WholeDistanceVoiceScheduler.checkpoint(1200.0,20f,2000.0))
        assertEquals(500,WholeDistanceVoiceScheduler.checkpoint(500.0,20f,1200.0))
        val exact=1900.75
        assertNull(WholeDistanceVoiceScheduler.checkpoint(exact,20f,null,true))
        assertEquals(exact,1900.75,0.0)
    }

    @Test fun nearExitCriticalPhraseStillWorksWithoutDistance() {
        val now=phrase(10.0,35.0,12f)
        assertEquals(VoicePriority.CRITICAL,now.priority)
        assertEquals(listOf("turn_right"),now.parts.map{it.clip})
    }
}

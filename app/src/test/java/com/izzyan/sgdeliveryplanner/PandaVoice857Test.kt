package com.izzyan.sgdeliveryplanner
import org.junit.Test
import org.junit.Assert.*
class PandaVoice857Test {
 private fun parts(m:Int, type:String="turn", modifier:String="right", cue:NavigationPanelCue=NavigationPanelCue(NavigationSign.TURN_RIGHT))=PandaVoiceMapper.composeAtDistance(type,modifier,"Turn right",null,cue,m)
 @Test fun dynamicDistances(){
  assertEquals(listOf("distance_in_3km","turn_right"),parts(3000))
  assertEquals(listOf("distance_in_5km","turn_left"),parts(5000,"turn","left",NavigationPanelCue(NavigationSign.TURN_LEFT)))
  assertTrue(parts(2500).isEmpty())
  assertTrue(parts(3700).isEmpty())
  assertEquals(listOf("word_in","number_19","unit_kilometres","turn_right"),parts(19000))
  assertEquals(listOf("distance_in_25km","turn_right"),parts(25000))
  assertEquals(listOf("distance_in_900m","turn_right"),parts(900))
  assertEquals(listOf("turn_right_800m"),parts(800))
  // No misleading "eight one hundred metres" from number_100 clip.
  assertTrue(parts(800,"keep","left",NavigationPanelCue(NavigationSign.KEEP_LEFT)).isEmpty())
  assertEquals(listOf("word_in","number_100","unit_metres","keep_left"),
      parts(100,"keep","left",NavigationPanelCue(NavigationSign.KEEP_LEFT)))
  assertEquals(listOf("distance_in_900m","keep_left"),
      parts(900,"keep","left",NavigationPanelCue(NavigationSign.KEEP_LEFT)))
  assertEquals(listOf("turn_right_200m"),parts(200))
 }
 @Test fun numberedExits(){
  assertEquals(listOf("distance_in_3km","take_exit_number","number_17","letter_b"),parts(3000,"off ramp","right",NavigationPanelCue(NavigationSign.EXIT_RIGHT,"17B")))
  assertEquals(listOf("take_exit_number","number_20","number_3","letter_a"),parts(0,"off ramp","right",NavigationPanelCue(NavigationSign.EXIT_RIGHT,"23A")))
 }
 @Test fun roundaboutAndThreshold(){
  assertEquals(listOf("roundabout_exit_7"),parts(0,"roundabout","right",NavigationPanelCue(NavigationSign.ROUNDABOUT,roundaboutExit=7)))
  val phrase=PandaVoiceMapper.maneuver("turn","right","Turn right",null,NavigationPanelCue(NavigationSign.TURN_RIGHT),2980.0,25f,previousDistance=3010.0)
  assertEquals(listOf("distance_in_3km","turn_right"),phrase.parts.map{it.clip})
  assertEquals(VoicePriority.ADVANCE,phrase.priority)
 }
 @Test fun wavCompositionAndUnsupportedExit(){
  val out=java.io.ByteArrayOutputStream()
  PandaWav.write(out,listOf(byteArrayOf(1,2),byteArrayOf(3,4)))
  val b=out.toByteArray()
  assertEquals("RIFF",String(b,0,4,Charsets.US_ASCII))
  assertEquals("WAVE",String(b,8,4,Charsets.US_ASCII))
  assertEquals(44+4+3120,b.size)
  assertEquals(listOf("exit_right"),parts(0,"off ramp","right",NavigationPanelCue(NavigationSign.EXIT_RIGHT,"17Z")))
 }
 @Test fun checkpointsAndRoadNames(){
  assertEquals(listOf("follow_woodlands"),PandaVoiceMapper.composeAtDistance("notification",null,"Follow the signs towards Woodlands Checkpoint",null,NavigationPanelCue(),0))
  assertEquals(listOf("distance_in_3km","turn_right","word_onto","road_pie"),PandaVoiceMapper.composeAtDistance("turn","right","Turn right onto PIE","PIE",NavigationPanelCue(NavigationSign.TURN_RIGHT),3000))
 }
 @Test fun stagedAnnouncementsReserveNearCue(){
  val gate=PandaVoiceGate()
  val cue=NavigationPanelCue(NavigationSign.TURN_RIGHT)
  fun phrase(m:Double,prev:Double?=null)=PandaVoiceMapper.maneuver("turn","right","Turn right",null,cue,m,25f,previousDistance=prev)
  val first=phrase(3000.0); assertTrue(gate.eligible(1,first,3000.0)); gate.mark(1,first,3000.0)
  assertFalse(gate.eligible(1,first,2990.0))
  val middle=phrase(990.0,1010.0);assertTrue(gate.eligible(1,middle,990.0));gate.mark(1,middle,990.0)
  val near=phrase(195.0,205.0);assertTrue(gate.eligible(1,near,195.0));gate.mark(1,near,195.0)
  val now=phrase(15.0,35.0);assertTrue(gate.eligible(1,now,15.0));gate.mark(1,now,15.0)
  assertFalse(gate.eligible(1,now,12.0))
  gate.reset();assertTrue(gate.eligible(1,first,3000.0))
 }
}

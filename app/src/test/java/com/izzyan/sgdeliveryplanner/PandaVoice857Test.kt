package com.izzyan.sgdeliveryplanner
import org.junit.Test
import org.junit.Assert.*
class PandaVoice857Test {
 private fun parts(m:Int, type:String="turn", modifier:String="right", cue:NavigationPanelCue=NavigationPanelCue(NavigationSign.TURN_RIGHT))=PandaVoiceMapper.composeAtDistance(type,modifier,"Turn right",null,cue,m)
 @Test fun dynamicDistances(){
  assertEquals(listOf("distance_in_3km","turn_right"),parts(3000))
  assertEquals(listOf("distance_in_5km","turn_left"),parts(5000,"turn","left",NavigationPanelCue(NavigationSign.TURN_LEFT)))
  assertEquals(listOf("word_in","number_2","word_point","number_5","unit_kilometres","turn_right"),parts(2500))
  assertEquals(listOf("word_in","number_3","word_point","number_7","unit_kilometres","turn_right"),parts(3700))
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
}

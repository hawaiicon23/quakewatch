package dev.blx.btprobe

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

object VoiceCompose {
    @JvmStatic fun install(activity:WalkieTalkieActivity){activity.setContent{ProbeTheme{VoicePage(activity)}}}
    @Composable private fun VoicePage(activity:WalkieTalkieActivity){
        var tick by remember{mutableIntStateOf(0)}
        LaunchedEffect(activity){activity.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED){while(true){tick++;delay(200)}}}
        @Suppress("UNUSED_VARIABLE")val observe=tick
        val connected=activity.connected();val talking=activity.talking
        GlassScreen(activity,3,"Your voice, nearby.","Direct Bluetooth · 2.4 GHz · no SIM or internet."){
            Panel{
                Pill(if(connected)"Voice link connected" else "Not connected",connected)
                Text(activity.voiceStatus,style=MaterialTheme.typography.titleLarge)
                Supporting("Voice shares the persistent data links used by chat and sensing. No pairing prompt is required. Take turns speaking.")
                PremiumButton("Discover data links",secondary=true){activity.prepare("links")}
            }
            Panel{
                Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
                    Box(Modifier.sizeIn(minWidth=140.dp,minHeight=140.dp).size(164.dp).clip(CircleShape)
                        .background(if(talking)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha=if(connected).10f else .04f))
                        .border(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.3f),CircleShape)
                        .pointerInput(connected){detectTapGestures(onPress={if(connected){activity.startTalk();try{tryAwaitRelease()}finally{activity.stopTalk()}}})}
                        .semantics(mergeDescendants=true){role=Role.Button;contentDescription=if(talking)"Talking. Release to listen" else "Hold to talk";if(!connected)disabled();onClick(label=if(talking)"Stop talking" else "Start talking"){if(connected){if(activity.talking)activity.stopTalk()else activity.startTalk();true}else false}},contentAlignment=Alignment.Center){
                        WaveMark(Modifier.size(70.dp),if(talking)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                    }
                    Text(if(talking)"Release to listen" else "Hold to talk",style=MaterialTheme.typography.headlineMedium)
                    Supporting(if(connected)"Microphone is active only while talking." else "Connect to a phone to enable the microphone.")
                }
                if(talking)PremiumButton("Stop talking",secondary=true){activity.stopTalk()}
                PremiumButton("Send quiet test tone",enabled=connected,secondary=true){activity.sendTone()}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){Text("TX ${activity.sent}",fontWeight=FontWeight.SemiBold);Text("RX ${activity.received}",fontWeight=FontWeight.SemiBold);Text("Played ${activity.played}",fontWeight=FontWeight.SemiBold)}
                Supporting("Mic peak ${activity.micPeak} · received peak ${activity.rxPeak} / 32768")

            }
            Supporting("Keep this page open during use. Voice is not saved to storage. Voice is public and unencrypted, like nearby chat. It reaches connected nodes directly; it is not relayed.")
        }
    }
}

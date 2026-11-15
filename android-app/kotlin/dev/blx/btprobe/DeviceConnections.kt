package dev.blx.btprobe

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun DeviceConnectionsDialog(context:Context,mesh:MeshService?,revision:Int,onDismiss:()->Unit) {
    val now=SystemClock.elapsedRealtime()
    val advertised=remember(revision){mesh?.chat?.peers?.entries?.filter{now-it.value.seen<60000}?.toList() ?: emptyList()}
    val links=remember(revision){mesh?.bulk?.links()?.sortedWith(compareByDescending<BulkImages.LinkState>{it.sending+it.receiving>0}.thenByDescending{it.updated}) ?: emptyList()}
    val persistent=remember(revision){mesh?.data?.connectedPeers()?.toList() ?: emptyList()}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Nearby devices")},text={
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=520.dp).testTag("device-connections-list"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {Text("Seen over advertising · ${advertised.size}",style=MaterialTheme.typography.titleMedium)}
            if(advertised.isEmpty())item {Supporting("No recent device advertisements.")}
            items(advertised,key={"advertising-${it.key}"}){(id,peer)->
                Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(DeviceIdentity.display(peer.name,id),style=MaterialTheme.typography.labelLarge)
                    Supporting("${peer.rssi} dBm · last seen ${((now-peer.seen)/1000).coerceAtLeast(0)} s ago")
                    Supporting(if(mesh?.bulk?.hasPeer(id)==true)"Connected image service available" else "Advertising discovery")
                }
            }
            item {HorizontalDivider();Spacer(Modifier.height(8.dp));Text("Active data links · ${persistent.size+links.sumOf{it.sending+it.receiving}}",style=MaterialTheme.typography.titleMedium)}
            items(persistent,key={"persistent-$it"}){id->
                Column{Text(DeviceIdentity.display(mesh?.chat?.peerName(id) ?: "Phone",id),style=MaterialTheme.typography.labelLarge);Text("Connected · chat, images, sensing and voice",color=MaterialTheme.colorScheme.primary)}
            }
            if(links.isEmpty()&&persistent.isEmpty())item {Supporting("No data links yet. Compatible nearby phones connect automatically.")}
            items(links,key={"data-${it.peer}"}){link->
                Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(DeviceIdentity.display(mesh?.chat?.peerName(link.peer) ?: "Phone",link.peer),style=MaterialTheme.typography.labelLarge)
                    val state=when {
                        link.sending>0&&link.receiving>0->"Connected · sending and receiving"
                        link.sending>0->"Connected · sending"
                        link.receiving>0->"Connected · receiving"
                        link.connecting>0->"Connecting"
                        else->"Disconnected · last activity ${((now-link.updated)/1000).coerceAtLeast(0)} s ago"
                    }
                    Text(state,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
                    if(link.result.isNotBlank())Supporting(link.result)
                }
            }
            item {HorizontalDivider();Supporting("You: "+DeviceIdentity.display(DeviceIdentity.name(context),DeviceIdentity.id(context)))}
            item {Supporting("Advertising shows nearby presence. Only an open data socket is counted as an active data link.")}
        }
    },confirmButton={TextButton(onClick=onDismiss){Text("Done")}})
}

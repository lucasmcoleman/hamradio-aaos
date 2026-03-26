package com.hamradio.aaos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hamradio.aaos.radio.LogDir
import com.hamradio.aaos.radio.LogEntry
import com.hamradio.aaos.radio.protocol.BasicCommand
import com.hamradio.aaos.radio.protocol.BenshiMessage
import com.hamradio.aaos.radio.protocol.CommandGroup
import com.hamradio.aaos.radio.protocol.RadioCommands
import com.hamradio.aaos.radio.transport.ConnectionState
import com.hamradio.aaos.ui.theme.Accent
import com.hamradio.aaos.ui.theme.Background
import com.hamradio.aaos.ui.theme.OnSurfaceMuted
import com.hamradio.aaos.ui.theme.Outline
import com.hamradio.aaos.ui.theme.RxGreen
import com.hamradio.aaos.ui.theme.SurfaceCard
import com.hamradio.aaos.ui.theme.TxRed
import com.hamradio.aaos.vm.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DebugScreen(vm: MainViewModel, onClose: () -> Unit = {}) {
    val log      by vm.messageLog.collectAsStateWithLifecycle()
    val devInfo  by vm.deviceInfo.collectAsStateWithLifecycle()
    val isMock   = vm.isMockMode

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ----------------------------------------------------------------
        // Left: message log
        // ----------------------------------------------------------------
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceCard)
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onClose,
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                        border  = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.5f)),
                        shape   = RoundedCornerShape(8.dp),
                    ) { Text("← Back", style = MaterialTheme.typography.labelMedium) }
                    Text("Message Log", style = MaterialTheme.typography.headlineSmall)
                }
                if (log.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { vm.clearLog() },
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = TxRed),
                        border  = androidx.compose.foundation.BorderStroke(1.dp, TxRed.copy(alpha = 0.5f)),
                        shape   = RoundedCornerShape(8.dp),
                    ) {
                        Text("Clear", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (log.isEmpty()) {
                Text("No messages yet.", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding      = PaddingValues(bottom = 8.dp),
                ) {
                    items(log) { entry -> LogRow(entry) }
                }
            }
        }

        // ----------------------------------------------------------------
        // Right: controls + device info
        // ----------------------------------------------------------------
        Column(
            modifier = Modifier
                .width(280.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Device info card
            if (devInfo != null) {
                val d = devInfo!!
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceCard)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Device Info", style = MaterialTheme.typography.titleMedium, color = Accent)
                    InfoLine("Vendor",   "0x%02X".format(d.vendorId))
                    InfoLine("Product",  "0x%04X".format(d.productId))
                    InfoLine("HW ver",   d.hwVersion.toString())
                    InfoLine("SW ver",   d.softVersion.toString())
                    InfoLine("Channels", d.channelCount.toString())
                    InfoLine("DMR",      if (d.supportDmr) "Yes" else "No")
                    InfoLine("VFO",      if (d.supportVfo) "Yes" else "No")
                    InfoLine("NOAA",     if (d.supportNoaa) "Yes" else "No")
                    InfoLine("GMRS",     if (d.gmrs) "Yes" else "No")
                }
            }

            // Quick commands
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceCard)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Quick Commands", style = MaterialTheme.typography.titleMedium, color = Accent)
                QuickCmd("GET_DEV_INFO")   { vm.sendRawCommand(RadioCommands.getDevInfo()) }
                QuickCmd("GET_HT_STATUS")  { vm.sendRawCommand(RadioCommands.getHtStatus()) }
                QuickCmd("GET_SETTINGS")   { vm.sendRawCommand(RadioCommands.getSettings()) }
                QuickCmd("GET_BSS")        { vm.sendRawCommand(RadioCommands.getBssSettings()) }
                QuickCmd("GET_VOLUME")     { vm.sendRawCommand(RadioCommands.getVolume()) }
                QuickCmd("GET_POSITION")   { vm.sendRawCommand(RadioCommands.getPosition()) }
                QuickCmd("REFRESH CH")     { vm.refreshChannels() }
                Text("PF Test", style = MaterialTheme.typography.titleMedium, color = TxRed)
                QuickCmd("PF: TOGGLE A/B") { vm.sendRawCommand(RadioCommands.doProgFunc(RadioCommands.PF_TOGGLE_AB_CH)) }
                QuickCmd("PF: NEXT CH")    { vm.sendRawCommand(RadioCommands.doProgFunc(RadioCommands.PF_NEXT_CHANNEL)) }
                QuickCmd("PF: PREV CH")    { vm.sendRawCommand(RadioCommands.doProgFunc(RadioCommands.PF_PREV_CHANNEL)) }
                QuickCmd("PF: VOL UP")     { vm.sendRawCommand(RadioCommands.doProgFunc(RadioCommands.PF_VOL_UP)) }
                QuickCmd("PF: SCAN")       { vm.sendRawCommand(RadioCommands.doProgFunc(RadioCommands.PF_TOGGLE_CH_SCAN)) }
                QuickCmd("PF: MAIN PTT")   { vm.sendRawCommand(RadioCommands.doProgFunc(RadioCommands.PF_MAIN_PTT)) }
                QuickCmd("PF: TX POWER")   { vm.sendRawCommand(RadioCommands.doProgFunc(RadioCommands.PF_TOGGLE_TX_POWER)) }
            }

            // Transport badge
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isMock) Accent.copy(0.15f) else RxGreen.copy(0.12f))
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text  = if (isMock) "MOCK TRANSPORT" else "BLE TRANSPORT",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isMock) Accent else RxGreen,
                )
                if (!isMock) {
                    val connState by vm.connectionState.collectAsStateWithLifecycle()
                    Text(
                        text  = connState.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val isIn    = entry.direction == LogDir.IN
    val cmdName = commandName(entry.command)
    val color   = when {
        isIn && entry.isReply -> RxGreen
        isIn                  -> Accent         // notification
        else                  -> TxRed          // outbound command
    }
    val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestamp))

    Row(
        modifier  = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.07f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (isIn) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
            contentDescription = null,
            tint        = color,
            modifier    = Modifier.width(14.dp).height(14.dp),
        )
        Text(
            text  = timeStr,
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceMuted,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text  = cmdName,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.weight(1f),
        )
        Text(
            text  = "[${entry.bodyLen}] ${entry.bodyHex}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = OnSurfaceMuted,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun QuickCmd(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick  = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors   = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
        border   = androidx.compose.foundation.BorderStroke(1.dp, Outline),
        shape    = RoundedCornerShape(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun commandName(cmd: Int): String = when (cmd) {
    BasicCommand.GET_DEV_INFO            -> "GET_DEV_INFO"
    BasicCommand.GET_HT_STATUS           -> "GET_HT_STATUS"
    BasicCommand.READ_SETTINGS           -> "READ_SETTINGS"
    BasicCommand.WRITE_SETTINGS          -> "WRITE_SETTINGS"
    BasicCommand.READ_RF_CH              -> "READ_RF_CH"
    BasicCommand.WRITE_RF_CH             -> "WRITE_RF_CH"
    BasicCommand.GET_VOLUME              -> "GET_VOLUME"
    BasicCommand.SET_VOLUME              -> "SET_VOLUME"
    BasicCommand.EVENT_NOTIFICATION      -> "EVENT_NOTIFICATION"
    BasicCommand.REGISTER_NOTIFICATION   -> "REG_NOTIF"
    BasicCommand.READ_BSS_SETTINGS       -> "READ_BSS"
    BasicCommand.WRITE_BSS_SETTINGS      -> "WRITE_BSS"
    BasicCommand.READ_STATUS             -> "READ_STATUS"
    BasicCommand.GET_POSITION            -> "GET_POSITION"
    BasicCommand.SET_IN_SCAN             -> "SET_SCAN"
    BasicCommand.STORE_SETTINGS          -> "STORE_SETTINGS"
    else                                 -> "CMD_$cmd"
}

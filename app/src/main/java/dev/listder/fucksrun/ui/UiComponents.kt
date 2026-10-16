package dev.listder.fucksrun.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SectionCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Text(title, style = MiuixTheme.textStyles.subtitle, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() })
            }
            content()
        }
    }
}

@Composable
internal fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
internal fun ActionButton(
    text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, primary: Boolean = false, destructive: Boolean = false,
) {
    val colors = when {
        destructive -> ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.errorContainer,
            contentColor = MiuixTheme.colorScheme.onErrorContainer)
        primary -> ButtonDefaults.buttonColorsPrimary()
        else -> ButtonDefaults.buttonColors()
    }
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), colors = colors) {
        Icon(icon, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MiuixTheme.textStyles.button)
    }
}

@Composable
internal fun NumberField(value: String, changed: (String) -> Unit, label: String, enabled: Boolean) {
    TextField(value = value, onValueChange = changed, label = label, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun MessageCard(message: String, error: Boolean = false) {
    val color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, insideMargin = PaddingValues(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(MiuixIcons.Info, null, tint = color, modifier = Modifier.size(20.dp))
            Text(message, color = color, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun ConfirmationDialog(
    show: Boolean, title: String, confirmText: String, onConfirm: () -> Unit,
    onDismiss: () -> Unit, dismissText: String = "取消", enabled: Boolean = true,
) {
    OverlayDialog(show = show, title = title, onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = dismissText, onClick = onDismiss, modifier = Modifier.weight(1f))
            TextButton(text = confirmText, onClick = onConfirm, enabled = enabled,
                colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1f))
        }
    }
}

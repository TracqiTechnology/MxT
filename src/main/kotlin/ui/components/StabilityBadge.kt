package ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.model.StabilityLevel

@Composable
fun StabilityBadge(level: StabilityLevel, modifier: Modifier = Modifier, compact: Boolean = false) {
    when (level) {
        StabilityLevel.STABLE -> {} // No badge for stable features
        StabilityLevel.BETA -> Badge(text = "BETA", isAlpha = false, modifier = modifier, compact = compact)
        StabilityLevel.ALPHA -> Badge(text = "ALPHA", isAlpha = true, modifier = modifier, compact = compact)
    }
}

@Composable
private fun Badge(text: String, isAlpha: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    val backgroundColor = if (isAlpha) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val textColor = if (isAlpha) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = backgroundColor
    ) {
        Text(
            text = text,
            style = if (compact) {
                MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
            } else {
                MaterialTheme.typography.labelSmall
            },
            color = textColor,
            modifier = Modifier.padding(
                horizontal = if (compact) 3.dp else 4.dp,
                vertical = if (compact) 0.dp else 1.dp
            )
        )
    }
}

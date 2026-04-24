package ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.model.StabilityLevel

@Composable
fun AlphaBetaDisclaimer(
    onAccept: () -> Unit,
    onStickToME7: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
        ) {
            Text(
                text = "A Word About Features That Might Set Your Car on Fire",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Spacer(modifier = Modifier.height(16.dp))

            SelectionContainer(
                modifier = Modifier.weight(1f),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionHeader("The Stable Stuff")
                    BodyText(
                        "The ME7 calibration path has been beaten on by the community for years. It works. " +
                        "The math is validated. The write-readback pipeline has hundreds of tests. If you stick " +
                        "to ME7, you're on solid ground \u2014 well, as solid as open-source ECU tuning gets."
                    )

                    SectionHeader("The Beta Stuff")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StabilityBadge(StabilityLevel.BETA)
                    }
                    BodyText(
                        "MED17 support is functional and actively used, but hasn't been through the same gauntlet. " +
                        "The calibration math is correct, the profiles work, but edge cases are still being " +
                        "discovered. Think of it as a car that starts every morning but occasionally makes a noise " +
                        "you can't quite identify. Marked with a yellow BETA badge."
                    )

                    SectionHeader("The Alpha Stuff \u2014 Here Be Dragons")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StabilityBadge(StabilityLevel.ALPHA)
                    }
                    BodyText(
                        "Motronic 3/5, MED9, the data logger, the RAM sniffer, and the A2L generator are all in " +
                        "active development. \"Active development\" is a polite way of saying \"it compiles, it " +
                        "mostly works, and we'd really like you to tell us when it doesn't.\" These features are " +
                        "marked with a red ALPHA badge. Use them, test them, report issues \u2014 but don't stake " +
                        "your engine on them without independent verification. We're not kidding about the " +
                        "verification part."
                    )

                    SectionHeader("Why We're Telling You This")
                    BodyText(
                        "Because we'd rather you know what you're getting into than find out the hard way. Every " +
                        "feature in this app will eventually graduate to stable, but only after enough people have " +
                        "tested it and enough bugs have been squashed. Your feedback is literally what makes that " +
                        "happen. If something breaks, Help \u2192 Report Issue is right there. Use it."
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onStickToME7) {
                    Text("I'll Stick to ME7")
                }
                Button(onClick = onAccept) {
                    Text("Got It, Show Me Everything")
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        ),
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

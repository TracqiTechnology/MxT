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

@Composable
fun EulaDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
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
                text = "Hold Up — Read This Before You Blow Up Your Engine",
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
                    SectionHeader("No Warranty — We Mean It")
                    BodyText(
                        "Licensed under the Apache License, Version 2.0. This software is provided on an " +
                        "\"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied, " +
                        "including, without limitation, any warranties or conditions of TITLE, NON-INFRINGEMENT, " +
                        "MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE. You are solely responsible for " +
                        "determining the appropriateness of using or redistributing this software and assume any " +
                        "risks associated with your exercise of permissions under the License."
                    )

                    SectionHeader("What You Are About to Do")
                    BodyText(
                        "You are about to use free, open-source software written by enthusiasts on the internet " +
                        "to modify the binary calibration data inside your engine control unit. Let that sink in. " +
                        "This software will help you rewrite the instructions that control fuel injection timing, " +
                        "boost pressure targets, ignition advance, and mass airflow sensor linearization — " +
                        "the things that stand between a healthy engine and a very expensive paperweight."
                    )

                    SectionHeader("The Combinatorial Problem")
                    BodyText(
                        "ME7 ECUs ship in dozens of hardware and software variants. Each variant has a different " +
                        "binary layout. XDF definition files attempt to map human-readable names to byte offsets " +
                        "inside that binary — but XDF files are community-maintained, not OEM-verified. The number " +
                        "of possible combinations of ECU variant × XDF definition × bin revision × tuning " +
                        "permutation is astronomically large. MxT has been tested against a tiny fraction " +
                        "of them. Your specific combination may not be one of them."
                    )

                    SectionHeader("You MUST Verify Every Output")
                    BodyText(
                        "Before writing any modified binary to your ECU, you MUST:\n\n" +
                        "  • Hex-diff the modified bin against your stock bin to confirm only the intended " +
                        "bytes changed.\n" +
                        "  • Load the modified bin in TunerPro (or equivalent) and verify that every addressed " +
                        "map reads correctly.\n" +
                        "  • Confirm that the mutations match your intent — not just that \"something changed,\" " +
                        "but that the right thing changed by the right amount at the right address.\n" +
                        "  • Keep a known-good backup of your stock bin. Always."
                    )

                    SectionHeader("MxT Helps You Tune — It Does Not Tune for You")
                    BodyText(
                        "This application is a calculator and a visualizer. It shows you what the math says. " +
                        "It does not know your engine, your turbo, your injectors, your fuel system, or your " +
                        "mechanical sympathy. It cannot predict whether the values it suggests will make your " +
                        "car faster, slower, or a lawn ornament. That judgment is yours and yours alone."
                    )

                    SectionHeader("Limitation of Liability")
                    BodyText(
                        "In no event and under no legal theory — whether in tort (including negligence), contract, " +
                        "or otherwise — shall any contributor to this project be liable to you for damages, " +
                        "including any direct, indirect, special, incidental, or consequential damages of any " +
                        "character arising as a result of this License or out of the use or inability to use " +
                        "the software, including but not limited to damages for loss of goodwill, work stoppage, " +
                        "engine failure, connecting rod liberation, piston extrusion through the hood, or any and " +
                        "all other mechanical or commercial damages or losses."
                    )

                    SectionHeader("Your Responsibility")
                    BodyText(
                        "By clicking \"I Accept the Risk\" below, you acknowledge that you are completely, fully, " +
                        "and totally responsible for verifying all outputs of this software before applying them " +
                        "to any engine control unit. You understand that incorrect calibration data can cause " +
                        "catastrophic engine damage, and you accept that risk voluntarily."
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
                OutlinedButton(onClick = onDecline) {
                    Text("No Thanks, I Like My Engine")
                }
                Button(onClick = onAccept) {
                    Text("I Accept the Risk")
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

package com.aicomics.motionstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicomics.motionstudio.storyboard.StoryboardViewModel
import com.aicomics.motionstudio.ui.StudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StudioTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    StudioHome()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun StudioHome(viewModel: StoryboardViewModel = viewModel()) {
    val state by viewModel.state
    var prompt by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "AI Comic Motion Studio",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Local-first comic video production",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Story prompt", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    label = { Text("Describe your story") },
                    placeholder = { Text("A swordsman protects a futuristic city from an energy monster...") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { viewModel.setAspectRatio("9:16") }, label = { Text("9:16") })
                    AssistChip(onClick = { viewModel.setAspectRatio("16:9") }, label = { Text("16:9") })
                    AssistChip(onClick = { viewModel.setAspectRatio("1:1") }, label = { Text("1:1") })
                }
                Button(
                    onClick = { viewModel.createDraft(prompt) },
                    enabled = prompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create storyboard draft")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Storyboard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("30 sec · ${state.aspectRatio}", style = MaterialTheme.typography.labelLarge)
        }

        if (state.message.isNotBlank()) {
            Text(state.message, color = MaterialTheme.colorScheme.primary)
        }

        if (state.scenes.isEmpty()) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("No storyboard yet", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Enter a prompt to create a local template draft. This initial version does not call an AI model.")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.scenes, key = { it.id }) { scene ->
                    Card {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(
                                text = "${scene.id} · ${scene.title}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text("${scene.startSeconds}s – ${scene.endSeconds}s")
                            Spacer(Modifier.height(5.dp))
                            Text(scene.visualDescription)
                            Spacer(Modifier.height(5.dp))
                            Text("Camera: ${scene.cameraMotion}", style = MaterialTheme.typography.bodySmall)
                            Text("Dialogue: ${scene.dialogue}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Text(
            "Prototype status: storyboard templates only. Image generation, voice, audio mixing, and MP4 export are not integrated yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

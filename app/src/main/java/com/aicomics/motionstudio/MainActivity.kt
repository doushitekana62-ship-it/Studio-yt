package com.aicomics.motionstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicomics.motionstudio.render.MediaCodecComicVideoRenderer
import com.aicomics.motionstudio.render.RenderProgress
import com.aicomics.motionstudio.render.RenderRequest
import com.aicomics.motionstudio.render.RenderResult
import com.aicomics.motionstudio.render.RenderStage
import com.aicomics.motionstudio.storyboard.Storyboard
import com.aicomics.motionstudio.storyboard.StoryboardViewModel
import com.aicomics.motionstudio.ui.StudioTheme
import kotlinx.coroutines.launch

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var isRendering by remember { mutableStateOf(false) }
    var renderMessage by remember { mutableStateOf("") }
    var exportPath by remember { mutableStateOf<String?>(null) }

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
                    minLines = 3,
                    label = { Text("Describe your story") },
                    placeholder = { Text("A swordsman protects a futuristic city from an energy monster...") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { viewModel.setAspectRatio("9:16") }, label = { Text("9:16") })
                    AssistChip(onClick = { viewModel.setAspectRatio("16:9") }, label = { Text("16:9") })
                    AssistChip(onClick = { viewModel.setAspectRatio("1:1") }, label = { Text("1:1") })
                }
                Button(
                    onClick = {
                        exportPath = null
                        renderMessage = ""
                        viewModel.createDraft(prompt)
                    },
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
                    Text("Create a draft to review the scene timeline. Story content is currently generated from offline templates, not an AI model.")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = true),
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
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRendering,
                onClick = {
                    val (width, height) = when (state.aspectRatio) {
                        "16:9" -> 1280 to 720
                        "1:1" -> 720 to 720
                        else -> 720 to 1280
                    }
                    val storyboard = Storyboard(
                        title = "Untitled Comic Story",
                        prompt = prompt,
                        totalDurationSeconds = 30,
                        aspectRatio = state.aspectRatio,
                        scenes = state.scenes
                    )
                    isRendering = true
                    exportPath = null
                    renderMessage = "Preparing local H.264 export..."
                    scope.launch {
                        val renderer = MediaCodecComicVideoRenderer(context)
                        val result = renderer.render(
                            RenderRequest(
                                storyboard = storyboard,
                                outputWidth = width,
                                outputHeight = height,
                                framesPerSecond = 24,
                                includeSubtitles = true,
                                includeAudio = false
                            )
                        ) { progress: RenderProgress ->
                            renderMessage = when (progress.stage) {
                                RenderStage.RENDERING_VIDEO -> {
                                    val percent = progress.fraction?.let { "${(it * 100).toInt()}%" } ?: "working"
                                    "${progress.message} · $percent"
                                }
                                else -> progress.message.ifBlank { progress.stage.name }
                            }
                        }
                        isRendering = false
                        when (result) {
                            is RenderResult.Success -> {
                                exportPath = result.outputPath
                                renderMessage = "MP4 created. Duration: ${result.durationMilliseconds / 1000}s. File is in app-private storage."
                            }
                            is RenderResult.Failure -> renderMessage = "Export failed: ${result.reason}"
                            RenderResult.Cancelled -> renderMessage = "Export cancelled."
                        }
                    }
                }
            ) {
                Text(if (isRendering) "Rendering..." else "Export comic motion MP4")
            }
        }

        if (renderMessage.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(renderMessage, style = MaterialTheme.typography.bodyMedium)
                    if (exportPath != null) {
                        Spacer(Modifier.height(4.dp))
                        Text("Path: $exportPath", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Text(
            "Export currently creates generated placeholder comic panels with camera motion and H.264 video only. AI image generation, voice, audio mixing, gallery saving, and sharing are not integrated.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

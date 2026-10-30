package com.swordfish.lemuroid.app.mobile.feature.gamemenu.states

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R

/** Save or load slots, each with a picture of the game at the moment it was saved. */
@Composable
fun GameMenuStatesScreen(
    viewModel: GameMenuStatesViewModel,
    onStateClicked: (Int) -> Unit,
) {
    val state = viewModel.uiStates.collectAsState(initial = GameMenuStatesViewModel.State())

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        state.value.entries.forEachIndexed { index, entry ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = entry.enabled) { onStateClicked(index) }
                        .alpha(if (entry.enabled) 1f else 0.5f)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(width = 132.dp, height = 88.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(androidx.compose.ui.graphics.Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    if (entry.preview != null) {
                        // The whole picture, never cropped.
                        Image(
                            modifier = Modifier.size(width = 132.dp, height = 88.dp),
                            bitmap = entry.preview.asImageBitmap(),
                            contentScale = ContentScale.Fit,
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                            contentDescription = null,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.game_menu_state_empty),
                            style = MaterialTheme.typography.labelMedium,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(text = entry.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

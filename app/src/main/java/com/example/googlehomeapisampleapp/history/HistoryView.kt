/* Copyright 2026 Google LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.example.googlehomeapisampleapp.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Doorbell
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.example.googlehomeapisampleapp.viewmodel.HomeAppViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryView(viewModel: HomeAppViewModel) {
    val historyItems = viewModel.historyFlow.collectAsLazyPagingItems()
    val selectedDevice by viewModel.selectedHistoryDeviceVM.collectAsStateWithLifecycle()

    // Handle Swipe Back / System Back button to return to previous screen
    BackHandler(enabled = true) {
        viewModel.clearHistorySelection()
    }

    // Set page background to White
    Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
        Column {
            if (selectedDevice != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp), // Standardized spacing
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        // Spacing stays the same, "History" is appended
                        text = "${selectedDevice?.name?.collectAsState()?.value}: History",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.Black, // Text changed to black
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            HistoryList(events = historyItems)
        }
    }
}

@Composable
fun HistoryList(events: LazyPagingItems<HistoryEventUi>) {
    val errorPainter = rememberVectorPainter(Icons.Outlined.Image)
    val today = LocalDate.now()

    val isFinishedLoading = events.loadState.refresh is LoadState.NotLoading
    val isEmpty = events.itemCount == 0

    if (isFinishedLoading && isEmpty) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Outlined.Videocam,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = Color.LightGray
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "No camera events",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Black
                )
                Text(
                    text = "Activity will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize().background(Color.White)) {
            items(
                count = events.itemCount,
                key = events.itemKey { it.id }
            ) { index ->
                when (val item = events[index]) {
                    is HistoryEventUi.DateSeparatorModel -> DateSeparatorRow(item.date, today)
                    is HistoryUiDataModel -> HistoryItemRow(item, errorPainter)
                    null -> {}
                }
            }
        }
    }
}

@Composable
fun HistoryItemRow(uiModel: HistoryUiDataModel, errorPainter: Painter) {
    val (title, icon) = getEventMetadata(uiModel)

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        // Light card style
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = Color.Black, modifier = Modifier.size(24.dp))

            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = title,
                    color = Color.Black,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${uiModel.timestamp.formatToTime()} • ${uiModel.entityName}",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (uiModel is HistoryUiDataModel.CameraEvent) {
                AsyncImage(
                    model = uiModel.mediaUrl.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(width = 80.dp, height = 60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.LightGray),
                    contentScale = ContentScale.Crop,
                    error = errorPainter
                )
            }
        }
    }
}

private fun getEventMetadata(model: HistoryUiDataModel): Pair<String, ImageVector> {
    return when (model) {
        is HistoryUiDataModel.CameraEvent -> {
            val title = when (model.eventType) {
                HistoryUiEventType.Person -> "Person"
                HistoryUiEventType.Motion -> "Motion"
                HistoryUiEventType.Doorbell -> "Doorbell"
                HistoryUiEventType.Vehicle -> "Vehicle"
                else -> "Unknown Camera Event"
            }
            title to getIconForType(model.eventType)
        }
        is HistoryUiDataModel.DefaultEvent -> {
            (model.eventName ?: "Activity") to Icons.Outlined.History
        }
    }
}

private fun getIconForType(type: HistoryUiEventType): ImageVector {
    return when (type) {
        HistoryUiEventType.Person -> Icons.Outlined.Person
        HistoryUiEventType.Doorbell -> Icons.Outlined.Doorbell
        HistoryUiEventType.Vehicle -> Icons.Outlined.DirectionsCar
        HistoryUiEventType.Motion -> Icons.Outlined.MotionPhotosOn
        else -> Icons.Outlined.Videocam
    }
}

@Composable
fun DateSeparatorRow(date: LocalDate, today: LocalDate) {
    val label = if (date == today) "Today" else if (date == today.minusDays(1)) "Yesterday" else date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
    Text(
        text = label,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.labelLarge,
        color = Color.Gray,
        fontWeight = FontWeight.Bold
    )
}

private fun Instant.formatToTime() =
    DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault()).format(this)
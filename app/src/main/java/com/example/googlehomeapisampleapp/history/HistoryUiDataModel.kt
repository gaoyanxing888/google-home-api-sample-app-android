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

import android.util.Log
import com.google.home.HistoryItem
import com.google.home.annotation.HomeExperimentalApi
import com.google.home.google.CameraHistory
import com.google.home.google.CameraHistoryTrait
import java.time.Instant
import java.time.LocalDate

sealed interface HistoryEventUi {
    val id: String
    data class DateSeparatorModel(val date: LocalDate) : HistoryEventUi {
        override val id: String = date.toString()
    }
}

sealed interface HistoryUiDataModel : HistoryEventUi {
    val eventId: String
    val timestamp: Instant
    val entityName: String
    override val id: String get() = eventId

    data class DefaultEvent(
        override val eventId: String,
        override val timestamp: Instant,
        override val entityName: String,
        val eventName: String? = null,
    ) : HistoryUiDataModel

    data class CameraEvent(
        override val eventId: String,
        override val timestamp: Instant,
        override val entityName: String,
        val eventType: HistoryUiEventType,
        val mediaUrl: MediaUrl,
        val deviceId: String,
    ) : HistoryUiDataModel
}

data class MediaUrl(
    val previewUrl: String = "",
    val thumbnailUrl: String = "",
) {
    companion object {
        fun fromCameraHistoryMediaUrl(mediaUrl: CameraHistoryTrait.MediaUrl?): MediaUrl {
            return MediaUrl(
                previewUrl = mediaUrl?.preview_url ?: "",
                thumbnailUrl = mediaUrl?.thumbnail_url ?: ""
            )
        }
    }
}

enum class HistoryUiEventType {
    Motion, Person, Doorbell, Animal, Vehicle, Unknown;

    companion object {
        fun fromEventType(eventType: CameraHistoryTrait.EventType): HistoryUiEventType = when (eventType) {
            CameraHistoryTrait.EventType.Motion -> Motion
            CameraHistoryTrait.EventType.Person -> Person
            CameraHistoryTrait.EventType.Doorbell -> Doorbell
            CameraHistoryTrait.EventType.Animal -> Animal
            CameraHistoryTrait.EventType.Vehicle -> Vehicle
            else -> Unknown
        }
    }
}

@OptIn(HomeExperimentalApi::class)
fun HistoryItem.toUiDataModel(): HistoryUiDataModel {
    val event = this.event
    return when (event) {
        is CameraHistory.HistoryItemEvent -> {
            val rawTracks = event.eventTracks?.flatMap { it.eventTypes }?.map { it.name } ?: emptyList()
            val sdkEventName = event.eventName ?: "Unnamed SDK Event"

            Log.i("CAMERA_DEBUG", "Camera Event: $sdkEventName | Device: $entityName")
            Log.d("CAMERA_DEBUG", "   -> Raw SDK Tracks: $rawTracks")

            val priorityList = listOf(
                CameraHistoryTrait.EventType.Doorbell,
                CameraHistoryTrait.EventType.Person,
                CameraHistoryTrait.EventType.Motion,
                CameraHistoryTrait.EventType.Vehicle,
                CameraHistoryTrait.EventType.Animal
            )
            val eventTypes = event.eventTracks?.flatMap { it.eventTypes }?.toSet()
            val detected = priorityList.firstOrNull { eventTypes?.contains(it) == true }
                ?: CameraHistoryTrait.EventType.Unknown

            HistoryUiDataModel.CameraEvent(
                eventId = id.id,
                timestamp = timestamp,
                entityName = entityName ?: "Camera",
                eventType = HistoryUiEventType.fromEventType(detected),
                mediaUrl = MediaUrl.fromCameraHistoryMediaUrl(event.mediaUrl),
                deviceId = entityId.id,
            )
        }
        else -> HistoryUiDataModel.DefaultEvent(
            eventId = id.id,
            timestamp = timestamp,
            entityName = entityName ?: "Event",
            eventName = event.eventName
        )
    }
}
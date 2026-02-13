/* Copyright 2025 Google LLC

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

package com.example.googlehomeapisampleapp.camera

import android.util.Log
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.googlehomeapisampleapp.camera.CameraStreamState.ERROR
import com.example.googlehomeapisampleapp.camera.CameraStreamState.INITIALIZED
import com.example.googlehomeapisampleapp.camera.CameraStreamState.NOT_STARTED
import com.example.googlehomeapisampleapp.camera.CameraStreamState.READY_OFF
import com.example.googlehomeapisampleapp.camera.CameraStreamState.READY_ON
import com.example.googlehomeapisampleapp.camera.CameraStreamState.STARTING
import com.example.googlehomeapisampleapp.camera.CameraStreamState.STOPPING
import com.example.googlehomeapisampleapp.camera.CameraStreamState.STREAMING_WITHOUT_TALKBACK
import com.example.googlehomeapisampleapp.camera.CameraStreamState.STREAMING_WITH_TALKBACK
import com.example.googlehomeapisampleapp.camera.livestreamplayer.CameraAvStreamManagementController
import com.example.googlehomeapisampleapp.camera.livestreamplayer.CameraAvStreamManagementControllerFactory
import com.example.googlehomeapisampleapp.camera.livestreamplayer.LiveStreamPlayer
import com.example.googlehomeapisampleapp.camera.livestreamplayer.LiveStreamPlayerFactory
import com.example.googlehomeapisampleapp.camera.livestreamplayer.OnOffController
import com.example.googlehomeapisampleapp.camera.livestreamplayer.OnOffControllerFactory
import com.example.googlehomeapisampleapp.doorbell.DoorbellChimeController
import com.example.googlehomeapisampleapp.doorbell.DoorbellChimeControllerFactory
import com.google.home.HomeDevice
import com.google.home.google.ChimeTrait
import com.google.home.google.GoogleDoorbellDevice
import com.google.home.matter.standard.RootNodeDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
open class CameraStreamViewModel @Inject internal constructor(
  private val liveStreamPlayerFactory: LiveStreamPlayerFactory,
  private val onOffControllerFactory: OnOffControllerFactory,
  private val cameraAvStreamManagementControllerFactory: CameraAvStreamManagementControllerFactory,
  private val doorbellChimeControllerFactory: DoorbellChimeControllerFactory,
) : ViewModel() {
  private val TAG = "CameraStreamViewModel"
  private val TOGGLE_WAIT_TIME = 4000L

  private var activeJobs = mutableListOf<Job>()
  private val deviceDeferred = CompletableDeferred<HomeDevice>()

  private val _uiMessage = MutableSharedFlow<String?>()
  val uiMessage: SharedFlow<String?> = _uiMessage.asSharedFlow()

  private val _liveStreamPlayer = MutableStateFlow<LiveStreamPlayer?>(null)
  private val _onOffController = MutableStateFlow<OnOffController?>(null)

  //Device info state
  private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
  val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo

  // Audio Controller Flow
  private val _cameraAvStreamManagementController = MutableStateFlow<CameraAvStreamManagementController?>(null)
  private val cameraAvStreamManagementController: StateFlow<CameraAvStreamManagementController?> = _cameraAvStreamManagementController

  private val _microphonePermissionGranted = MutableStateFlow(false)


  fun initialize(microphonePermissionGranted: Boolean) {
    // 1. Check if the permission status has actually changed
    if (_microphonePermissionGranted.value == microphonePermissionGranted) {
      return
    }

    // 2. Update the state
    _microphonePermissionGranted.value = microphonePermissionGranted
    Log.d(TAG, "Permission status changed to: $microphonePermissionGranted. Re-initializing.")

    // 3. Restart the stream to apply the hardware changes
    restartInitialization()
  }

  private val _isToggleAudioRecordingInProgress = MutableStateFlow(false)
  val isToggleAudioRecordingInProgress: StateFlow<Boolean> = _isToggleAudioRecordingInProgress

  // Doorbell Settings Controller
  private val _doorbellChimeController = MutableStateFlow<DoorbellChimeController?>(null)
  @OptIn(ExperimentalCoroutinesApi::class)
  val isDoorbellDevice: StateFlow<Boolean> = _onOffController
    .filterNotNull()
    .map {
      val device = deviceDeferred.await()
      val hasDoorbellTrait = device.has(GoogleDoorbellDevice)
      Log.d(TAG, "isDoorbellDevice Flow Logic -> hasDoorbellTrait: $hasDoorbellTrait")
      hasDoorbellTrait
    }.onEach { Log.d(TAG, "isDoorbellDevice Flow EMITTED: $it") }
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.Eagerly,
      initialValue = false
    )

  @OptIn(ExperimentalCoroutinesApi::class)
  val isIndoorChimeEnabled: StateFlow<Boolean> =
    _doorbellChimeController
      .flatMapLatest { it?.isChimeEnabled ?: flowOf(true) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)

  @OptIn(ExperimentalCoroutinesApi::class)
  val externalChimeType: StateFlow<ChimeTrait.ExternalChimeType> =
    _doorbellChimeController
      .flatMapLatest { it?.externalChimeType ?: flowOf(ChimeTrait.ExternalChimeType.Electronic) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), ChimeTrait.ExternalChimeType.Electronic)

  // --- UI State Flows ---
  @OptIn(ExperimentalCoroutinesApi::class)
  val isRecording: StateFlow<Boolean> = _onOffController
    .filterNotNull() // Wait until the controller actually exists
    .flatMapLatest { it.isRecording }
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = false // This will still be false until the first cloud sync
    )
  /** * Observe the Audio Recording state.
   * Hardware "Muted" = UI "Recording OFF", so we invert the boolean.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  val isAudioRecording: StateFlow<Boolean> = cameraAvStreamManagementController
    .flatMapLatest { controller ->
      // Reference mapping: Hardware Muted = UI OFF (Inverted)
      controller?.isRecordingMicrophoneMuted?.map { isMuted -> !isMuted } ?: flowOf(false)
    }
    .onEach { isEnabled ->
      Log.d("AUDIO_DEBUG", "OBSERVER: Cloud Trait Updated -> Recording Enabled = $isEnabled")
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

  // Add this init block or update your existing one to "unlock" the state
  private val isHardwareReady = MutableStateFlow(false)

  init {
    viewModelScope.launch {
      Log.d(TAG, "Hardware Stabilizing...")
      delay(3000)

      _onOffController.filterNotNull().first()

      isHardwareReady.value = true
      Log.d(TAG, "Hardware UNLOCKED - Now evaluating truth")

      _state.collect { currentState ->
        val nextState = handleCameraStreamState(currentState)
        if (nextState != currentState) {
          _state.value = nextState
        }
      }
    }
  }

  val isToggleRecordingInProgress = MutableStateFlow(false)
  val isToggleTalkbackInProgress = MutableStateFlow(false)

  @OptIn(ExperimentalCoroutinesApi::class)
  val isTalkbackEnabled: StateFlow<Boolean> = _liveStreamPlayer
    .flatMapLatest { it?.isTalkbackEnabled ?: flowOf(false) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

  val isTalkbackSupported: StateFlow<Boolean> = _liveStreamPlayer
    .map { it?.isTalkbackSupported == true }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

  private val _state = MutableStateFlow(NOT_STARTED)
  val state: StateFlow<CameraStreamState> = _state

  private var surface: Surface? = null

  // --- Commands ---

  fun setRecording(enabled: Boolean) {
    if (_state.value == NOT_STARTED) return
    val controller = _onOffController.value ?: return

    viewModelScope.launch {
      isToggleRecordingInProgress.value = true

      if (enabled) {
        // Clear any lingering sessions before turning back on
        stopPlayer()
        _state.value = INITIALIZED
      }

      val result = withTimeoutOrNull(TOGGLE_WAIT_TIME) {
        controller.setRecording(enabled)

        if (!enabled) {
          // If we're turning off, kill the player immediately
          // so the UI doesn't wait for a timeout
          stopPlayer()
        }

        // Wait for hardware to confirm it actually changed
        isRecording.first { it == enabled }
      }

      if (result == null) {
        Log.e(TAG, "Toggle timed out. Hardware is out of sync.")
      }

      if (enabled && result != null) {
        Log.d(TAG, "Toggle Success: Forcing READY_ON to start video")
        _state.value = READY_ON
      }

      isToggleRecordingInProgress.value = false
    }
  }

  fun setTalkback(enabled: Boolean) {
    Log.i(TAG, "setTalkback: Requesting Mic -> $enabled")
    val player = _liveStreamPlayer.value
    if (player == null) {
      Log.w(TAG, "setTalkback: Ignored, player is null (likely closing).")
      return
    }

    viewModelScope.launch {
      isToggleTalkbackInProgress.value = true
      try {
        player.toggleTalkback(enabled)
        // Wait for WebRTC hardware to confirm state change
        withTimeoutOrNull(2000) {
          isTalkbackEnabled.first { it == enabled }
        }
        _uiMessage.emit(if (enabled) "Microphone ON" else "Microphone OFF")
      } catch (e: Exception) {
        Log.e(TAG, "Talkback toggle failed: ${e.message}")
        _uiMessage.emit("Microphone error")
      } finally {
        isToggleTalkbackInProgress.value = false
      }
    }
  }

  /** Set audio recording on or off (microphone for clips). */
  /** * Writes the new Audio Recording state to the cloud.
   * Translates UI "Enabled" to Hardware "Not Muted".
   */
  fun setAudioRecording(enabled: Boolean) {
    val audioController = cameraAvStreamManagementController.value ?: return

    if (_state.value != STREAMING_WITH_TALKBACK && _state.value != STREAMING_WITHOUT_TALKBACK) {
      Log.w(TAG, "SDK Busy: Delaying audio write until stream is stable")
      // Optionally queue the action or show a 'Please wait' message
      return
    }
    viewModelScope.launch {
      _isToggleAudioRecordingInProgress.value = true
      try {
        val success = audioController.setRecordingMicrophoneMuted(!enabled)
        Log.d("USER_ACTION", "Write command sent. Success: $success")

        withTimeoutOrNull(TOGGLE_WAIT_TIME) {
          isAudioRecording.first { it == enabled }
        }
      } finally {
        _isToggleAudioRecordingInProgress.value = false
      }
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  fun setDevice(device: HomeDevice) {
    val currentDevice = if (deviceDeferred.isCompleted) deviceDeferred.getCompleted() else null
    if (currentDevice != null && currentDevice.id != device.id) {
      viewModelScope.launch {
        stopPlayer()
        setupDeviceResources(device, micGranted = true)
      }
      return
    }
    if (deviceDeferred.isCompleted) return
    deviceDeferred.complete(device)
    viewModelScope.launch { setupDeviceResources(device, micGranted = true) }
  }

  private suspend fun setupDeviceResources(device: HomeDevice, micGranted: Boolean): Boolean {
    activeJobs.forEach { it.cancel() }
    activeJobs.clear()
    stopPlayer()

    // Extract device information
    extractDeviceInfo(device)

    if (device.has(GoogleDoorbellDevice)) {
      _doorbellChimeController.value = doorbellChimeControllerFactory.create(device)
    }

    val controller = onOffControllerFactory.create(device)
    _onOffController.value = controller
    _cameraAvStreamManagementController.value = cameraAvStreamManagementControllerFactory.create(device)

    val isCameraActuallyOn = controller?.isRecording?.first() ?: false
    Log.d(TAG, "Verified hardware truth: $isCameraActuallyOn")

    val player = liveStreamPlayerFactory.createPlayerFromDevice(device, viewModelScope, micGranted)
    _liveStreamPlayer.value = player

    surface?.let {
      Log.d(TAG, "setupDeviceResources: Attaching surface to new player")
      player?.attachRenderer(it)
    }

    viewModelScope.launch {
      player?.state?.collect { playerInternalState ->
        if (playerInternalState.toString().contains("STREAMING", ignoreCase = true)) {
          val isTalkbackOn = player.isTalkbackEnabled.first()
          _state.value = if (isTalkbackOn) STREAMING_WITH_TALKBACK else STREAMING_WITHOUT_TALKBACK
        }
      }
    }.also { activeJobs.add(it) }

    viewModelScope.launch {
      controller?.isRecording?.collect { rec ->
        handleIsRecordingChange(rec)
      }
    }.also { activeJobs.add(it) }

    _state.value = INITIALIZED
    return true
  }

  //Extract device information
  private fun extractDeviceInfo(device: HomeDevice) {
    viewModelScope.launch {
      try {
        val deviceTypes = device.types().first()
        val rootNode = deviceTypes.filterIsInstance<RootNodeDevice>().firstOrNull()

        if (rootNode != null) {
          val basicInfo = rootNode.standardTraits.basicInformation

          if (basicInfo != null) {
            val vendorId = basicInfo.vendorId?.toInt() ?: 0
            val productId = basicInfo.productId?.toInt() ?: 0
            val productName = basicInfo.productName
            val vendorName = basicInfo.vendorName

            val model = if (productName != null && productName != "Unknown") {
              if (vendorName != null && vendorName != "Unknown Vendor") {
                "$vendorName $productName"
              } else {
                productName
              }
            } else {
              "VID: $vendorId, PID: $productId"
            }

            val finalSoftwareVersion = basicInfo.softwareVersionString
              ?: basicInfo.softwareVersion?.toString()
              ?: "Unknown"

            val finalHardwareVersion = basicInfo.hardwareVersionString
              ?: basicInfo.hardwareVersion?.toString()
              ?: "Unknown"

            _deviceInfo.value = DeviceInfo(
              model = model,
              softwareVersion = finalSoftwareVersion,
              hardwareVersion = finalHardwareVersion
            )
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to extract device info: ${e.message}")
      }
    }
  }

  private suspend fun handleCameraStreamState(currentState: CameraStreamState): CameraStreamState {
    Log.d(TAG, "State Machine Eval: $currentState")
    return when (currentState) {
      INITIALIZED -> {
        if (isToggleRecordingInProgress.value) return currentState

        val hardwareOn = isRecording.value
        Log.d(TAG, "State Machine Eval: Hardware is $hardwareOn")

        if (hardwareOn) READY_ON else READY_OFF
      }
      READY_ON -> {
        Log.d(TAG, "State Machine: READY_ON -> STARTING")
        if (startPlayer()) {
          STARTING
        } else {
          ERROR
        }
      }
      STOPPING -> {
        stopPlayer()
        READY_OFF // Clean landing on the "Camera is Off" screen
      }
      else -> currentState
    }
  }

  private fun handleIsRecordingChange(isRecording: Boolean) {
    viewModelScope.launch {
      if (!isHardwareReady.value) return@launch

      if (isRecording) {
        delay(1500) // Stabilization delay

        if (_liveStreamPlayer.value == null) {
          val device = deviceDeferred.await()
          setupDeviceResources(device, micGranted = true)
        }

        _state.value = READY_ON
      } else {
        stopPlayer()
      }
    }
  }

  private suspend fun startPlayer(): Boolean {
    // If the player is currently null, wait a brief moment for setupDeviceResources to finish
    var player = _liveStreamPlayer.value
    if (player == null) {
      Log.w(TAG, "startPlayer: Player null, attempting emergency setup")
      val device = deviceDeferred.await()
      setupDeviceResources(device, micGranted = true)
      player = _liveStreamPlayer.value
    }

    if (player == null) {
      Log.e(TAG, "startPlayer: Still no player instance after wait.")
      return false
    }

    surface?.let {
      Log.d(TAG, "startPlayer: Attaching surface")
      player.attachRenderer(it)
    }

    return try {
      player.start()
      true
    } catch (e: Exception) {
      Log.e(TAG, "startPlayer: Handshake failed: ${e.message}")
      false
    }
  }

  fun stopPlayerExternally() {
    viewModelScope.launch {
      stopPlayer()
    }
  }

  private suspend fun stopPlayer() {
    val player = _liveStreamPlayer.value ?: return

    withContext(NonCancellable) {
      try {
        Log.i(TAG, "stopPlayer: Killing jobs and disposing player")

        activeJobs.forEach { it.cancel() }
        activeJobs.clear()

        player.toggleTalkback(false)
        player.dispose()
      } catch (e: Exception) {
        Log.e(TAG, "stopPlayer error: ${e.message}")
      } finally {
        _liveStreamPlayer.value = null
        surface = null
        // This lands the UI on "Camera is Off"
        _state.value = READY_OFF
      }
    }
  }

  fun onSurfaceCreated(surface: Surface) {
    this.surface = surface
    _liveStreamPlayer.value?.attachRenderer(surface)
  }

  fun onSurfaceDestroyed() {
    _liveStreamPlayer.value?.detachRenderer()
    this.surface = null
  }

  fun restartInitialization() {
    Log.i(TAG, "restartInitialization: Manual hard-reset triggered.")
    viewModelScope.launch {
      stopPlayer()
      val device = deviceDeferred.await()
      // Use the actual permission state from the flow
      setupDeviceResources(device, micGranted = _microphonePermissionGranted.value)
    }
  }

  override fun onCleared() {
    Log.i(TAG, "ViewModel onCleared: Releasing all resources")
    super.onCleared()
    stopPlayerExternally()
  }

  // Expose whether the "Software Enable" attribute exists on this hardware
  @OptIn(ExperimentalCoroutinesApi::class)
  val isChimeToggleSupported: StateFlow<Boolean> = _doorbellChimeController
    .flatMapLatest { it?.isChimeToggleSupported ?: flowOf(false) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)


  fun toggleIndoorChime() {
    val controller = _doorbellChimeController.value ?: return
    val currentState = isIndoorChimeEnabled.value // Current truth

    viewModelScope.launch {
      // We calculate the inverse here (!currentState)
      val success = controller.setChimeEnabled(!currentState)

      if (success) {
        withTimeoutOrNull(TOGGLE_WAIT_TIME) {
          // Wait for the flow to reflect the change from the cloud
          isIndoorChimeEnabled.first { it == !currentState }
        }
      }
    }
  }

  fun setExternalChimeType(type: ChimeTrait.ExternalChimeType) {
    val controller = _doorbellChimeController.value ?: return
    viewModelScope.launch {
      Log.d(TAG, "Setting Physical Chime Type to: $type")
      controller.setExternalChimeType(type)
    }
  }
}

enum class CameraStreamState {
  NOT_STARTED,
  INITIALIZED,
  READY_OFF, // Camera is physically powered off
  READY_ON,  // Ready to start WebRTC session
  STARTING,  // Handshake in progress
  STREAMING_WITHOUT_TALKBACK,
  STREAMING_WITH_TALKBACK,
  STOPPING,
  ERROR,
}

data class DeviceInfo(
  val model: String,
  val softwareVersion: String,
  val hardwareVersion: String
)
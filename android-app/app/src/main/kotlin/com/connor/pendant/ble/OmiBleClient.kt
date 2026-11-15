package com.connor.pendant.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.UUID
/**
 * Minimal raw-Android BluetoothGatt wrapper for M1. No Nordic library yet — M2 swaps to
 * `no.nordicsemi.android.kotlin.ble` for clean coroutine semantics. For now we just need
 * to prove the GATT subscription works end-to-end.
 *
 * Battery-mode lifecycle: scan on explicit sync → connect → enable storage notify →
 * drain offline files → emit reconstructed raw frames → disconnect.
 */
@SuppressLint("MissingPermission")  // permissions checked at activity layer before construct
class OmiBleClient(private val context: Context) {

    enum class State { IDLE, SCANNING, CONNECTING, DISCOVERING, READY, SUBSCRIBED, SYNCING, DISCONNECTED }
    enum class FrameSource { LIVE, STORAGE }

    data class AudioFrame(
        val bytes: ByteArray,
        val source: FrameSource,
    )

    data class StorageFilePayload(
        val index: Int,
        val timestamp: Long,
        val size: Long,
        val frames: List<ByteArray>,
    )

    data class StorageSyncStatus(
        val active: Boolean = false,
        val filesTotal: Int = 0,
        val filesDone: Int = 0,
        val bytesDone: Long = 0,
        val bytesTotal: Long = 0,
        val framesDone: Long = 0,
        val cleanupActive: Boolean = false,
        val cleanupDeleted: Int = 0,
        val cleanupFailed: Int = 0,
        val cleanupLastIndex: Int? = null,
        val cleanupLastStatus: String? = null,
        val missingTimestampFiles: Int = 0,
        val lastCompletedAt: Long = 0L,
        val lastError: String? = null,
    )

    data class StorageStatsStatus(
        val active: Boolean = false,
        val proofActive: Boolean = false,
        val usedBytes: Long = -1L,
        val fileCount: Int = -1,
        val freeBytes: Long? = null,
        val statusFlags: Long = 0L,
        val previousUsedBytes: Long = -1L,
        val previousFileCount: Int = -1,
        val deltaBytes: Long? = null,
        val deltaFiles: Int? = null,
        val lastReadAt: Long = 0L,
        val lastError: String? = null,
        val message: String? = null,
        // ── the ring, in its own terms (packets, not bytes-of-files) ──
        val ringReadSeq: Long = -1L,
        val ringWriteSeq: Long = -1L,
        val ringCapacity: Long = 0L,
        val ringPacketBytes: Int = 0,
    )

    data class MicGainStatus(
        val value: Int? = null,
        val target: Int = CAPTURE_MIC_GAIN_LEVEL,
        val lastSetAt: Long = 0L,
        val lastError: String? = null,
    )

    enum class ButtonEvent(val code: Int, val label: String) {
        IDLE(0, "Idle"),
        SINGLE_TAP(1, "Single tap"),
        DOUBLE_TAP(2, "Double tap"),
        LONG_TAP(3, "Long press"),
        PRESS(4, "Pressed"),
        RELEASE(5, "Released"),
        TRIPLE_TAP(6, "Triple tap"),
        UNKNOWN(-1, "Unknown");

        companion object {
            fun fromCode(code: Int): ButtonEvent =
                values().firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    data class ButtonStatus(
        val event: ButtonEvent = ButtonEvent.IDLE,
        val rawCode: Int = 0,
        val receivedAt: Long = 0L,
        val subscribed: Boolean = false,
    )

    private val tag = "OmiBleClient"
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = btManager.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("omi_ble_client", Context.MODE_PRIVATE)

    private fun isDeviceActuallyConnected(): Boolean {
        val g = gatt ?: return false
        val device = g.device ?: return false
        return try {
            btManager.getConnectionState(device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }
    }

    private fun writeCharacteristicCompat(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, value, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = value
                ch.writeType = writeType
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error writing characteristic ${ch.uuid}: ${e.message}", e)
            false
        }
    }

    private fun writeDescriptorCompat(
        g: BluetoothGatt,
        d: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error writing descriptor ${d.uuid}: ${e.message}", e)
            false
        }
    }

    private var gatt: BluetoothGatt? = null
    private var scanGeneration = 0

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _codec = MutableStateFlow(OmiUuids.Codec.UNKNOWN)
    val codec: StateFlow<OmiUuids.Codec> = _codec.asStateFlow()

    private val _micGain = MutableStateFlow(MicGainStatus())
    val micGain: StateFlow<MicGainStatus> = _micGain.asStateFlow()

    private val _storageSync = MutableStateFlow(StorageSyncStatus())
    val storageSync: StateFlow<StorageSyncStatus> = _storageSync.asStateFlow()

    private val _storageStats = MutableStateFlow(StorageStatsStatus())
    val storageStats: StateFlow<StorageStatsStatus> = _storageStats.asStateFlow()

    private val _buttonStatus = MutableStateFlow(ButtonStatus())
    val buttonStatus: StateFlow<ButtonStatus> = _buttonStatus.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class SettingsTelemetry(
        val batteryVoltage: Int,
        val batteryPercentage: Int,
        val chargingState: Int,
        val sdBacklog: Long,
        val micStatus: Int,
        val vadStatus: Int,
        val codecStatus: Int,
        val sdStatus: Int
    )

    private val _settingsTelemetry = MutableStateFlow<SettingsTelemetry?>(null)
    val settingsTelemetry: StateFlow<SettingsTelemetry?> = _settingsTelemetry.asStateFlow()

    private val _buttonEvents = MutableSharedFlow<ButtonStatus>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val buttonEvents: SharedFlow<ButtonStatus> = _buttonEvents.asSharedFlow()

    /**
     * Live notifications are best-effort.  Storage records are NOT emitted on this flow at all: they are
     * handed to the safety checkpoint as a whole window and uploaded from the durable phone-local file.
     */
    private val _frames = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 256
    )
    val frames: SharedFlow<ByteArray> = _frames.asSharedFlow()

    private val _frameEvents = MutableSharedFlow<AudioFrame>(
        replay = 0,
        extraBufferCapacity = 256
    )
    val frameEvents: SharedFlow<AudioFrame> = _frameEvents.asSharedFlow()

    private var frameCount = 0L
    fun frameCount(): Long = frameCount
    private var droppedLiveFrames = 0L

    @Volatile var lastError: String? = null
        private set
    @Volatile var deviceAddr: String? = null
        private set
    @Volatile var mtu: Int = 0
        private set
    @Volatile var lastFrameSize: Int = 0
        private set

    private val _batteryPct = MutableStateFlow<Int?>(null)
    val batteryPct: StateFlow<Int?> = _batteryPct.asStateFlow()
    @Volatile var lastBatteryReadAt: Long = 0L
        private set
    @Volatile var lastRssi: Int? = null
        private set

    private var pendingStorageSync = false

    private var pendingStorageStatsRead = false
    private var storageStatsDisconnectAfterRead = false
    private var storageStatsProofStep = 0
    private var storageStatsProofGeneration = 0
    private var proofBaselineUsedBytes = -1L
    private var proofBaselineFileCount = -1
    private var proofWindowMs = STORAGE_PROOF_WINDOW_MS
    private var pendingLiveStream = false
    private var pendingButtonWatch = false
    private var pendingManualMicGain: Int? = null
    private var micGainWriteInFlight = false
    private var micGainWriteTarget = CAPTURE_MIC_GAIN_LEVEL
    private var afterMicGain = AfterMicGain.NONE
    private var storageNotifyEnabled = false
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val notifyEnabled = mutableSetOf<UUID>()
    private var notifyWriteInFlight = false
    // ── ring drain state (lives across BLE reconnects; the CURSOR is the progress) ─────────────
    private var ringReadSeq = -1L          // ring read cursor from the last INFO (-1 = unknown)
    private var ringWriteSeq = -1L         // where recorded audio ends (packets)
    private var ringCapacity = 0L
    private var ringPacketBytes = RING_PACKET_BYTES_DEFAULT
    private var ringId = 0L
    private var ringCaps = 0
    private var ringVersion = 0
    private var ringLastSeq = -1L          // last sequence RECEIVED — the resume point (persisted)
    private var ringSeqFloor = 0L          // sequence of the first record of the current window
    private var ringPacketIndex = 0L       // records carved in the current window
    private var ringDraining = false
    private var ringCustodyGranted = false
    private var ringWindow = ByteArrayOutputStream()
    private val ringFrames = ArrayList<ByteArray>()
    private var pendingAdvance: PendingAdvance? = null
    private var storageReadGeneration = 0
    private var lastStorageReadProgressAt = 0L
    private var storageCleanupDeleted = 0
    private var storageCleanupFailed = 0
    private var storageFileSafetyCheckpoint: (suspend (StorageFilePayload) -> Boolean)? = null
    private var storageRetryGeneration = 0
    private var storageRetryAttempts = 0

    private enum class AfterMicGain { NONE, STORAGE_SYNC, LIVE_STREAM, DISCONNECT }

    private data class PendingAdvance(
        val seq: Long,
        val onAck: (Int) -> Unit,
    )

    private fun u(value: Long): String = java.lang.Long.toUnsignedString(value)

    // ---- Scanning ----

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: return
            if (name !in OmiUuids.ADVERTISED_NAMES) return
            Log.i(tag, "Found pendant: name=$name addr=${device.address} rssi=${result.rssi}")
            deviceAddr = "${device.address} (rssi=${result.rssi})"
            prefs.edit().putString(PREF_LAST_DEVICE_ADDRESS, device.address.uppercase()).apply()
            lastRssi = result.rssi
            scanGeneration++
            adapter.bluetoothLeScanner?.stopScan(this)
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(tag, "Scan failed: $errorCode")
            scanGeneration++
            lastError = "Scan failed code=$errorCode"
            failStorageSyncIfActive(lastError ?: "Scan failed code=$errorCode")
            failStorageStatsIfActive(lastError ?: "Scan failed code=$errorCode")
            _state.value = State.IDLE
        }
    }

    fun startScan(useCachedAddress: Boolean = true) {
        if (_state.value != State.IDLE && _state.value != State.DISCONNECTED) return
        if (adapter == null) { lastError = "BluetoothAdapter null"; return }
        if (!adapter.isEnabled) { lastError = "Bluetooth is OFF"; return }
        if (useCachedAddress) {
            val cached = prefs.getString(PREF_LAST_DEVICE_ADDRESS, null)
            if (!cached.isNullOrBlank() && connectCachedAddress(cached)) return
        }
        val scanner = adapter.bluetoothLeScanner ?: run { lastError = "No BLE scanner"; return }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        _state.value = State.SCANNING
        val generation = ++scanGeneration
        scanner.startScan(null, settings, scanCallback)
        Log.i(tag, "Scanning broadly for ${OmiUuids.ADVERTISED_NAMES}")
        handler.postDelayed({
            if (scanGeneration == generation && _state.value == State.SCANNING) {
                adapter.bluetoothLeScanner?.stopScan(scanCallback)
                lastError = "Pendant scan timed out. Bring Omi nearby, keep it awake, then tap Reconnect Pendant."
                Log.w(tag, lastError ?: "Pendant scan timed out")
                failStorageSyncIfActive(lastError ?: "Pendant scan timed out")
                failStorageStatsIfActive(lastError ?: "Pendant scan timed out")
                _state.value = State.DISCONNECTED
            }
        }, SCAN_TIMEOUT_MS)
    }

    private fun connectCachedAddress(address: String): Boolean {
        val adapter = adapter ?: return false
        return try {
            val addr = address.uppercase()
            val device = if (Build.VERSION.SDK_INT >= 34) {
                adapter.getRemoteLeDevice(addr, BluetoothDevice.ADDRESS_TYPE_RANDOM)
            } else {
                adapter.getRemoteDevice(addr)
            }
            val generation = ++scanGeneration
            deviceAddr = "$addr (cached)"
            Log.i(tag, "Connecting to cached pendant address=$addr")
            connect(device, autoConnect = false)
            handler.postDelayed({
                if (scanGeneration == generation && _state.value == State.CONNECTING) {
                    Log.w(tag, "Cached pendant connect timed out; falling back to broad scan")
                    gatt?.disconnect()
                    gatt?.close()
                    gatt = null
                    _state.value = State.IDLE
                    startScan(useCachedAddress = false)
                }
            }, DIRECT_CONNECT_TIMEOUT_MS)
            true
        } catch (t: Throwable) {
            Log.w(tag, "Cached pendant address unusable: ${t.message}")
            false
        }
    }

    fun syncStorageNow() {
        syncStorageNowInternal(fromRetry = false)
    }

    private fun syncStorageNowInternal(fromRetry: Boolean) {
        if (!fromRetry) {
            storageRetryGeneration++
            storageRetryAttempts = 0
        }
        pendingStorageSync = true
        lastError = null
        resetStorageCleanupState()
        _storageSync.value = StorageSyncStatus(active = true)

        when (_state.value) {
            State.READY -> startStorageSyncAfterGain(gatt ?: return)
            State.IDLE, State.DISCONNECTED -> startScan()
            else -> Log.i(tag, "Storage sync queued while state=${_state.value}")
        }
    }

    fun probeStorageStats() {
        storageStatsProofStep = 0
        storageStatsProofGeneration++
        beginStorageStatsRead(
            disconnectAfterRead = _state.value in setOf(State.IDLE, State.DISCONNECTED),
            message = "Reading pendant flash counters",
        )
    }

    fun proveRecordingToStorage(windowMs: Long = STORAGE_PROOF_WINDOW_MS) {
        if (_state.value == State.SYNCING || _storageSync.value.active) {
            failStorageStats("Wait for the current storage sync to finish, then check recording proof.")
            return
        }
        if (_state.value == State.SUBSCRIBED || pendingLiveStream) {
            failStorageStats("Live capture is connected; the live frame counter is the proof. Stop Live to test offline flash growth.")
            return
        }

        val generation = ++storageStatsProofGeneration
        storageStatsProofStep = 1
        proofBaselineUsedBytes = -1L
        proofBaselineFileCount = -1
        proofWindowMs = windowMs.coerceIn(3_000L, 60_000L)
        _storageStats.value = StorageStatsStatus(
            active = true,
            proofActive = true,
            message = "Reading flash before sample",
        )
        beginStorageStatsRead(
            disconnectAfterRead = true,
            message = "Reading flash before sample",
            generation = generation,
        )
    }

    fun setStorageFileSafetyCheckpoint(checkpoint: (suspend (StorageFilePayload) -> Boolean)?) {
        storageFileSafetyCheckpoint = checkpoint
    }

    fun startLiveStream() {
        pendingLiveStream = true
        lastError = null

        when (_state.value) {
            State.READY, State.SUBSCRIBED -> startLiveStreamAfterGain(gatt ?: return)
            State.IDLE, State.DISCONNECTED -> startScan()
            else -> Log.i(tag, "Live stream queued while state=${_state.value}")
        }
    }

    fun setMicGain(level: Int) {
        val target = level.coerceIn(MIC_GAIN_MIN, MIC_GAIN_MAX)
        pendingManualMicGain = target
        lastError = null

        val g = gatt
        if (g != null && _state.value in setOf(State.READY, State.SUBSCRIBED, State.SYNCING)) {
            if (writeMicGain(g, target, AfterMicGain.DISCONNECT)) return
            pendingManualMicGain = null
            disconnect()
            return
        }

        when (_state.value) {
            State.IDLE, State.DISCONNECTED -> startScan()
            else -> Log.i(tag, "Mic gain=$target queued while state=${_state.value}")
        }
    }

    fun armButtonWatch() {
        pendingButtonWatch = true
        lastError = null

        when (_state.value) {
            State.READY, State.SUBSCRIBED, State.SYNCING -> queueButtonNotify(gatt ?: return)
            State.IDLE, State.DISCONNECTED -> startScan()
            else -> Log.i(tag, "Button watch queued while state=${_state.value}")
        }
    }

    fun stopLiveStream() {
        pendingLiveStream = false
        disconnect()
    }

    fun playHaptic(pulseType: Byte = 2): Boolean {
        val g = gatt ?: return false
        val service = g.getService(OmiUuids.HAPTIC_SERVICE) ?: run {
            Log.w(tag, "Haptic service not found")
            return false
        }
        val ch = service.getCharacteristic(OmiUuids.HAPTIC_CHAR) ?: run {
            Log.w(tag, "Haptic characteristic not found")
            return false
        }
        val success = writeCharacteristicCompat(
            g,
            ch,
            byteArrayOf(pulseType),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
        Log.i(tag, "Write haptic value=$pulseType success=$success")
        return success
    }

    fun stopScan() {
        scanGeneration++
        adapter.bluetoothLeScanner?.stopScan(scanCallback)
    }

    // ---- GATT connection ----

    private fun connect(device: BluetoothDevice, autoConnect: Boolean = false) {
        _state.value = State.CONNECTING
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, autoConnect, gattCallback)
        }
    }

    fun disconnect() {
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        pendingStorageSync = false
        pendingStorageStatsRead = false
        storageStatsDisconnectAfterRead = false
        pendingLiveStream = false
        pendingButtonWatch = false
        pendingManualMicGain = null
        micGainWriteInFlight = false
        afterMicGain = AfterMicGain.NONE
        storageNotifyEnabled = false
        notifyQueue.clear()
        notifyEnabled.clear()
        notifyWriteInFlight = false
        val button = _buttonStatus.value
        _buttonStatus.value = button.copy(subscribed = false)
        persistPartialRingWindow()
        ringFrames.clear()
        ringWindowReset()
        ringDraining = false
        failStorageSyncIfActive("BLE disconnected during storage sync")
        failStorageStatsIfActive("BLE disconnected during storage stats read")
        _state.value = State.DISCONNECTED
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(tag, "ConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = State.DISCOVERING
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.requestMtu(517)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val disconnectError = if (status != 0) {
                        "GATT disconnect status=$status"
                    } else {
                        "GATT disconnected during storage sync"
                    }
                    if (status != 0) lastError = disconnectError
                    _state.value = State.DISCONNECTED
                    failStorageSyncIfActive(disconnectError)
                    failStorageStatsIfActive(disconnectError)
                    pendingStorageSync = false
                    pendingStorageStatsRead = false
                    storageStatsDisconnectAfterRead = false
                    pendingLiveStream = false
                    pendingButtonWatch = false
                    pendingManualMicGain = null
                    micGainWriteInFlight = false
                    afterMicGain = AfterMicGain.NONE
                    storageNotifyEnabled = false
                    notifyQueue.clear()
                    notifyEnabled.clear()
                    notifyWriteInFlight = false
                    val button = _buttonStatus.value
                    _buttonStatus.value = button.copy(subscribed = false)
                    persistPartialRingWindow()
                    ringFrames.clear()
                    ringWindowReset()
                    ringDraining = false
                    g.close()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtuNegotiated: Int, status: Int) {
            Log.i(tag, "MTU negotiated: $mtuNegotiated (status=$status)")
            mtu = mtuNegotiated
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(tag, "Service discovery failed: $status")
                lastError = "Service discovery failed=$status"
                failStorageSyncIfActive(lastError ?: "Service discovery failed=$status")
                failStorageStatsIfActive(lastError ?: "Service discovery failed=$status")
                return
            }
            handler.postDelayed({
                if (gatt != g) return@postDelayed
                Log.i(tag, "Services discovered (${g.services.size}) - executing post-discovery actions")
                if (pendingStorageSync) {
                    startStorageSyncAfterGain(g)
                    return@postDelayed
                }

                _state.value = State.READY
                if (pendingStorageStatsRead && !pendingLiveStream) {
                    readStorageStats(g)
                    return@postDelayed
                }
                pendingManualMicGain?.let { target ->
                    if (writeMicGain(g, target, AfterMicGain.DISCONNECT)) return@postDelayed
                    pendingManualMicGain = null
                    disconnect()
                    return@postDelayed
                }

                val codecChar = g.getService(OmiUuids.AUDIO_SERVICE)
                    ?.getCharacteristic(OmiUuids.AUDIO_CODEC_CHAR)
                if (pendingLiveStream) {
                    startLiveStreamAfterGain(g)
                } else if (codecChar != null) {
                    val readSuccess = g.readCharacteristic(codecChar)
                    if (!readSuccess) {
                        Log.w(tag, "Failed to read codec characteristic; falling back to refreshBattery")
                        refreshBattery()
                    }
                } else {
                    if (pendingButtonWatch) {
                        queueButtonNotify(g)
                    }
                    refreshBattery()
                }
            }, 250)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (ch.uuid == OmiUuids.SETTINGS_MIC_GAIN_CHAR) {
                handleMicGainWrite(g, status)
            }
        }

        @Deprecated("Override for SDK 33+; old signature also called")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            handleCharRead(g, ch, ch.value, status)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharRead(g, ch, value, status)
        }

        private fun handleCharRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (ch.uuid == OmiUuids.AUDIO_CODEC_CHAR && status == BluetoothGatt.GATT_SUCCESS) {
                val codec = if (value.isNotEmpty()) OmiUuids.Codec.fromByte(value[0]) else OmiUuids.Codec.UNKNOWN
                _codec.value = codec
                Log.i(tag, "Audio codec: $codec (raw byte=0x${value.firstOrNull()?.toInt()?.and(0xFF)?.toString(16) ?: "?"})")
                if (pendingLiveStream) {
                    beginLiveStream(g)
                } else if (pendingButtonWatch) {
                    queueButtonNotify(g)
                } else {
                    refreshBattery()
                }
            } else if (ch.uuid == OmiUuids.BATTERY_LEVEL_CHAR && status == BluetoothGatt.GATT_SUCCESS) {
                val pct = if (value.isNotEmpty()) (value[0].toInt() and 0xFF) else -1
                if (pct in 0..100) {
                    _batteryPct.value = pct
                    lastBatteryReadAt = System.currentTimeMillis()
                    Log.i(tag, "Battery: $pct%")
                }
            } else if (ch.uuid == OmiUuids.STORAGE_READ_CHAR) {
                handleStorageStatsRead(g, value, status)
            }
        }

        @Deprecated("Override for SDK 33+; old signature also called")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic
        ) {
            handleNotify(ch.uuid, ch.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotify(ch.uuid, value)
        }

        private fun handleNotify(uuid: java.util.UUID, value: ByteArray) {
            when (uuid) {
                OmiUuids.AUDIO_DATA_CHAR -> {
                    frameCount++
                    lastFrameSize = value.size
                    if (frameCount % 100L == 0L) {
                        Log.d(tag, "frames=$frameCount last_size=${value.size} hdr=${value.take(3).joinToString("") { "%02x".format(it) }}")
                    }
                    emitLiveFrame(value)
                }
                OmiUuids.STORAGE_WRITE_CHAR -> handleStorageNotify(value)
                OmiUuids.BUTTON_STATE_CHAR -> handleButtonNotify(value)
                OmiUuids.SETTINGS_TELEMETRY_CHAR -> handleSettingsTelemetryNotify(value)
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == OmiUuids.CCC_DESCRIPTOR) {
                val chUuid = descriptor.characteristic?.uuid
                notifyWriteInFlight = false
                if (status == BluetoothGatt.GATT_SUCCESS && chUuid != null) {
                    notifyEnabled += chUuid
                } else {
                    lastError = "Notify enable failed status=$status"
                    Log.w(tag, "${lastError} char=$chUuid")
                    drainNotifyQueue(g)
                    return
                }
                if (chUuid == OmiUuids.STORAGE_WRITE_CHAR) {
                    storageNotifyEnabled = true
                    Log.i(tag, "Storage notifications enabled")
                    // Subscribing to 30295781 is itself what sets storage_is_on in the firmware — and the
                    // session then opens with CUSTODY_ENABLE, not a file-list request.
                    startRingSession(g)
                } else if (chUuid == OmiUuids.AUDIO_DATA_CHAR) {
                    _state.value = State.SUBSCRIBED
                    Log.i(tag, "Audio notifications enabled")
                    refreshBattery()
                } else if (chUuid == OmiUuids.BUTTON_STATE_CHAR) {
                    val current = _buttonStatus.value
                    _buttonStatus.value = current.copy(subscribed = true)
                    Log.i(tag, "Button notifications enabled")
                    if (notifyQueue.isEmpty()) refreshBattery()
                } else if (chUuid == OmiUuids.SETTINGS_TELEMETRY_CHAR) {
                    Log.i(tag, "Settings telemetry notifications enabled")
                    if (notifyQueue.isEmpty()) refreshBattery()
                } else {
                    _state.value = State.READY
                    refreshBattery()
                }
                drainNotifyQueue(g)
            }
        }
    }

    private fun startStorageSyncAfterGain(g: BluetoothGatt) {
        if (writeMicGain(g, CAPTURE_MIC_GAIN_LEVEL, AfterMicGain.STORAGE_SYNC)) return
        beginStorageSync(g)
    }

    private fun startLiveStreamAfterGain(g: BluetoothGatt) {
        if (writeMicGain(g, CAPTURE_MIC_GAIN_LEVEL, AfterMicGain.LIVE_STREAM)) return
        readCodecThenLive(g)
    }

    private fun readCodecThenLive(g: BluetoothGatt) {
        val codecChar = g.getService(OmiUuids.AUDIO_SERVICE)
            ?.getCharacteristic(OmiUuids.AUDIO_CODEC_CHAR)
        if (codecChar != null && g.readCharacteristic(codecChar)) return
        beginLiveStream(g)
    }

    private fun writeMicGain(g: BluetoothGatt, level: Int, after: AfterMicGain): Boolean {
        if (micGainWriteInFlight) {
            if (after != AfterMicGain.NONE) afterMicGain = after
            Log.i(tag, "Mic gain write already in flight; continuing after current write")
            return true
        }

        val target = level.coerceIn(MIC_GAIN_MIN, MIC_GAIN_MAX)
        val ch = g.getService(OmiUuids.SETTINGS_SERVICE)
            ?.getCharacteristic(OmiUuids.SETTINGS_MIC_GAIN_CHAR) ?: run {
                val error = "Mic gain characteristic not found"
                Log.w(tag, error)
                _micGain.value = _micGain.value.copy(target = target, lastError = error)
                return false
            }

        micGainWriteTarget = target
        afterMicGain = after
        micGainWriteInFlight = writeCharacteristicCompat(
            g,
            ch,
            byteArrayOf(target.toByte()),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
        if (!micGainWriteInFlight) {
            val error = "Failed to write mic gain=$target"
            afterMicGain = AfterMicGain.NONE
            Log.w(tag, error)
            _micGain.value = _micGain.value.copy(target = target, lastError = error)
            return false
        }

        Log.i(tag, "Setting mic gain=$target (${micGainLabel(target)})")
        return true
    }

    private fun handleMicGainWrite(g: BluetoothGatt, status: Int) {
        micGainWriteInFlight = false
        val target = micGainWriteTarget
        val next = afterMicGain
        afterMicGain = AfterMicGain.NONE
        pendingManualMicGain = null

        if (status == BluetoothGatt.GATT_SUCCESS) {
            _micGain.value = MicGainStatus(
                value = target,
                target = CAPTURE_MIC_GAIN_LEVEL,
                lastSetAt = System.currentTimeMillis(),
            )
            Log.i(tag, "Mic gain set to $target (${micGainLabel(target)})")
        } else {
            val error = "Mic gain write failed status=$status"
            _micGain.value = _micGain.value.copy(target = target, lastError = error)
            Log.w(tag, error)
        }

        when (next) {
            AfterMicGain.STORAGE_SYNC -> beginStorageSync(g)
            AfterMicGain.LIVE_STREAM -> readCodecThenLive(g)
            AfterMicGain.DISCONNECT -> disconnect()
            AfterMicGain.NONE -> Unit
        }
    }

    /**
     * Read the standard BLE Battery Level characteristic. Safe to call repeatedly;
     * does nothing if we're not connected. Updates [batteryPct] + [lastBatteryReadAt]
     * via the GATT callback path.
     */
    fun refreshBattery() {
        val g = gatt ?: return
        if (!isDeviceActuallyConnected()) {
            Log.w(tag, "refreshBattery: Device is not actually connected according to BluetoothManager. Forcing disconnect.")
            disconnect()
            return
        }
        val ch = g.getService(OmiUuids.BATTERY_SERVICE)
            ?.getCharacteristic(OmiUuids.BATTERY_LEVEL_CHAR) ?: run {
                Log.w(tag, "Battery service/char not found")
                return
            }
        try {
            val success = g.readCharacteristic(ch)
            if (!success) {
                Log.w(tag, "refreshBattery: readCharacteristic returned false")
            }
        } catch (e: Exception) {
            Log.e(tag, "refreshBattery error: ${e.message}", e)
            disconnect()
        }
    }

    private fun beginStorageStatsRead(
        disconnectAfterRead: Boolean,
        message: String,
        generation: Int = storageStatsProofGeneration,
    ) {
        if (_state.value == State.SYNCING || _storageSync.value.active) {
            failStorageStats("Wait for the current storage sync to finish, then check recording proof.")
            return
        }

        pendingStorageStatsRead = true
        storageStatsDisconnectAfterRead = disconnectAfterRead
        val current = _storageStats.value
        _storageStats.value = current.copy(
            active = true,
            proofActive = storageStatsProofStep > 0 || current.proofActive,
            lastError = null,
            message = message,
        )

        when (_state.value) {
            State.READY, State.SUBSCRIBED -> readStorageStats(gatt ?: return)
            State.IDLE, State.DISCONNECTED -> startScan()
            else -> Log.i(tag, "Storage stats read queued while state=${_state.value} generation=$generation")
        }
    }

    private fun readStorageStats(g: BluetoothGatt) {
        val ch = g.getService(OmiUuids.STORAGE_SERVICE)
            ?.getCharacteristic(OmiUuids.STORAGE_READ_CHAR) ?: run {
                failStorageStats("Storage stats characteristic not found")
                return
            }
        if (!g.readCharacteristic(ch)) {
            failStorageStats("Failed to read storage stats characteristic")
        }
    }

    /**
     * The status characteristic (30295782) read.  The firmware's own handler answers this READ with four
     * LITTLE-ENDIAN u32 — `used_bytes, unread_packets, free_bytes, rtc_valid` (storage.c:
     * storage_read_characteristic) — while some builds answer with the full 41-byte INFO block.  Both are
     * accepted here; the ring is named in ITS OWN terms (read_seq / write_seq / packets), not a file list.
     */
    private fun handleStorageStatsRead(g: BluetoothGatt, value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failStorageStats("Storage stats read failed status=$status")
            return
        }
        val usedBytes: Long
        val unreadPackets: Long
        when {
            value.size >= RingCommands.INFO_LEN -> {
                ringReadSeq = readBe64(value, 1)
                ringWriteSeq = readBe64(value, 9)
                ringCapacity = readBe32(value, 17)
                val pkt = readBe16(value, 29)
                ringPacketBytes = if (pkt in 16..2048) pkt else RING_PACKET_BYTES_DEFAULT
                ringCaps = value[31].toInt() and 0xFF
                ringVersion = value[32].toInt() and 0xFF
                ringId = readBe64(value, 33)
                unreadPackets = (ringWriteSeq - ringReadSeq).coerceAtLeast(0L)
                usedBytes = unreadPackets * ringPacketBytes.toLong()
            }
            value.size >= 16 -> {
                usedBytes = readU32Le(value, 0)
                unreadPackets = readU32Le(value, 4)
            }
            else -> {
                failStorageStats("Storage status payload too short (${value.size} bytes)")
                return
            }
        }
        val freeBytes = when {
            value.size >= RingCommands.INFO_LEN ->
                (ringCapacity - unreadPackets).coerceAtLeast(0L) * ringPacketBytes.toLong()
            value.size >= 12 -> readU32Le(value, 8)
            else -> null
        }
        val statusFlags = when {
            value.size >= RingCommands.INFO_LEN -> ringCaps.toLong()
            value.size >= 16 -> readU32Le(value, 12)
            else -> 0L
        }
        val fileCount = unreadPackets.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        val current = _storageStats.value
        val previousUsed = current.usedBytes
        val previousFiles = current.fileCount
        val deltaBytes = if (previousUsed >= 0L) usedBytes - previousUsed else null
        val deltaFiles = if (previousFiles >= 0) fileCount - previousFiles else null
        val now = System.currentTimeMillis()
        pendingStorageStatsRead = false

        val proofStep = storageStatsProofStep
        val message = when {
            proofStep == 1 -> "Baseline captured; waiting at least ${proofWindowMs / 1000L}s with BLE disconnected"
            proofStep == 2 && (deltaBytes ?: 0L) > 0L -> "Recording proof passed: ring grew by $deltaBytes bytes"
            proofStep == 2 -> "No ring growth detected during the proof window"
            deltaBytes != null && deltaBytes > 0L -> "Ring grew by $deltaBytes bytes since last check"
            else -> "Ring counters read"
        }

        _storageStats.value = current.copy(
            active = false,
            proofActive = proofStep == 1,
            usedBytes = usedBytes,
            fileCount = fileCount,
            freeBytes = freeBytes,
            statusFlags = statusFlags,
            previousUsedBytes = previousUsed,
            previousFileCount = previousFiles,
            deltaBytes = deltaBytes,
            deltaFiles = deltaFiles,
            lastReadAt = now,
            lastError = null,
            message = message,
            ringReadSeq = ringReadSeq,
            ringWriteSeq = ringWriteSeq,
            ringCapacity = ringCapacity,
            ringPacketBytes = ringPacketBytes,
        )
        Log.i(tag, "Ring stats used=$usedBytes packets=$fileCount unread=$unreadPackets deltaBytes=${deltaBytes ?: "n/a"}")

        if (proofStep == 1) {
            val generation = storageStatsProofGeneration
            proofBaselineUsedBytes = usedBytes
            proofBaselineFileCount = fileCount
            storageStatsProofStep = 2
            if (storageStatsDisconnectAfterRead && !pendingLiveStream) {
                storageStatsDisconnectAfterRead = false
                disconnect()
            }
            handler.postDelayed({
                if (storageStatsProofGeneration == generation && storageStatsProofStep == 2) {
                    beginStorageStatsRead(
                        disconnectAfterRead = true,
                        message = "Reading ring after sample",
                        generation = generation,
                    )
                }
            }, proofWindowMs)
            return
        }

        if (proofStep == 2) {
            storageStatsProofStep = 0
            proofBaselineUsedBytes = -1L
            proofBaselineFileCount = -1
            _storageStats.value = _storageStats.value.copy(proofActive = false)
        }

        if (storageStatsDisconnectAfterRead && !pendingLiveStream) {
            storageStatsDisconnectAfterRead = false
            disconnect()
        } else {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
        }
    }


    private fun beginStorageSync(g: BluetoothGatt) {
        pendingStorageSync = true
        _state.value = State.SYNCING
        resetStorageCleanupState()
        _storageSync.value = StorageSyncStatus(active = true)
        ringFrames.clear()
        ringWindowReset()
        ringDraining = false
        // The last sequence received survives a reconnect — load it if this process is fresh.
        if (ringLastSeq < 0L) ringLastSeq = prefs.getLong(PREF_RING_LAST_SEQ, -1L)
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        queueButtonNotify(g)
        queueTelemetryNotify(g)

        val ch = g.getService(OmiUuids.STORAGE_SERVICE)
            ?.getCharacteristic(OmiUuids.STORAGE_WRITE_CHAR) ?: run {
                completeStorageSync("Storage write/notify characteristic not found")
                return
            }
        enableNotify(g, ch)
    }

    private fun beginLiveStream(g: BluetoothGatt) {
        pendingLiveStream = true
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        queueButtonNotify(g)
        queueTelemetryNotify(g)
        val ch = g.getService(OmiUuids.AUDIO_SERVICE)
            ?.getCharacteristic(OmiUuids.AUDIO_DATA_CHAR) ?: run {
                lastError = "Audio data characteristic not found"
                Log.w(tag, lastError ?: "Audio data characteristic not found")
                return
            }
        enableNotify(g, ch)
    }

    private fun queueButtonNotify(g: BluetoothGatt) {
        val ch = g.getService(OmiUuids.BUTTON_SERVICE)
            ?.getCharacteristic(OmiUuids.BUTTON_STATE_CHAR) ?: run {
                Log.w(tag, "Button service/char not found")
                return
            }
        enableNotify(g, ch)
    }

    private fun queueTelemetryNotify(g: BluetoothGatt) {
        val ch = g.getService(OmiUuids.SETTINGS_SERVICE)
            ?.getCharacteristic(OmiUuids.SETTINGS_TELEMETRY_CHAR) ?: run {
                Log.w(tag, "Settings service/telemetry char not found")
                return
            }
        enableNotify(g, ch)
    }

    private fun handleSettingsTelemetryNotify(value: ByteArray) {
        if (value.size < 12) {
            Log.w(tag, "Settings telemetry size mismatch: expected 12, got ${value.size}")
            return
        }
        val voltage = ((value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8))
        val percentage = value[2].toInt() and 0xFF
        val charging = value[3].toInt() and 0xFF
        val backlog = (value[4].toLong() and 0xFF) or
                ((value[5].toLong() and 0xFF) shl 8) or
                ((value[6].toLong() and 0xFF) shl 16) or
                ((value[7].toLong() and 0xFF) shl 24)
        val mic = value[8].toInt() and 0xFF
        val vad = value[9].toInt() and 0xFF
        val codec = value[10].toInt() and 0xFF
        val sd = value[11].toInt() and 0xFF

        val telemetry = SettingsTelemetry(
            batteryVoltage = voltage,
            batteryPercentage = percentage,
            chargingState = charging,
            sdBacklog = backlog,
            micStatus = mic,
            vadStatus = vad,
            codecStatus = codec,
            sdStatus = sd
        )
        _settingsTelemetry.value = telemetry
        Log.i(tag, "Settings telemetry updated: $telemetry")
    }

    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        if (notifyEnabled.contains(ch.uuid) || notifyQueue.any { it.uuid == ch.uuid }) return
        notifyQueue.add(ch)
        drainNotifyQueue(g)
    }

    private fun drainNotifyQueue(g: BluetoothGatt) {
        if (notifyWriteInFlight) return
        val ch = notifyQueue.poll() ?: return
        g.setCharacteristicNotification(ch, true)
        val ccc = ch.getDescriptor(OmiUuids.CCC_DESCRIPTOR) ?: run {
            Log.e(tag, "CCC descriptor not found on ${ch.uuid}")
            return
        }
        notifyWriteInFlight = writeDescriptorCompat(g, ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (!notifyWriteInFlight) {
            lastError = "Failed to enable notifications for ${ch.uuid}"
            Log.w(tag, lastError ?: "Failed to enable notifications")
            drainNotifyQueue(g)
        }
    }

    private fun handleButtonNotify(value: ByteArray) {
        val raw = readI32Le(value)
        val event = ButtonEvent.fromCode(raw)
        val status = ButtonStatus(
            event = event,
            rawCode = raw,
            receivedAt = System.currentTimeMillis(),
            subscribed = true,
        )
        _buttonStatus.value = status
        _buttonEvents.tryEmit(status)
        Log.i(tag, "Button event ${event.label} raw=$raw bytes=${value.joinToString(" ") { "%02x".format(it) }}")
    }

    /**
     * Write a ring command to the control characteristic, retrying a transient failure.  A failed write
     * after 10 attempts is reported through the sync status unless the caller asked it not to be.
     */
    private fun writeStorageCommand(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        cmd: ByteArray,
        failSyncOnError: Boolean = true,
        retryCount: Int = 0,
        onComplete: (Boolean) -> Unit = {}
    ) {
        handler.post {
            if (gatt == null || gatt != g) {
                Log.w(tag, "Aborting writeStorageCommand: GATT connection changed or null")
                onComplete(false)
                return@post
            }
            val success = writeCharacteristicCompat(
                g,
                ch,
                cmd,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            if (!success) {
                if (retryCount < 10) {
                    Log.w(tag, "Failed to write ring command 0x${cmd.first().toInt().and(0xFF).toString(16)}, retrying (${retryCount + 1}/10)...")
                    handler.postDelayed({
                        writeStorageCommand(g, ch, cmd, failSyncOnError, retryCount + 1, onComplete)
                    }, 100)
                } else {
                    val message = "Failed to write ring command 0x${cmd.first().toInt().and(0xFF).toString(16)} after 10 attempts"
                    if (failSyncOnError) {
                        completeStorageSync(message)
                    } else {
                        Log.w(tag, message)
                    }
                    onComplete(false)
                }
            } else {
                onComplete(true)
            }
        }
    }

    private fun storageWriteChar(g: BluetoothGatt): BluetoothGattCharacteristic? =
        g.getService(OmiUuids.STORAGE_SERVICE)?.getCharacteristic(OmiUuids.STORAGE_WRITE_CHAR)

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    //  THE RING — a SEQUENCE drain, not a file list.
    //
    //  The pendant's firmware keeps stored audio in a RING BUFFER: a monotonic packet sequence and a
    //  reader cursor.  There are NO FILES.  Every command is `[opcode][args…]` with BIG-ENDIAN fields
    //  and NO epoch prefix; requests go to the control characteristic 30295781 and the answers come
    //  back on it as ACK / INFO / DATA / DONE / READ_BEGIN / LIVE_MARK.
    //
    //  So the unit of work is a SEQUENCE, not a file: the resume point across a reconnect is the last
    //  sequence received, and a completed window is committed with CMD_RING_ADVANCE.  The pendant
    //  drops the link every ~10-15 s, so a cursor — not a restart-from-zero — is what lets a backlog
    //  larger than one window ever finish.
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    /** Open a ring session: grant custody, then ask the ring for its INFO. */
    private fun startRingSession(g: BluetoothGatt) {
        val ch = storageWriteChar(g) ?: return completeStorageSync("Storage write characteristic not found")
        ringWindowReset()
        ringFrames.clear()
        ringDraining = false
        ringCustodyGranted = false
        lastStorageReadProgressAt = System.currentTimeMillis()
        // ⚡ CUSTODY FIRST — it is the gate.  storage_connection_changed() CLEARS custody on every
        // connect, so it must be re-sent per session.  Payload is EXACTLY 3 bytes: {opcode, version, caps}.
        Log.i(tag, "Ring: → CUSTODY_ENABLE (0x14) v${RingCommands.CUSTODY_VERSION} caps=0x%02x".format(RingCommands.CUSTODY_CAPS))
        writeStorageCommand(g, ch, RingCommands.custodyEnable(), failSyncOnError = true) { wrote ->
            if (!wrote) return@writeStorageCommand
            handler.postDelayed({
                if (gatt == g && pendingStorageSync) requestRingInfo(g)
            }, RING_CUSTODY_SETTLE_MS)
        }
    }

    private fun requestRingInfo(g: BluetoothGatt) {
        val ch = storageWriteChar(g) ?: return
        Log.i(tag, "Ring: → RING_INFO (0x10)")
        writeStorageCommand(g, ch, RingCommands.ringInfo(), failSyncOnError = true)
    }

    /** INFO reply — the 41-byte layout (storage.c:400-409).  This is the ONLY source of the cursor. */
    private fun handleRingInfo(value: ByteArray) {
        if (value.size < RingCommands.INFO_LEN) {
            Log.w(
                tag,
                "Ring INFO short: ${value.size} of ${RingCommands.INFO_LEN} bytes — " +
                    "the ATT MTU must be ≥ ${RingCommands.INFO_MIN_ATT_MTU} for the INFO notify to carry it"
            )
            return
        }
        ringReadSeq = readBe64(value, 1)
        ringWriteSeq = readBe64(value, 9)
        ringCapacity = readBe32(value, 17)
        val pkt = readBe16(value, 29)
        ringPacketBytes = if (pkt in 16..2048) pkt else RING_PACKET_BYTES_DEFAULT
        ringCaps = value[31].toInt() and 0xFF
        ringVersion = value[32].toInt() and 0xFF
        ringId = readBe64(value, 33)
        val unread = (ringWriteSeq - ringReadSeq).coerceAtLeast(0L)
        Log.i(
            tag,
            "Ring INFO read=${u(ringReadSeq)} write=${u(ringWriteSeq)} unread=$unread cap=${u(ringCapacity)} " +
                "pkt=$ringPacketBytes caps=0x%02x ver=$ringVersion ring=${u(ringId)}".format(ringCaps)
        )
        publishRingStats(unread)
        val s = _storageSync.value
        _storageSync.value = s.copy(
            active = true,
            filesTotal = maxOf(s.filesTotal, s.filesDone + unread.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
            bytesTotal = maxOf(s.bytesTotal, s.bytesDone + unread * ringPacketBytes.toLong()),
        )
        if (unread > 0L) beginRingRead(gatt ?: return)
        else completeStorageSync()
    }

    /** Send RING_READ: `[0x11][BE64 start_seq]` = 9 bytes.  The firmware reads `sys_get_be64(data+1)`. */
    private fun beginRingRead(g: BluetoothGatt) {
        val ch = storageWriteChar(g) ?: return completeStorageSync("Storage write characteristic not found")
        val start = ringResumeSeq()
        if (start < 0L) return completeStorageSync("Ring has no readable sequence")
        ringSeqFloor = start
        ringPacketIndex = 0
        ringDraining = true
        lastStorageReadProgressAt = System.currentTimeMillis()
        storageReadGeneration++
        scheduleStorageReadWatchdog(storageReadGeneration)
        Log.i(tag, "Ring: → RING_READ (0x11) from seq ${u(start)}")
        // ⚠️ From the CURSOR — NEVER a literal 0, and never a 6-byte [index,0,0,0,0] form: the 9-byte
        // length is enforced and a wrong length is the INVALID_COMMAND the old code kept getting.
        writeStorageCommand(g, ch, RingCommands.ringRead(start), failSyncOnError = true)
    }

    /**
     * The resume point, in the ring's own terms.  The firmware advances its read cursor as it SENDs, so
     * `read_seq` normally *is* the resume point across reconnects; [ringLastSeq] — the last sequence this
     * client actually received, persisted in prefs — only narrows it forward when a drop lands mid-window.
     * NEVER a literal 0 default.
     */
    private fun ringResumeSeq(): Long {
        val readSeq = ringReadSeq
        if (readSeq < 0L) return -1L
        val last = ringLastSeq
        return if (last in readSeq..ringWriteSeq) last else readSeq
    }

    /** DATA reply: `[0x03][raw ring bytes…]`.  Fragment boundaries are NOT record boundaries. */
    private fun handleRingData(value: ByteArray) {
        if (value.size <= 1) return
        if (!ringDraining) {
            Log.w(tag, "Ring DATA with no active window (${value.size} bytes) — ignoring rather than mis-framing")
            return
        }
        ringWindow.write(value, 1, value.size - 1)
        val received = (value.size - 1).toLong()

        // Carve complete records out of the fragment stream.  A stored record is a fixed 444-byte packet
        // (INFO.packet_bytes); the fragment is arbitrary, so we reassemble and slice.
        val all = ringWindow.toByteArray()
        var consumed = 0
        var carved = 0
        while (all.size - consumed >= ringPacketBytes) {
            ringFrames.add(all.copyOfRange(consumed, consumed + ringPacketBytes))
            consumed += ringPacketBytes
            carved++
        }
        if (carved > 0) {
            ringWindow.reset()
            if (consumed < all.size) ringWindow.write(all, consumed, all.size - consumed)
            ringPacketIndex += carved
            // ⇒ THE SEQUENCE ADVANCED.  This — not a byte count — is what restarts the idle timer and
            //   what makes the value the resume point across a reconnect.
            ringLastSeq = ringSeqFloor + ringPacketIndex - 1L
            saveRingLastSeq()
            lastStorageReadProgressAt = System.currentTimeMillis()
        }
        val s = _storageSync.value
        _storageSync.value = s.copy(
            bytesDone = s.bytesDone + received,
            filesDone = s.filesDone + carved,
            framesDone = s.framesDone + carved,
        )
    }

    /** DONE reply: `[0x04][status][BE64 next_seq]` — the transfer reached the write edge. */
    private fun handleRingDone(value: ByteArray) {
        val status = if (value.size >= 2) value[1].toInt() and 0xFF else RingCommands.ACK_OK
        val nextSeq = if (value.size >= 10) readBe64(value, 2) else ringLastSeq + 1L
        ringDraining = false
        Log.i(tag, "Ring: ← DONE status=${storageStatusLabel(status)} next_seq=${u(nextSeq)}")
        if (status != RingCommands.ACK_OK) {
            completeStorageSync("Ring read ended status=${storageStatusLabel(status)}")
            return
        }
        // A DONE that carried nothing AND moved nothing would loop forever (READ → DONE → ADVANCE →
        // INFO → READ …).  Treat it as a stalled window so the retry path — not a spin — handles it.
        if (ringFrames.isEmpty() && nextSeq <= ringSeqFloor) {
            completeStorageSync("Ring returned no progress at seq ${u(nextSeq)}")
            return
        }
        if (nextSeq > 0L && ringLastSeq < nextSeq - 1L) ringLastSeq = nextSeq - 1L
        // Persist what we hold FIRST (the phone is the durable side), then ADVANCE to commit it.
        flushRingWindow { ok ->
            if (!ok) return@flushRingWindow
            val g = gatt
            if (g == null) {
                completeStorageSync()
                return@flushRingWindow
            }
            // ADVANCE to the cursor the DONE named — everything through it is consumed, and the next
            // INFO decides whether the ring holds a further window.
            ringAdvance(g, nextSeq) { requestRingInfo(g) }
        }
    }

    /** ACK reply: `[0x01][status]` (2 bytes), or a custody reply `[0x01][0][granted]` (3 bytes). */
    private fun handleRingAck(value: ByteArray) {
        if (value.size >= 3 && value[1].toInt() == 0) {
            ringCustodyGranted = (value[2].toInt() and RingCommands.OMI_CAP_LIVE_PERSIST) != 0
            Log.i(tag, "Ring custody granted caps=0x%02x livePersist=$ringCustodyGranted".format(value[2].toInt() and 0xFF))
            return
        }
        val status = if (value.size >= 2) value[1].toInt() and 0xFF else RingCommands.ACK_OK
        val pending = pendingAdvance
        if (pending != null) {
            pendingAdvance = null
            if (status == RingCommands.ACK_OK) {
                storageCleanupDeleted++
                updateStorageCleanupStatus(0, true, storageStatusLabel(status), active = false)
            } else {
                storageCleanupFailed++
                updateStorageCleanupStatus(0, false, storageStatusLabel(status), active = false)
            }
            pending.onAck(status)
            return
        }
        if (status != RingCommands.ACK_OK) {
            Log.w(tag, "Ring ACK status=${storageStatusLabel(status)}")
            if (status == RingCommands.INVALID_COMMAND || status == RingCommands.SEQ_OUT_OF_RANGE ||
                status == RingCommands.RING_ID_MISMATCH
            ) {
                completeStorageSync("Ring refused a command: ${storageStatusLabel(status)}")
            }
        }
    }

    private fun ringAdvance(g: BluetoothGatt, seq: Long, onDone: () -> Unit) {
        val ch = storageWriteChar(g)
        if (ch == null || seq < 0L) {
            onDone()
            return
        }
        pendingAdvance = PendingAdvance(seq = seq, onAck = { onDone() })
        Log.i(tag, "Ring: → RING_ADVANCE (0x12) to seq ${u(seq)}")
        writeStorageCommand(g, ch, RingCommands.ringAdvance(seq), failSyncOnError = false) { wrote ->
            if (!wrote) {
                pendingAdvance = null
                storageCleanupFailed++
                updateStorageCleanupStatus(0, false, "LOCAL_WRITE_FAILED", active = false)
                onDone()
            }
        }
    }

    /**
     * Persist the records we hold to the phone BEFORE anything is reclaimed, then run them through the
     * safety checkpoint.  A drop must not discard audio that already reached us.
     */
    private fun flushRingWindow(onDone: (Boolean) -> Unit) {
        val frames = ArrayList(ringFrames)
        ringFrames.clear()
        ringWindowReset()
        if (frames.isEmpty()) {
            onDone(true)
            return
        }
        val size = frames.sumOf { it.size.toLong() }
        val ts = ringRecordTimestamp(frames.first())
        if (ts <= 0L) {
            val s = _storageSync.value
            _storageSync.value = s.copy(missingTimestampFiles = s.missingTimestampFiles + 1)
        }
        updateStorageCleanupStatus(0, null, "persisting ${frames.size} records", active = true)
        scope.launch {
            val ok = runStorageSafetyCheckpoint(frames, ts, size)
            handler.post {
                if (ok) Log.i(tag, "Ring window persisted frames=${frames.size} bytes=$size")
                else Log.w(tag, "Ring window NOT safe on phone frames=${frames.size} — pendant data left unreclaimed")
                onDone(ok)
            }
        }
    }

    /** The link dropped mid-drain: keep what already arrived; the ring's own cursor resumes us. */
    private fun persistPartialRingWindow() {
        if (!ringDraining && ringFrames.isEmpty()) return
        ringDraining = false
        flushRingWindow { }
    }

    /** A record's first 4 bytes ARE a big-endian timestamp (custody.h: omi_record_timestamp). */
    private fun ringRecordTimestamp(record: ByteArray): Long {
        if (record.size < 4) return 0L
        val raw = ((record[0].toLong() and 0xFF) shl 24) or ((record[1].toLong() and 0xFF) shl 16) or
            ((record[2].toLong() and 0xFF) shl 8) or (record[3].toLong() and 0xFF)
        // UTC seconds, or uptime seconds with bit 31 SET.  The store wants a plausible epoch; an uptime
        // flag or anything out of range becomes 0 (the store then uses sync time and flags it unverified).
        return if (raw in MIN_REASONABLE_EPOCH_SECONDS..MAX_REASONABLE_EPOCH_SECONDS) raw else 0L
    }

    private fun ringWindowReset() {
        ringWindow.reset()
        ringPacketIndex = 0
    }

    private fun saveRingLastSeq() {
        if (ringLastSeq >= 0L) prefs.edit().putLong(PREF_RING_LAST_SEQ, ringLastSeq).apply()
    }

    /** Map the ring (packets, not files) onto the status surface the UI already renders. */
    private fun publishRingStats(unreadPackets: Long) {
        val bytes = unreadPackets.coerceAtLeast(0L) * ringPacketBytes.toLong()
        val free = (ringCapacity - unreadPackets).coerceAtLeast(0L) * ringPacketBytes.toLong()
        val now = System.currentTimeMillis()
        val current = _storageStats.value
        val deltaBytes = if (current.usedBytes >= 0L) bytes - current.usedBytes else null
        _storageStats.value = current.copy(
            active = false,
            usedBytes = bytes,
            fileCount = unreadPackets.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            freeBytes = free,
            statusFlags = ringCaps.toLong(),
            previousUsedBytes = current.usedBytes,
            previousFileCount = current.fileCount,
            deltaBytes = deltaBytes,
            deltaFiles = null,
            lastReadAt = now,
            lastError = null,
            message = "ring read=${u(ringReadSeq)} write=${u(ringWriteSeq)} unread=$unreadPackets pkts @ ${ringPacketBytes}B",
            ringReadSeq = ringReadSeq,
            ringWriteSeq = ringWriteSeq,
            ringCapacity = ringCapacity,
            ringPacketBytes = ringPacketBytes,
        )
    }

    /** The idle watchdog.  It restarts on a SEQUENCE advance (a carved record), not on a byte count. */
    private fun scheduleStorageReadWatchdog(generation: Int) {
        handler.postDelayed({
            if (generation != storageReadGeneration) return@postDelayed
            if (!ringDraining) return@postDelayed
            if (!pendingStorageSync || _state.value != State.SYNCING) return@postDelayed
            val idleMs = System.currentTimeMillis() - lastStorageReadProgressAt
            if (idleMs >= STORAGE_READ_STALL_TIMEOUT_MS) {
                completeStorageSync(
                    "Ring read stalled ${idleMs / 1000}s at seq ${u(ringLastSeq)} " +
                        "(${_storageSync.value.filesDone} packets this session)"
                )
                return@postDelayed
            }
            scheduleStorageReadWatchdog(generation)
        }, STORAGE_READ_STALL_CHECK_MS)
    }

    private suspend fun runStorageSafetyCheckpoint(frames: List<ByteArray>, timestamp: Long, size: Long): Boolean {
        val checkpoint = storageFileSafetyCheckpoint
        if (checkpoint == null) {
            Log.w(tag, "Storage safety checkpoint missing; refusing pendant reclamation")
            return false
        }
        return try {
            checkpoint(
                StorageFilePayload(
                    index = 0,
                    timestamp = timestamp,
                    size = size,
                    frames = frames,
                )
            )
        } catch (t: Throwable) {
            Log.w(tag, "Storage safety checkpoint failed: ${t.message}")
            false
        }
    }

    /** Every reply from the ring is dispatched here by its 1-byte opcode. */
    private fun handleStorageNotify(value: ByteArray) {
        if (value.isEmpty()) return
        when (value[0].toInt() and 0xFF) {
            RingCommands.NOTIFY_ACK -> handleRingAck(value)
            RingCommands.NOTIFY_INFO -> handleRingInfo(value)
            RingCommands.NOTIFY_DATA -> handleRingData(value)
            RingCommands.NOTIFY_DONE -> handleRingDone(value)
            RingCommands.NOTIFY_READ_BEGIN -> Log.i(
                tag,
                "Ring: ← READ_BEGIN start=${if (value.size >= 9) u(readBe64(value, 1)) else "?"} " +
                    "count=${if (value.size >= 13) readBe32(value, 9) else 0L}"
            )
            RingCommands.NOTIFY_LIVE_MARK -> Log.i(tag, "Ring: ← LIVE_MARK — the pendant is flagging live audio on the ring")
            else -> Log.d(tag, "Ring: unhandled reply opcode 0x%02x (${value.size} bytes)".format(value[0].toInt() and 0xFF))
        }
    }

    private fun emitLiveFrame(frame: ByteArray) {
        val copy = frame.copyOf()
        _frameEvents.tryEmit(AudioFrame(copy, FrameSource.LIVE))
        if (_frames.tryEmit(copy)) return
        droppedLiveFrames++
        if (droppedLiveFrames < 10L || droppedLiveFrames % 100L == 0L) {
            Log.w(tag, "live frame flow full; dropped live frame (#$droppedLiveFrames)")
        }
    }

    private fun completeStorageSync(error: String? = null) {
        val now = System.currentTimeMillis()
        val current = _storageSync.value
        val shouldRetry = error?.let(::isRetryableStorageSyncError) == true
        if (error != null) {
            lastError = error
            Log.w(tag, "Storage sync failed: $error")
        } else {
            storageRetryAttempts = 0
            Log.i(
                tag,
                "Storage sync complete files=${current.filesDone}/${current.filesTotal} bytes=${current.bytesDone}/${current.bytesTotal} frames=${current.framesDone}"
            )
        }
        Log.i(tag, "Storage cleanup complete deleted=$storageCleanupDeleted failed=$storageCleanupFailed")
        _storageSync.value = current.copy(
            active = false,
            cleanupActive = false,
            cleanupDeleted = storageCleanupDeleted,
            cleanupFailed = storageCleanupFailed,
            lastCompletedAt = now,
            lastError = error,
        )
        pendingStorageSync = false
        storageNotifyEnabled = false
        ringFrames.clear()
        ringWindowReset()
        ringDraining = false
        storageReadGeneration++
        pendingAdvance = null
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
        disconnect()
        if (shouldRetry) scheduleStorageSyncRetry(error ?: "storage sync retry")
    }

    private fun failStorageSyncIfActive(error: String) {
        val current = _storageSync.value
        if (!current.active) return
        lastError = error
        pendingStorageSync = false
        storageNotifyEnabled = false
        pendingAdvance = null
        _storageSync.value = current.copy(
            active = false,
            cleanupActive = false,
            cleanupDeleted = storageCleanupDeleted,
            cleanupFailed = storageCleanupFailed,
            lastCompletedAt = System.currentTimeMillis(),
            lastError = error,
        )
        ringFrames.clear()
        ringWindowReset()
        ringDraining = false
        storageReadGeneration++
        pendingAdvance = null
        if (isRetryableStorageSyncError(error)) {
            disconnect()
            scheduleStorageSyncRetry(error)
        }
    }

    private fun isRetryableStorageSyncError(error: String): Boolean =
        error.contains("STORAGE_NOT_READY", ignoreCase = true) ||
            error.contains("scan timed out", ignoreCase = true) ||
            // The pendant naps after ~10 s of quiet and a drain link drops every ~10-15 s — both are the
            // normal cost of draining a RING, so a stalled or dropped window must RETRY, not end the sync.
            error.contains("stalled", ignoreCase = true) ||
            error.contains("disconnected during", ignoreCase = true) ||
            error.contains("no progress", ignoreCase = true)

    private fun scheduleStorageSyncRetry(reason: String) {
        val generation = storageRetryGeneration
        storageRetryAttempts += 1
        val delayMs = STORAGE_RETRY_DELAY_MS
        Log.w(tag, "Storage sync will retry in ${delayMs / 1000}s after: $reason")
        handler.postDelayed({
            if (generation != storageRetryGeneration) return@postDelayed
            if (_storageSync.value.active || pendingLiveStream || pendingButtonWatch) return@postDelayed
            Log.i(tag, "Retrying storage sync attempt=$storageRetryAttempts")
            syncStorageNowInternal(fromRetry = true)
        }, delayMs)
    }

    private fun failStorageStatsIfActive(error: String) {
        val current = _storageStats.value
        if (!current.active) return
        failStorageStats(error)
    }

    private fun failStorageStats(error: String) {
        val current = _storageStats.value
        lastError = error
        pendingStorageStatsRead = false
        storageStatsDisconnectAfterRead = false
        storageStatsProofStep = 0
        proofBaselineUsedBytes = -1L
        proofBaselineFileCount = -1
        _storageStats.value = current.copy(
            active = false,
            proofActive = false,
            lastError = error,
            message = error,
        )
        Log.w(tag, "Storage stats failed: $error")
    }

    // ── big-endian reads (every ring field is big-endian; storage.c uses sys_get_be*()) ─────────────
    private fun readBe64(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes.getOrElse(offset + i) { 0 }.toLong() and 0xFF)
        return v
    }

    private fun readBe32(bytes: ByteArray, offset: Int): Long =
        ((bytes.getOrElse(offset) { 0 }.toLong() and 0xFF) shl 24) or
            ((bytes.getOrElse(offset + 1) { 0 }.toLong() and 0xFF) shl 16) or
            ((bytes.getOrElse(offset + 2) { 0 }.toLong() and 0xFF) shl 8) or
            (bytes.getOrElse(offset + 3) { 0 }.toLong() and 0xFF)

    private fun readBe16(bytes: ByteArray, offset: Int): Int =
        ((bytes.getOrElse(offset) { 0 }.toInt() and 0xFF) shl 8) or
            (bytes.getOrElse(offset + 1) { 0 }.toInt() and 0xFF)

    private fun readU32Le(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0L
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun readI32Le(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        if (bytes.size < 4) return bytes[0].toInt() and 0xFF
        return (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 24)
    }

    private fun storageStatusLabel(status: Int): String = when (status) {
        RingCommands.ACK_OK -> "OK(0)"
        RingCommands.INVALID_COMMAND -> "INVALID_COMMAND(6)"
        RingCommands.STORAGE_NOT_READY -> "STORAGE_NOT_READY(9)"
        RingCommands.SEQ_OUT_OF_RANGE -> "SEQ_OUT_OF_RANGE(10)"
        RingCommands.RING_ID_MISMATCH -> "RING_ID_MISMATCH(11)"
        RingCommands.DEFERRED -> "DEFERRED(255)"
        else -> "UNKNOWN($status)"
    }

    private fun resetStorageCleanupState() {
        storageCleanupDeleted = 0
        storageCleanupFailed = 0
    }

    private fun updateStorageCleanupStatus(
        index: Int,
        deletedOk: Boolean?,
        status: String,
        active: Boolean,
    ) {
        val current = _storageSync.value
        val deleted = when (deletedOk) {
            true -> storageCleanupDeleted
            else -> current.cleanupDeleted
        }
        val failed = when (deletedOk) {
            false -> storageCleanupFailed
            else -> current.cleanupFailed
        }
        _storageSync.value = current.copy(
            cleanupActive = active,
            cleanupDeleted = deleted,
            cleanupFailed = failed,
            cleanupLastIndex = index,
            cleanupLastStatus = status,
        )
    }

    companion object {
        // ── the ring — opcodes/statuses live in RingCommands; these are pacing knobs ─────────────────
        private const val RING_PACKET_BYTES_DEFAULT = 444   // RAW_AUDIO_PACKET_BYTES; INFO's value wins
        private const val RING_CUSTODY_SETTLE_MS = 600L     // let CUSTODY_ENABLE be processed first
        private const val STORAGE_READ_STALL_CHECK_MS = 5_000L
        private const val STORAGE_READ_STALL_TIMEOUT_MS = 20_000L
        private const val MIN_REASONABLE_EPOCH_SECONDS = 1_500_000_000L
        private const val MAX_REASONABLE_EPOCH_SECONDS = 4_102_444_800L
        private const val STORAGE_PROOF_WINDOW_MS = 15_000L
        private const val SCAN_TIMEOUT_MS = 60_000L
        private const val DIRECT_CONNECT_TIMEOUT_MS = 20_000L
        private const val STORAGE_RETRY_DELAY_MS = 30_000L
        private const val PREF_LAST_DEVICE_ADDRESS = "last_device_address"
        private const val PREF_RING_LAST_SEQ = "ring_last_seq"
        private const val MIC_GAIN_MIN = 0
        private const val MIC_GAIN_MAX = 8
        // Firmware level 8 maps to hardware gain byte 0x50, the max PDM gain.
        const val CAPTURE_MIC_GAIN_LEVEL = 8

        fun micGainLabel(level: Int): String = when (level) {
            0 -> "Mute"
            1 -> "-20dB"
            2 -> "-10dB"
            3 -> "+0dB"
            4 -> "+6dB"
            5 -> "+10dB"
            6 -> "+20dB"
            7 -> "+30dB"
            8 -> "+40dB"
            else -> "unknown"
        }
    }
}

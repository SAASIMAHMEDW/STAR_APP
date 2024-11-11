package com.example.star

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.star.databinding.ActivityMainBinding
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Method
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val markersList = mutableListOf<Map<String, Any>>() // List to hold the fetched data

    // UI Elements
    private lateinit var connectionStatusTextView: TextView
    private lateinit var receiverTextView: TextView
    private lateinit var messageEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var macAddressEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var connectionSection: LinearLayout
    private lateinit var communicationSection: LinearLayout
    private lateinit var receivedMessagesScrollView: ScrollView

    // Bluetooth Components
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var connectedDevice: BluetoothDevice? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var isConnected = false

    // Handler for updating UI from threads
    private val handler = Handler(Looper.getMainLooper())

    // UUID for SPP
    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Activity Result Launchers
    private lateinit var enableBtLauncher: ActivityResultLauncher<Intent>
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>

    // Buffer for incoming messages
    private var incomingMessageBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize UI Elements
        connectionStatusTextView = binding.connectionStatusTextView
        receiverTextView = binding.receiverTextView
        messageEditText = binding.messageEditText
        sendButton = binding.sendButton
        disconnectButton = binding.disconnectButton
        macAddressEditText = binding.macAddressEditText
        connectButton = binding.connectButton
        connectionSection = binding.connectionSection
        communicationSection = binding.communicationSection
        receivedMessagesScrollView = binding.receivedMessagesScrollView

        // Initialize Bluetooth Adapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        // Set initial UI state
        setUIState(disconnected = true)

        // Initialize Activity Result Launchers
        initActivityResultLaunchers()

        // Register Receiver for Bluetooth State Changes
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        // Set up Button Click Listeners
//        setupButtonListeners()
        connectButton.setOnClickListener{
            startActivity(Intent(this, HomeActivity::class.java))
        }
        // Check and Request Bluetooth Permissions
        if (hasBluetoothPermissions()) {
            initializeBluetooth()
        } else {
            requestBluetoothPermissions()
        }

        // Fetch data from Firestore
        getData()
    }

    private fun setUIState(disconnected: Boolean) {
        sendButton.isEnabled = !disconnected
        disconnectButton.isEnabled = !disconnected
        communicationSection.visibility = if (disconnected) View.GONE else View.VISIBLE
        connectionSection.visibility = if (disconnected) View.VISIBLE else View.GONE
        messageEditText.visibility = if (disconnected) View.GONE else View.VISIBLE
    }

    private fun initActivityResultLaunchers() {
        // Launcher for enabling Bluetooth
        enableBtLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val macAddress = macAddressEditText.text.toString().trim()
                if (macAddress.isNotEmpty()) {
                    connectToTargetDevice(macAddress)
                } else {
                    showToast("Please enter a MAC address to connect")
                }
            } else {
                showToast("Bluetooth is required to use this app")
                finish()
            }
        }

        // Launcher for requesting permissions
        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.values.all { it }) {
                initializeBluetooth()
            } else {
                showToast("Bluetooth permissions are required to use this app")
                finish()
            }
        }
    }

    private fun setupButtonListeners() {
        connectButton.setOnClickListener {
            val macAddress = macAddressEditText.text.toString().trim()
            if (macAddress.isNotEmpty()) {
                if (bluetoothAdapter?.isEnabled == true) {
                    connectToTargetDevice(macAddress)
                } else {
                    enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            } else {
                showToast("Please enter a MAC address to connect")
            }
        }

        disconnectButton.setOnClickListener { disconnectFromDevice() }

        sendButton.setOnClickListener {
            val message = messageEditText.text.toString()
            if (message.isNotBlank()) {
                sendMessage(message)
            } else {
                showToast("Please enter a message to send")
            }
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> {
                    showToast("Bluetooth Turned Off")
                    resetConnectionUI()
                }
                BluetoothAdapter.STATE_ON -> {
                    showToast("Bluetooth Turned On")
                    val macAddress = macAddressEditText.text.toString().trim()
                    if (macAddress.isNotEmpty()) connectToTargetDevice(macAddress)
                }
            }
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        requestPermissionLauncher.launch(permissions)
    }

    private fun initializeBluetooth() {
        if (bluetoothAdapter == null) {
            showToast("Bluetooth is not supported on this device")
            finish()
            return
        }
        if (!bluetoothAdapter!!.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    private fun connectToTargetDevice(macAddress: String) {
        if (isConnected) {
            showToast("Already connected to a device. Please disconnect first.")
            return
        }

        val device: BluetoothDevice? = try {
            bluetoothAdapter?.getRemoteDevice(macAddress)
        } catch (e: IllegalArgumentException) {
            showToast("Invalid MAC address format.")
            Log.e("Bluetooth", "Invalid MAC address: $macAddress")
            null
        }

        device?.let {
            val socket = try {
                bluetoothAdapter?.cancelDiscovery()
                device.createInsecureRfcommSocketToServiceRecord(uuid)
            } catch (e: Exception) {
                createRfcommSocketUsingReflection(device)
            }

            bluetoothSocket = socket
            socket?.let { connectSocket(it, device) } ?: showToast("Could not create socket.")
        } ?: showToast("Target device not found.")
    }

    private fun createRfcommSocketUsingReflection(device: BluetoothDevice): BluetoothSocket? {
        return try {
            val method: Method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
            method.invoke(device, 1) as BluetoothSocket
        } catch (e: Exception) {
            Log.e("Bluetooth", "Reflection socket creation failed: ${e.message}")
            null
        }
    }

    private fun connectSocket(socket: BluetoothSocket, device: BluetoothDevice) {
        Thread {
            try {
                handler.post { connectionStatusTextView.text = "Connecting to ${device.name}..." }
                socket.connect()
                connectedDevice = device
                isConnected = true
                handler.post {
                    setUIState(disconnected = false)
                    connectionStatusTextView.text = "Connected to ${device.name}"
                    showToast("Connected to ${device.name}")
                }
                startListeningForMessages(socket)
            } catch (e: IOException) {
                Log.e("Bluetooth", "Connection failed: ${e.message}")
                closeSocket()
                handler.post {
                    showToast("Failed to connect to ${device.name}")
                    resetConnectionUI()
                }
            }
        }.start()
    }

    private fun closeSocket() {
        try {
            bluetoothSocket?.close()
            bluetoothSocket = null
            isConnected = false
        } catch (e: IOException) {
            Log.e("Bluetooth", "Socket closure failed: ${e.message}")
        }
    }

    private fun startListeningForMessages(socket: BluetoothSocket) {
        val inputStream = try {
            socket.inputStream
        } catch (e: IOException) {
            Log.e("Bluetooth", "Could not get input stream: ${e.message}")
            null
        }

        inputStream?.let {
            val buffer = ByteArray(1024)
            while (isConnected) {
                try {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val receivedMessage = String(buffer, 0, bytesRead)
                        incomingMessageBuffer.append(receivedMessage)
                        handler.post {
                            receiverTextView.text = incomingMessageBuffer.toString()
                            receivedMessagesScrollView.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                } catch (e: IOException) {
                    Log.e("Bluetooth", "Message reception failed: ${e.message}")
                    handler.post { showToast("Connection lost") }
                    disconnectFromDevice()
                }
            }
        }
    }

    private fun sendMessage(message: String) {
        bluetoothSocket?.let { socket ->
            val outputStream: OutputStream? = try {
                socket.outputStream
            } catch (e: IOException) {
                Log.e("Bluetooth", "Failed to get output stream: ${e.message}")
                null
            }

            outputStream?.let {
                try {
                    it.write(message.toByteArray())
                    handler.post { messageEditText.text.clear() }
                } catch (e: IOException) {
                    Log.e("Bluetooth", "Failed to send message: ${e.message}")
                    handler.post { showToast("Failed to send message") }
                }
            }
        } ?: showToast("No connected device found.")
    }

    private fun disconnectFromDevice() {
        closeSocket()
        resetConnectionUI()
        showToast("Disconnected from device")
    }

    private fun resetConnectionUI() {
        setUIState(disconnected = true)
        connectionStatusTextView.text = "Not connected"
    }

    private fun getData() {
        val db = Firebase.firestore
        db.collection("markers")
            .get()
            .addOnSuccessListener { result ->
                markersList.clear()
                for (document in result) {
                    document.data.let { markersList.add(it) }
                }
                showToast("Data fetched from Firestore")
            }
            .addOnFailureListener { e ->
                Log.e("Firestore", "Error fetching data: ${e.message}")
                showToast("Failed to fetch data from Firestore")
            }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothStateReceiver)
        disconnectFromDevice()
    }
}

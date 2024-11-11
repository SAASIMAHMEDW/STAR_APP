package com.example.star

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.star.databinding.ActivityMainBinding
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)

//        binding.connectButton.setOnClickListener {
//            startActivity(Intent(this, HomeActivity::class.java))
//        }

        // Initialize UI Elements
        connectionStatusTextView = findViewById(R.id.connectionStatusTextView)
        receiverTextView = findViewById(R.id.receiverTextView)
        messageEditText = findViewById(R.id.messageEditText)
        sendButton = findViewById(R.id.sendButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        macAddressEditText = findViewById(R.id.macAddressEditText)
        connectButton = findViewById(R.id.connectButton)
        connectionSection = findViewById(R.id.connectionSection)
        communicationSection = findViewById(R.id.communicationSection)
        receivedMessagesScrollView = findViewById(R.id.receivedMessagesScrollView)

        // Initialize Bluetooth Adapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        // Initialize UI State
        sendButton.isEnabled = false
        disconnectButton.isEnabled = false
        communicationSection.visibility = View.GONE

        // Initialize Activity Result Launchers
        initActivityResultLaunchers()

        // Register Receiver for Bluetooth State Changes
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        registerReceiver(bluetoothStateReceiver, filter)

        // Set up Button Click Listeners
        setupButtonListeners()

        // Check and Request Bluetooth Permissions
        if (hasBluetoothPermissions()) {
            initializeBluetooth()
        } else {
            requestBluetoothPermissions()
        }
        getData()

    }

    /**
     * Initialize Activity Result Launchers for enabling Bluetooth and requesting permissions.
     */
    private fun initActivityResultLaunchers() {
        // Launcher for enabling Bluetooth
        enableBtLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // Bluetooth enabled, attempt to connect if MAC address is provided
                val macAddress = macAddressEditText.text.toString().trim()
                if (macAddress.isNotEmpty()) {
                    connectToTargetDevice(macAddress)
                } else {
                    Toast.makeText(this, "Please enter a MAC address to connect", Toast.LENGTH_SHORT).show()
                }
            } else {
                // User denied to enable Bluetooth
                Toast.makeText(this, "Bluetooth is required to use this app", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        // Launcher for requesting permissions
        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            var allGranted = true
            permissions.entries.forEach {
                if (!it.value) {
                    allGranted = false
                }
            }

            if (allGranted) {
                initializeBluetooth()
            } else {
                Toast.makeText(this, "Bluetooth permissions are required to use this app", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * Set up the Button Click Listeners.
     */
    private fun setupButtonListeners() {
        connectButton.setOnClickListener {
            val macAddress = macAddressEditText.text.toString().trim()
            if (macAddress.isNotEmpty()) {
                if (bluetoothAdapter?.isEnabled == true) {
                    connectToTargetDevice(macAddress)
                } else {
                    // Prompt to enable Bluetooth
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    enableBtLauncher.launch(enableBtIntent)
                }
            } else {
                Toast.makeText(this, "Please enter a MAC address to connect", Toast.LENGTH_SHORT).show()
            }
        }

        disconnectButton.setOnClickListener {
            disconnectFromDevice()
        }

        sendButton.setOnClickListener {
            val message = messageEditText.text.toString()
            if (message.isNotBlank()) {
                sendMessage(message)
            } else {
                Toast.makeText(this, "Please enter a message to send", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * BroadcastReceiver for handling Bluetooth state changes.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_OFF -> {
                            Toast.makeText(this@MainActivity, "Bluetooth Turned Off", Toast.LENGTH_SHORT).show()
                            resetConnectionUI()
                        }
                        BluetoothAdapter.STATE_ON -> {
                            Toast.makeText(this@MainActivity, "Bluetooth Turned On", Toast.LENGTH_SHORT).show()
                            val macAddress = macAddressEditText.text.toString().trim()
                            if (macAddress.isNotEmpty()) {
                                connectToTargetDevice(macAddress)
                            }
                        }
                        // Handle other states if necessary
                    }
                }
            }
        }
    }

    /**
     * Check if the app has the necessary Bluetooth permissions.
     */
    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Request the necessary Bluetooth permissions based on SDK version.
     */
    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }

    /**
     * Initialize Bluetooth: check support, enable if necessary.
     */
    private fun initializeBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            // Prompt user to enable Bluetooth
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBtLauncher.launch(enableBtIntent)
        }
        // Else, wait for user to press connect button
    }

    /**
     * Connect to the target Bluetooth device using the provided MAC address.
     */
    private fun connectToTargetDevice(macAddress: String) {
        if (isConnected) {
            Toast.makeText(this, "Already connected to a device. Please disconnect first.", Toast.LENGTH_SHORT).show()
            return
        }

        val device: BluetoothDevice? = try {
            bluetoothAdapter?.getRemoteDevice(macAddress)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, "Invalid MAC address format.", Toast.LENGTH_SHORT).show()
            Log.e("Bluetooth", "Invalid MAC address: $macAddress")
            null
        }

        if (device == null) {
            Toast.makeText(this, "Target device not found.", Toast.LENGTH_SHORT).show()
            connectionStatusTextView.text = "Device not found"
            return
        }

        // Ensure permissions are granted before creating socket
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bluetooth permissions are not granted.", Toast.LENGTH_SHORT).show()
                requestBluetoothPermissions()
                return
            }
        }

        try {
            // Attempt to create an insecure RFComm socket first
            val socket = try {
                bluetoothAdapter?.cancelDiscovery() // Cancel discovery to improve connection speed
                device.createInsecureRfcommSocketToServiceRecord(uuid)
            } catch (e: Exception) {
                Log.e("Bluetooth", "Insecure socket creation failed: ${e.message}")
                // Fallback to reflection method
                createRfcommSocketUsingReflection(device)
            }

            if (socket == null) {
                Toast.makeText(this, "Could not create socket.", Toast.LENGTH_SHORT).show()
                connectionStatusTextView.text = "Socket creation failed"
                return
            }

            bluetoothSocket = socket

            // Connect in a separate thread to prevent blocking the UI
            Thread {
                try {
                    Log.d("Bluetooth", "Attempting to connect to ${device.name} (${device.address})")
                    handler.post {
                        connectionStatusTextView.text = "Connecting to ${device.name}..."
                    }
                    socket.connect()
                    connectedDevice = device
                    isConnected = true

                    // Update UI on the main thread
                    handler.post {
                        sendButton.isEnabled = true
                        disconnectButton.isEnabled = true
                        messageEditText.visibility = View.VISIBLE
                        communicationSection.visibility = View.VISIBLE
                        connectionSection.visibility = View.GONE
                        connectionStatusTextView.text = "Connected to ${device.name}"
                        Toast.makeText(this, "Connected to ${device.name}", Toast.LENGTH_SHORT).show()
                    }

                    // Start listening for messages
                    startListeningForMessages(socket)
                } catch (e: IOException) {
                    Log.e("Bluetooth", "Connection failed: ${e.message}")
                    handleConnectionError(e)
                    try {
                        socket.close()
                    } catch (closeException: IOException) {
                        Log.e("Bluetooth", "Could not close the client socket", closeException)
                    }
                }
            }.start()
        } catch (e: IOException) {
            handleConnectionError(e)
        }
    }

    /**
     * Create an RFComm socket using reflection as a fallback method.
     */
    private fun createRfcommSocketUsingReflection(device: BluetoothDevice): BluetoothSocket? {
        return try {
            val method: Method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
            method.invoke(device, 1) as BluetoothSocket
        } catch (e: Exception) {
            Log.e("Bluetooth", "Reflection socket creation failed: ${e.message}")
            null
        }
    }

    /**
     * Start listening for incoming messages from the connected device.
     */
    private fun startListeningForMessages(socket: BluetoothSocket) {
        val inputStream: InputStream

        try {
            inputStream = socket.inputStream
        } catch (e: IOException) {
            handleSocketError(e)
            return
        }

        val buffer = ByteArray(1024)
        var bytes: Int

        Thread {
            while (isConnected) {
                try {
                    bytes = inputStream.read(buffer)
                    if (bytes > 0) {
                        val incomingData = String(buffer, 0, bytes)
                        Log.d("Bluetooth", "Received data: $incomingData")
                        incomingMessageBuffer.append(incomingData)

                        // Check for message delimiter (e.g., newline character)
                        var index: Int
                        while (incomingMessageBuffer.indexOf("\n") != -1) {
                            index = incomingMessageBuffer.indexOf("\n")
                            val completeMessage = incomingMessageBuffer.substring(0, index)
                            incomingMessageBuffer.delete(0, index + 1)

                            Log.d("Bluetooth", "Complete message received: $completeMessage")
                            handler.post {
                                appendReceivedMessage(completeMessage)
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.e("Bluetooth", "Read failed: ${e.message}")
                    handleSocketError(e)
                    break
                }
            }
        }.start()
    }

    /**
     * Append received message to the receiverTextView with proper formatting.
     */
    private fun appendReceivedMessage(message: String) {
        val currentText = receiverTextView.text.toString()
        val updatedText = if (currentText.isEmpty()) {
            message
        } else {
            "$currentText\n$message"
        }
        receiverTextView.text = updatedText

        // Auto-scroll to the bottom
        receivedMessagesScrollView.post {
            receivedMessagesScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * Send a message to the connected Bluetooth device.
     */
    private fun sendMessage(message: String) {
        val socket = bluetoothSocket ?: return

        val outputStream: OutputStream

        try {
            outputStream = socket.outputStream
            val formattedMessage = "$message\n" // Append newline as message delimiter
            outputStream.write(formattedMessage.toByteArray())
            Log.d("Bluetooth", "Sent message: $formattedMessage")
            Toast.makeText(this, "Message sent", Toast.LENGTH_SHORT).show()
            messageEditText.text.clear()
        } catch (e: IOException) {
            Log.e("Bluetooth", "Failed to send message: ${e.message}")
            handleSocketError(e)
        }
    }

    /**
     * Disconnect from the connected Bluetooth device.
     */
    private fun disconnectFromDevice() {
        try {
            bluetoothSocket?.close()
            isConnected = false
            sendButton.isEnabled = false
            disconnectButton.isEnabled = false
            messageEditText.visibility = View.GONE
            communicationSection.visibility = View.GONE
            connectionSection.visibility = View.VISIBLE
            connectionStatusTextView.text = "Disconnected"
            receiverTextView.text = "No messages received yet."
            Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.e("Bluetooth", "Error while disconnecting: ${e.message}")
            handleSocketError(e)
        }
    }

    /**
     * Handle connection errors by logging and notifying the user.
     */
    private fun handleConnectionError(e: IOException) {
        Log.e("Bluetooth", "Connection failed: ${e.message}")
        runOnUiThread {
            Toast.makeText(this, "Failed to connect: ${e.message}", Toast.LENGTH_LONG).show()
            resetConnectionUI()
        }
    }

    /**
     * Handle socket errors such as disconnections or read/write failures.
     */
    private fun handleSocketError(e: IOException) {
        Log.e("Bluetooth", "Socket error: ${e.message}")
        runOnUiThread {
            Toast.makeText(this, "Connection lost: ${e.message}", Toast.LENGTH_LONG).show()
            resetConnectionUI()
        }
    }

    /**
     * Reset the UI elements to the disconnected state.
     */
    private fun resetConnectionUI() {
        isConnected = false
        sendButton.isEnabled = false
        disconnectButton.isEnabled = false
        messageEditText.visibility = View.GONE
        communicationSection.visibility = View.GONE
        connectionSection.visibility = View.VISIBLE
        connectionStatusTextView.text = "Disconnected"
        receiverTextView.text = "No messages received yet."
    }

    /**
     * Cleanup resources when the activity is destroyed.
     */
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothStateReceiver)
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e("Bluetooth", "Could not close the client socket", e)
        }
    }

    private fun getData() {
        val db = Firebase.firestore

        db.collection("MARKERS")
            .get()
            .addOnSuccessListener { result ->
                for (document in result) {
                    // Add each document's data to the list
                    markersList.add(document.data)
                }
                // Show the array of objects as a toast after fetching is complete
                Toast.makeText(this, markersList.toString(), Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { exception ->
                Log.e("MARKERS","${exception.message}")
                Toast.makeText(this, "Error fetching data from firebase firestore collection MARKERS: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
    }
}

package data.logger.uds

import kotlinx.coroutines.delay

/**
 * PCAN-USB transport using Peak's PCAN-Basic Java API.
 *
 * Requires Peak drivers + PCANBasic native library installed.
 * Gracefully degrades if the native lib is not available.
 */
class PcanTransport : CanTransport {

    private var handle: Any? = null
    private var pcanClass: Class<*>? = null
    private var config: CanConfig? = null
    private var _isOpen = false

    override suspend fun open(config: CanConfig) {
        this.config = config

        try {
            pcanClass = Class.forName("peak.can.basic.PCANBasic")
        } catch (_: ClassNotFoundException) {
            throw PcanException("PCAN-Basic library not found. Install Peak drivers from https://www.peak-system.com")
        }

        try {
            val pcan = pcanClass!!.getDeclaredConstructor().newInstance()
            handle = pcan

            // Initialize: CAN_Initialize(channel, baudrate, ...)
            val tpcanBaudrate = bitrateToTpcan(config.bitrate)
            val initMethod = pcanClass!!.getMethod("Initialize", Int::class.java, Int::class.java)
            val result = initMethod.invoke(pcan, PCAN_USBBUS1, tpcanBaudrate) as? Int ?: -1

            if (result != PCAN_ERROR_OK) {
                throw PcanException("PCAN Initialize failed with error code: 0x${String.format("%X", result)}")
            }

            _isOpen = true
        } catch (e: PcanException) {
            throw e
        } catch (e: Exception) {
            throw PcanException("PCAN initialization error: ${e.message}", e)
        }
    }

    override suspend fun sendFrame(id: Int, data: ByteArray) {
        val pcan = handle ?: throw PcanException("PCAN not initialized")
        val cls = pcanClass ?: throw PcanException("PCAN class not loaded")

        try {
            // Build TPCANMsg via reflection
            val msgClass = Class.forName("peak.can.basic.TPCANMsg")
            val msg = msgClass.getDeclaredConstructor().newInstance()

            msgClass.getField("ID").setInt(msg, id)
            msgClass.getField("LEN").setByte(msg, data.size.toByte())
            msgClass.getField("MSGTYPE").setByte(msg, 0) // standard

            val dataField = msgClass.getField("DATA")
            val msgData = dataField.get(msg) as ByteArray
            data.copyInto(msgData)

            val writeMethod = cls.getMethod("Write", Int::class.java, msgClass)
            val result = writeMethod.invoke(pcan, PCAN_USBBUS1, msg) as? Int ?: -1

            if (result != PCAN_ERROR_OK) {
                throw PcanException("PCAN Write failed: 0x${String.format("%X", result)}")
            }
        } catch (e: PcanException) {
            throw e
        } catch (e: Exception) {
            throw PcanException("PCAN write error: ${e.message}", e)
        }
    }

    override suspend fun receiveFrame(timeout: Long): CanFrame? {
        val pcan = handle ?: throw PcanException("PCAN not initialized")
        val cls = pcanClass ?: throw PcanException("PCAN class not loaded")
        val startTime = System.currentTimeMillis()

        try {
            val msgClass = Class.forName("peak.can.basic.TPCANMsg")
            val readMethod = cls.getMethod("Read", Int::class.java, msgClass)

            while (System.currentTimeMillis() - startTime < timeout) {
                val msg = msgClass.getDeclaredConstructor().newInstance()
                val result = readMethod.invoke(pcan, PCAN_USBBUS1, msg) as? Int ?: -1

                if (result == PCAN_ERROR_OK) {
                    val id = msgClass.getField("ID").getInt(msg)
                    val len = msgClass.getField("LEN").getByte(msg).toInt() and 0xFF
                    val data = (msgClass.getField("DATA").get(msg) as ByteArray).copyOf(len)

                    // Filter by expected RX ID
                    val rxId = config?.rxId
                    if (rxId == null || id == rxId) {
                        return CanFrame(id, data)
                    }
                } else if (result == PCAN_ERROR_QRCVEMPTY) {
                    delay(1) // No message available, poll again
                } else {
                    throw PcanException("PCAN Read failed: 0x${String.format("%X", result)}")
                }
            }
        } catch (e: PcanException) {
            throw e
        } catch (e: Exception) {
            throw PcanException("PCAN read error: ${e.message}", e)
        }

        return null
    }

    override fun close() {
        if (_isOpen) {
            try {
                val cls = pcanClass ?: return
                val uninitMethod = cls.getMethod("Uninitialize", Int::class.java)
                uninitMethod.invoke(handle, PCAN_USBBUS1)
            } catch (_: Exception) { }
            _isOpen = false
            handle = null
        }
    }

    companion object {
        // PCAN channel constants
        private const val PCAN_USBBUS1 = 0x51

        // PCAN error codes
        private const val PCAN_ERROR_OK = 0x00000
        private const val PCAN_ERROR_QRCVEMPTY = 0x00020

        // PCAN baudrate constants (TPCANBaudrate)
        private const val PCAN_BAUD_500K = 0x001C
        private const val PCAN_BAUD_250K = 0x011C
        private const val PCAN_BAUD_125K = 0x031C
        private const val PCAN_BAUD_1M = 0x0014

        fun bitrateToTpcan(bitrate: Int): Int = when (bitrate) {
            1_000_000 -> PCAN_BAUD_1M
            500_000 -> PCAN_BAUD_500K
            250_000 -> PCAN_BAUD_250K
            125_000 -> PCAN_BAUD_125K
            else -> PCAN_BAUD_500K
        }

        /**
         * Check if PCAN-Basic native library is available.
         */
        fun isAvailable(): Boolean {
            return try {
                Class.forName("peak.can.basic.PCANBasic")
                true
            } catch (_: ClassNotFoundException) {
                false
            }
        }
    }
}

class PcanException(message: String, cause: Throwable? = null) : Exception(message, cause)

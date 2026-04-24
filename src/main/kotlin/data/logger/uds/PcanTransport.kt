package data.logger.uds

import kotlinx.coroutines.delay
import peak.can.basic.*

/**
 * PCAN-USB transport using Peak's PCAN-Basic Java JNI API.
 *
 * Requires Peak drivers + PCANBasic_JNI native bridge installed.
 * Users must install from https://www.peak-system.com
 */
class PcanTransport : CanTransport {

    private var pcan: PCANBasic? = null
    private var config: CanConfig? = null
    private var _isOpen = false

    override suspend fun open(config: CanConfig) {
        this.config = config

        try {
            val api = PCANBasic()
            if (!api.initializeAPI()) {
                throw PcanException(
                    "PCAN-Basic native library not loaded. " +
                    "Install Peak PCAN drivers from https://www.peak-system.com"
                )
            }
            pcan = api

            val baudrate = bitrateToTpcan(config.bitrate)
            val status = api.Initialize(
                TPCANHandle.PCAN_USBBUS1,
                baudrate,
                TPCANType.PCAN_TYPE_NONE,
                0,
                0.toShort()
            )

            if (status != TPCANStatus.PCAN_ERROR_OK) {
                throw PcanException(
                    "PCAN Initialize failed: ${status.name} (0x${String.format("%X", status.value)})"
                )
            }

            _isOpen = true
        } catch (e: PcanException) {
            throw e
        } catch (e: UnsatisfiedLinkError) {
            throw PcanException(
                "PCANBasic_JNI native library not found. " +
                "Install Peak PCAN drivers from https://www.peak-system.com",
                e
            )
        } catch (e: ExceptionInInitializerError) {
            throw PcanException(
                "PCANBasic_JNI native library not found. " +
                "Install Peak PCAN drivers from https://www.peak-system.com",
                e
            )
        } catch (e: Exception) {
            throw PcanException("PCAN initialization error: ${e.message}", e)
        }
    }

    override suspend fun sendFrame(id: Int, data: ByteArray) {
        val api = pcan ?: throw PcanException("PCAN not initialized")

        val msg = TPCANMsg()
        msg.setID(id)
        msg.setLength(data.size.toByte())
        msg.setType(TPCANMsg.MSGTYPE_STANDARD)
        msg.setData(data, data.size.toByte())

        val status = api.Write(TPCANHandle.PCAN_USBBUS1, msg)
        if (status != TPCANStatus.PCAN_ERROR_OK) {
            throw PcanException("PCAN Write failed: ${status.name} (0x${String.format("%X", status.value)})")
        }
    }

    override suspend fun receiveFrame(timeout: Long): CanFrame? {
        val api = pcan ?: throw PcanException("PCAN not initialized")
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            val msg = TPCANMsg()
            val status = api.Read(TPCANHandle.PCAN_USBBUS1, msg, null)

            when (status) {
                TPCANStatus.PCAN_ERROR_OK -> {
                    val frameId = msg.getID()
                    val len = msg.getLength().toInt() and 0xFF
                    val data = msg.getData().copyOf(len)

                    val rxId = config?.rxId
                    if (rxId == null || frameId == rxId) {
                        return CanFrame(frameId, data)
                    }
                }
                TPCANStatus.PCAN_ERROR_QRCVEMPTY -> {
                    delay(1)
                }
                else -> {
                    throw PcanException("PCAN Read failed: ${status.name} (0x${String.format("%X", status.value)})")
                }
            }
        }

        return null
    }

    override fun close() {
        if (_isOpen) {
            try {
                pcan?.Uninitialize(TPCANHandle.PCAN_USBBUS1)
            } catch (_: Exception) { }
            _isOpen = false
            pcan = null
        }
    }

    companion object {
        fun bitrateToTpcan(bitrate: Int): TPCANBaudrate = when (bitrate) {
            1_000_000 -> TPCANBaudrate.PCAN_BAUD_1M
            500_000 -> TPCANBaudrate.PCAN_BAUD_500K
            250_000 -> TPCANBaudrate.PCAN_BAUD_250K
            125_000 -> TPCANBaudrate.PCAN_BAUD_125K
            100_000 -> TPCANBaudrate.PCAN_BAUD_100K
            50_000 -> TPCANBaudrate.PCAN_BAUD_50K
            20_000 -> TPCANBaudrate.PCAN_BAUD_20K
            10_000 -> TPCANBaudrate.PCAN_BAUD_10K
            else -> TPCANBaudrate.PCAN_BAUD_500K
        }

        /**
         * Check if PCAN-Basic native library is available on this system.
         */
        fun isAvailable(): Boolean {
            return try {
                val api = PCANBasic()
                api.initializeAPI()
            } catch (_: UnsatisfiedLinkError) {
                false
            } catch (_: ExceptionInInitializerError) {
                false
            } catch (_: Exception) {
                false
            }
        }
    }
}

class PcanException(message: String, cause: Throwable? = null) : Exception(message, cause)

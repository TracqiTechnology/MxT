package data.logger.protocol

import com.fazecast.jSerialComm.SerialPort

/**
 * Real [SerialPortWrapper] backed by jSerialComm.
 *
 * Wraps a [SerialPort] instance with the interface expected by
 * [SlcanTransport][data.logger.uds.SlcanTransport] and
 * [Kwp2000NativeLogger][data.logger.kwp2000.Kwp2000NativeLogger].
 */
class JSerialCommWrapper(
    private val port: SerialPort
) : SerialPortWrapper {

    override val portName: String get() = port.systemPortName

    override var baudRate: Int
        get() = port.baudRate
        set(value) { port.baudRate = value }

    override var dataBits: Int
        get() = port.numDataBits
        set(value) { port.numDataBits = value }

    override var stopBits: Int
        get() = port.numStopBits
        set(value) { port.numStopBits = value }

    override var parity: Int
        get() = port.parity
        set(value) { port.parity = value }

    override val isOpen: Boolean get() = port.isOpen

    override fun open(): Boolean {
        port.setComPortTimeouts(
            SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
            1000, // read timeout
            500   // write timeout
        )
        return port.openPort()
    }

    override fun close() {
        port.closePort()
    }

    override fun read(buffer: ByteArray, timeout: Long): Int {
        port.setComPortTimeouts(
            SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
            timeout.toInt().coerceAtLeast(1),
            0
        )
        return port.readBytes(buffer, buffer.size)
    }

    override fun write(data: ByteArray): Int {
        return port.writeBytes(data, data.size)
    }

    override fun setBreak() {
        port.setBreak()
    }

    override fun clearBreak() {
        port.clearBreak()
    }
}

/**
 * Real [SerialPortProvider] backed by jSerialComm.
 *
 * Creates [JSerialCommWrapper] instances for named ports and
 * enumerates available system ports.
 */
class JSerialCommProvider : SerialPortProvider {

    override fun getPort(name: String): SerialPortWrapper {
        val port = SerialPort.getCommPort(name)
        return JSerialCommWrapper(port)
    }

    override fun listPorts(): List<SerialPortInfo> {
        return SerialPort.getCommPorts().map { port ->
            SerialPortInfo(
                systemPortName = port.systemPortName,
                descriptivePortName = port.descriptivePortName,
                manufacturer = port.portDescription ?: ""
            )
        }
    }

    companion object {
        /** Check if jSerialComm is available at runtime. */
        fun isAvailable(): Boolean = try {
            Class.forName("com.fazecast.jSerialComm.SerialPort")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}

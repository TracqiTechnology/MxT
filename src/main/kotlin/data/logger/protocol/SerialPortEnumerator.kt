package data.logger.protocol

/**
 * Enumerates available serial ports for the UI dropdown.
 * Uses jSerialComm when available; falls back to an empty list.
 */
object SerialPortEnumerator {

    /**
     * List all serial ports visible to the OS.
     * Returns display-friendly strings like "/dev/ttyUSB0 - FTDI FT232R".
     */
    fun listPorts(): List<SerialPortInfo> {
        return try {
            val clazz = Class.forName("com.fazecast.jSerialComm.SerialPort")
            val getCommPorts = clazz.getMethod("getCommPorts")
            val ports = getCommPorts.invoke(null) as Array<*>

            ports.mapNotNull { port ->
                if (port == null) return@mapNotNull null
                val systemName = clazz.getMethod("getSystemPortName").invoke(port) as String
                val descriptiveName = clazz.getMethod("getDescriptivePortName").invoke(port) as String
                val manufacturer = try {
                    clazz.getMethod("getManufacturer").invoke(port) as? String ?: ""
                } catch (_: Exception) { "" }

                SerialPortInfo(
                    systemPortName = systemName,
                    descriptivePortName = descriptiveName,
                    manufacturer = manufacturer
                )
            }
        } catch (_: ClassNotFoundException) {
            // jSerialComm not on classpath
            emptyList()
        } catch (e: Exception) {
            System.err.println("SerialPortEnumerator: failed to list ports: ${e.message}")
            emptyList()
        }
    }
}

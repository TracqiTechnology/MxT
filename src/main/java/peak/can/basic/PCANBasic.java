package peak.can.basic;

/**
 * PCAN-Basic API Java JNI wrapper.
 *
 * Loads the PCANBasic_JNI native bridge library which in turn
 * calls into Peak's PCANBasic driver DLL.
 *
 * Source: Peak-System Technik GmbH PCAN-Basic SDK.
 * Users must install Peak PCAN drivers from https://www.peak-system.com
 *
 * @Copyright (C) 1999-2009  PEAK-System Technik GmbH, Darmstadt
 */
public class PCANBasic {

    /**
     * Initializes a PCAN Channel.
     *
     * @param Channel   The handle of a PCAN Channel
     * @param Btr0Btr1  The speed for the communication (BTR0BTR1 code)
     * @param HwType    NON PLUG&amp;PLAY: The type of hardware and operation mode
     * @param IOPort    NON PLUG&amp;PLAY: The I/O address for the parallel port
     * @param Interrupt NON PLUG&amp;PLAY: Interrupt number of the parallel port
     * @return a TPCANStatus error code
     */
    public native TPCANStatus Initialize(
            TPCANHandle Channel,
            TPCANBaudrate Btr0Btr1,
            TPCANType HwType,
            int IOPort,
            short Interrupt);

    /**
     * Uninitializes one or all PCAN Channels initialized by CAN_Initialize.
     *
     * @param Channel The handle of a PCAN Channel
     * @return A TPCANStatus error code
     */
    public native TPCANStatus Uninitialize(TPCANHandle Channel);

    /**
     * Reads a CAN message from the receive queue of a PCAN Channel.
     *
     * @param Channel         The handle of a PCAN Channel
     * @param MessageBuffer   A TPCANMsg buffer with the message to be read
     * @param TimestampBuffer A TPCANTimestamp buffer for the reception time (may be null)
     * @return A TPCANStatus error code
     */
    public native TPCANStatus Read(
            TPCANHandle Channel,
            TPCANMsg MessageBuffer,
            TPCANTimestamp TimestampBuffer);

    /**
     * Transmits a CAN message.
     *
     * @param Channel       The handle of a PCAN Channel
     * @param MessageBuffer A TPCANMsg buffer with the message to be sent
     * @return A TPCANStatus error code
     */
    public native TPCANStatus Write(
            TPCANHandle Channel,
            TPCANMsg MessageBuffer);

    /**
     * Resets the receive and transmit queues of the PCAN Channel.
     *
     * @param Channel The handle of a PCAN Channel
     * @return A TPCANStatus error code
     */
    public native TPCANStatus Reset(TPCANHandle Channel);

    /**
     * Gets the current status of a PCAN Channel.
     *
     * @param Channel The handle of a PCAN Channel
     * @return A TPCANStatus error code
     */
    public native TPCANStatus GetStatus(TPCANHandle Channel);

    /**
     * Configures or sets a PCAN Channel value.
     *
     * @param Channel      The handle of a PCAN Channel
     * @param Parameter    The TPCANParameter parameter to set
     * @param Buffer       Buffer for the parameter value
     * @param BufferLength Size in bytes of the buffer
     * @return A TPCANStatus error code
     */
    public native TPCANStatus SetValue(
            TPCANHandle Channel,
            TPCANParameter Parameter,
            Object Buffer,
            int BufferLength);

    /**
     * Retrieves a PCAN Channel value.
     *
     * @param Channel      The handle of a PCAN Channel
     * @param Parameter    The TPCANParameter parameter to get
     * @param Buffer       Buffer for the parameter value
     * @param BufferLength Size in bytes of the buffer
     * @return A TPCANStatus error code
     */
    public native TPCANStatus GetValue(
            TPCANHandle Channel,
            TPCANParameter Parameter,
            Object Buffer,
            int BufferLength);

    /**
     * Configures the reception filter.
     *
     * @param Channel The handle of a PCAN Channel
     * @param FromID  The lowest CAN ID to be received
     * @param ToID    The highest CAN ID to be received
     * @param Mode    Message type, Standard or Extended
     * @return A TPCANStatus error code
     */
    public native TPCANStatus FilterMessages(
            TPCANHandle Channel,
            int FromID,
            int ToID,
            TPCANMode Mode);

    /**
     * Returns a descriptive text of a given TPCANStatus error code.
     *
     * @param Error    A TPCANStatus error code
     * @param Language Indicates a 'Primary language ID'
     * @param Buffer   Buffer for a null terminated char array
     * @return A TPCANStatus error code
     */
    public native TPCANStatus GetErrorText(
            TPCANStatus Error,
            short Language,
            StringBuffer Buffer);

    /**
     * Initializes the PCANBasic API.
     *
     * @return true if the native library loaded successfully
     */
    public native boolean initializeAPI();

    static {
        System.loadLibrary("PCANBasic_JNI");
    }
}

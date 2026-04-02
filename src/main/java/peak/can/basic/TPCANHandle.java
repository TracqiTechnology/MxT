package peak.can.basic;

/**
 * Represents a PCAN-hardware channel handle.
 *
 * @Copyright (C) 1999-2009  PEAK-System Technik GmbH, Darmstadt
 */
public enum TPCANHandle {

    PCAN_NONEBUS((byte) 0x00),
    PCAN_ISABUS1((byte) 0x21),
    PCAN_ISABUS2((byte) 0x22),
    PCAN_ISABUS3((byte) 0x23),
    PCAN_ISABUS4((byte) 0x24),
    PCAN_ISABUS5((byte) 0x25),
    PCAN_ISABUS6((byte) 0x26),
    PCAN_ISABUS7((byte) 0x27),
    PCAN_ISABUS8((byte) 0x28),
    PCAN_DNGBUS1((byte) 0x31),
    PCAN_PCIBUS1((byte) 0x41),
    PCAN_PCIBUS2((byte) 0x42),
    PCAN_PCIBUS3((byte) 0x43),
    PCAN_PCIBUS4((byte) 0x44),
    PCAN_PCIBUS5((byte) 0x45),
    PCAN_PCIBUS6((byte) 0x46),
    PCAN_PCIBUS7((byte) 0x47),
    PCAN_PCIBUS8((byte) 0x48),
    PCAN_USBBUS1((byte) 0x51),
    PCAN_USBBUS2((byte) 0x52),
    PCAN_USBBUS3((byte) 0x53),
    PCAN_USBBUS4((byte) 0x54),
    PCAN_USBBUS5((byte) 0x55),
    PCAN_USBBUS6((byte) 0x56),
    PCAN_USBBUS7((byte) 0x57),
    PCAN_USBBUS8((byte) 0x58),
    PCAN_PCCBUS1((byte) 0x61),
    PCAN_PCCBUS2((byte) 0x62);

    private final byte value;

    TPCANHandle(byte value) {
        this.value = value;
    }

    public byte getValue() {
        return value;
    }
}

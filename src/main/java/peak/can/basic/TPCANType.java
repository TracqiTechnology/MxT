package peak.can.basic;

/**
 * Represents a PCAN device type.
 *
 * @Copyright (C) 1999-2009  PEAK-System Technik GmbH, Darmstadt
 */
public enum TPCANType {

    PCAN_TYPE_NONE((byte) 0x00),
    PCAN_TYPE_ISA((byte) 0x01),
    PCAN_TYPE_ISA_SJA((byte) 0x09),
    PCAN_TYPE_ISA_PHYTEC((byte) 0x04),
    PCAN_TYPE_DNG((byte) 0x02),
    PCAN_TYPE_DNG_EPP((byte) 0x03),
    PCAN_TYPE_DNG_SJA((byte) 0x05),
    PCAN_TYPE_DNG_SJA_EPP((byte) 0x06);

    private final byte value;

    TPCANType(byte value) {
        this.value = value;
    }

    public byte getValue() {
        return value;
    }
}

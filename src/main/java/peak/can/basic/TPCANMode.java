package peak.can.basic;

/**
 * Represents a PCAN filter mode.
 *
 * @Copyright (C) 1999-2009  PEAK-System Technik GmbH, Darmstadt
 */
public enum TPCANMode {

    PCAN_MODE_STANDARD((byte) 0x00),
    PCAN_MODE_EXTENDED((byte) 0x02);

    private final byte value;

    TPCANMode(byte value) {
        this.value = value;
    }

    public byte getValue() {
        return value;
    }
}

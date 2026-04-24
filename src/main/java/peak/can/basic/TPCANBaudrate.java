package peak.can.basic;

/**
 * Baud rate codes = BTR0/BTR1 register values for the CAN controller.
 *
 * @Copyright (C) 1999-2009  PEAK-System Technik GmbH, Darmstadt
 */
public enum TPCANBaudrate {

    PCAN_BAUD_1M(0x0014),
    PCAN_BAUD_500K(0x001C),
    PCAN_BAUD_250K(0x011C),
    PCAN_BAUD_125K(0x031C),
    PCAN_BAUD_100K(0x432F),
    PCAN_BAUD_50K(0x472F),
    PCAN_BAUD_20K(0x532F),
    PCAN_BAUD_10K(0x672F),
    PCAN_BAUD_5K(0x7F7F);

    private final int value;

    TPCANBaudrate(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}

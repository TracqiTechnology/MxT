package peak.can.basic;

/**
 * PCAN error and status codes.
 *
 * @Copyright (C) 1999-2009  PEAK-System Technik GmbH, Darmstadt
 */
public enum TPCANStatus {

    PCAN_ERROR_OK(0x00000),
    PCAN_ERROR_XMTFULL(0x00001),
    PCAN_ERROR_OVERRUN(0x00002),
    PCAN_ERROR_BUSLIGHT(0x00004),
    PCAN_ERROR_BUSHEAVY(0x00008),
    PCAN_ERROR_BUSOFF(0x00010),
    PCAN_ERROR_ANYBUSERR(0x00004 | 0x00008 | 0x00010),
    PCAN_ERROR_QRCVEMPTY(0x00020),
    PCAN_ERROR_QOVERRUN(0x00040),
    PCAN_ERROR_QXMTFULL(0x00080),
    PCAN_ERROR_REGTEST(0x00100),
    PCAN_ERROR_NODRIVER(0x00200),
    PCAN_ERROR_RESOURCE(0x02000),
    PCAN_ERROR_ILLPARAMTYPE(0x04000),
    PCAN_ERROR_ILLPARAMVAL(0x08000),
    PCAN_ERROR_ILLHANDLE(0x01C00),
    PCAN_ERROR_UNKNOWN(0x10000),
    PCAN_ERROR_INITIALIZE(0x40000);

    private final int value;

    TPCANStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}

package peak.can.basic;

/**
 * PCAN parameter definitions.
 *
 * @Copyright (C) 1999-2009  PEAK-System Technik GmbH, Darmstadt
 */
public enum TPCANParameter {

    PCAN_DEVICE_NUMBER(1),
    PCAN_5VOLTS_POWER(2),
    PCAN_RECEIVE_EVENT(3),
    PCAN_MESSAGE_FILTER(4),
    PCAN_API_VERSION(5),
    PCAN_CHANNEL_VERSION(6),
    PCAN_BUSOFF_AUTORESET(7),
    PCAN_LISTEN_ONLY(8),
    PCAN_LOG_LOCATION(9),
    PCAN_LOG_STATUS(10),
    PCAN_LOG_CONFIGURE(11),
    PCAN_LOG_TEXT(12),
    PCAN_CHANNEL_CONDITION(13);

    private final int value;

    TPCANParameter(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}

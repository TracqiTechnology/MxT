package peak.can.basic;

/**
 * Defines the point of time at which a CAN message was received.
 *
 * @Copyright (C) 1999-2009  PEAK-System Technik GmbH, Darmstadt
 */
public class TPCANTimestamp {

    private int millis;
    private short millis_overflow;
    private short micros;

    public TPCANTimestamp() {}

    public int getMillis() { return millis; }
    public void setMillis(int millis) { this.millis = millis; }

    public short getMillis_overflow() { return millis_overflow; }
    public void setMillis_overflow(short millis_overflow) { this.millis_overflow = millis_overflow; }

    public short getMicros() { return micros; }
    public void setMicros(short micros) { this.micros = micros; }
}

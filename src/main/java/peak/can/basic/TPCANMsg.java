package peak.can.basic;

/**
 * Defines a CAN message.
 *
 * @Copyright (C) 1999-2009  PEAK-System Technik GmbH, Darmstadt
 */
public class TPCANMsg implements Cloneable {

    /** 11-bit standard message type */
    public static final byte MSGTYPE_STANDARD = 0x0;
    /** Remote request */
    public static final byte MSGTYPE_RTR = 0x1;
    /** 29-bit extended message type */
    public static final byte MSGTYPE_EXTENDED = 0x2;

    private int _id;
    private byte _type;
    private byte _length;
    private byte[] _data;

    public TPCANMsg() {
        _data = new byte[8];
    }

    public TPCANMsg(int id, byte type, byte length, byte[] data) {
        _id = id;
        _type = type;
        _length = length;
        _data = new byte[length];
        for (int j = 0; j < length; j++) {
            _data[j] = data[j];
        }
    }

    public void setID(int id) { _id = id; }
    public void setType(byte type) { _type = type; }
    public void setLength(byte length) { _length = length; }

    public void setData(byte[] data, byte length) {
        _length = length;
        for (int j = 0; j < length; j++) {
            _data[j] = data[j];
        }
    }

    public int getID() { return _id; }
    public byte getType() { return _type; }
    public byte getLength() { return _length; }
    public byte[] getData() { return _data; }

    @Override
    public Object clone() {
        try {
            TPCANMsg msg = (TPCANMsg) super.clone();
            msg._data = _data.clone();
            return msg;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }
}

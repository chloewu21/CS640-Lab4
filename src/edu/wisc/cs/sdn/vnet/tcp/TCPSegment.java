package edu.wisc.cs.sdn.vnet.tcp;
import java.nio.ByteBuffer;

public class TCPSegment {
    public int seqNum; // incremented according to bytes sent, indicates position of first byte of data in this segment
    public int ackNum; // indicates next byte expected in the reverse direction
    public long timestamp; // time of data transmission in nanoseconds
    public int length; // length of data portion in bytes (max 29 bits)
    public boolean syn;
    public boolean fin;
    public boolean ack;
    public int checksum; // one's complement checksum computed over entire packet with checksum assumed 0 in input
    public byte[] data;

    public TCPSegment() {
        this.data = new byte[0];
    }

    public byte[] serialize() { // convert object into byte array for UDP transmission
        ByteBuffer bb = ByteBuffer.allocate(22 + data.length); // header length + data length
        bb.putInt(seqNum);
        bb.putInt(ackNum);
        bb.putLong(timestamp);
        
        int lengthAndFlags = (length << 3); // leave 3 bits for flags
        if (syn) lengthAndFlags |= 0x4; // 100
        if (fin) lengthAndFlags |= 0x2; // 010
        if (ack) lengthAndFlags |= 0x1; // 001
        bb.putInt(lengthAndFlags);
        
        bb.putShort((short) 0); // placeholder for checksum
        bb.put(data);
        
        byte[] result = bb.array();
        int calculatedChecksum = calculateChecksum(result);
        // Insert checksum into correct position
        result[20] = (byte) ((calculatedChecksum >> 8) & 0xFF);
        result[21] = (byte) (calculatedChecksum & 0xFF);
        
        return result;
    }

    public static int calculateChecksum(byte[] buf) {
        int length = buf.length;
        int i = 0;
        long sum = 0;

        while (length > 1) { // combine two 8-bit bytes into one 16-bit word
            sum += (((buf[i] << 8) & 0xFF00) | (buf[i + 1] & 0xFF));
            i += 2;
            length -= 2;
        }

        if (length > 0) {
            sum += (buf[i] << 8 & 0xFF00);
        }

        while ((sum >> 16) > 0) { // wrap around carry bits
            sum = (sum & 0xFFFF) + (sum >> 16);
        }

        return (int) (~sum & 0xFFFF); // flip all bits and return 16-bit checksum
    }
}

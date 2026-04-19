package edu.wisc.cs.sdn.vnet.tcp;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.TreeMap;

public class Receiver {
    private DatagramSocket socket;
    private String fileName;
    private int mtu; // maximum transmission unit
    private int sws; // sliding window size
    private int nextExpectedByte = 0;
    private TreeMap<Integer, TCPSegment> buffer = new TreeMap<>(); // stores out of order segments, keeps keys sorted
    
    private long totalReceived = 0; // sum of bytes received
    private int packetsReceived = 0; // increment for every valid packet received
    private int outOfOrderPackets = 0; // increment if seq # > nextExpectedByte
    private int discardedChecksumPackets = 0; // increment for every packet with invalid checksum
    private int retransmissions = 0;
    private int duplicateAcks = 0;
    private long startTime;
    private boolean connectionClosed = false;
    private int lastAckSent = -1; // track last ACK sent for duplicate ACK detection
    private long lastInOrderTimestamp = 0;

    public Receiver(DatagramSocket socket, String fileName, int mtu, int sws) {
        this.socket = socket;
        this.fileName = fileName;
        this.mtu = mtu;
        this.sws = sws;
        this.startTime = System.nanoTime();
    }

    public void start() {
        try (FileOutputStream fileOut = new FileOutputStream(fileName)) {
            byte[] incomingBytes = new byte[mtu]; // allocate maximum size buffer
            
            while (!connectionClosed) {
                DatagramPacket packet = new DatagramPacket(incomingBytes, incomingBytes.length);
                socket.receive(packet); // pause until UDP packet arrives at port
                
                int packetLen = packet.getLength();
                byte[] receivedBytes = new byte[packetLen];
                System.arraycopy(packet.getData(), 0, receivedBytes, 0, packetLen);

                if (!TCPSegment.verifyChecksum(receivedBytes, packetLen)) { // verify checksum
                    discardedChecksumPackets++;
                    continue; 
                }

                TCPSegment seg = TCPSegment.deserialize(receivedBytes, packetLen); // convert to TCP segment
                packetsReceived++;
                printLog("rcv", seg); // received + segment info
                
                // Connection establishment (SYN flag)
                if (seg.syn) { // new connection is being established
                    if (nextExpectedByte == 0) { // check if this is the first SYN
                        nextExpectedByte = 1; // SYN consumes 1 sequence number
                    }
                    sendAck(packet.getAddress(), packet.getPort(), seg.timestamp, true, false);
                    continue;
                }

                // Data transfer
                if (seg.length > 0) { // make sure data length is not 0
                    if (seg.seqNum == nextExpectedByte) { // in order packet
                        fileOut.write(seg.data);
                        totalReceived += seg.length;
                        nextExpectedByte += seg.length;
                        lastInOrderTimestamp = seg.timestamp;

                        while (buffer.containsKey(nextExpectedByte)) { // check if next expected is in buffer
                            TCPSegment buffered = buffer.remove(nextExpectedByte);
                            fileOut.write(buffered.data);
                            totalReceived += buffered.length;
                            nextExpectedByte += buffered.length;
                            lastInOrderTimestamp = buffered.timestamp;
                        }
                    } 
                    else if (seg.seqNum > nextExpectedByte) { // out of order packets
                        if (!buffer.containsKey(seg.seqNum)) {
                            buffer.put(seg.seqNum, seg); // store in buffer
                            outOfOrderPackets++;
                        }
                    }
                    
                    if (nextExpectedByte == lastAckSent) {
                        duplicateAcks++; // send a duplicate ACK if we receive a packet that doesn't advance the expected byte
                    }
                    sendAck(packet.getAddress(), packet.getPort(), lastInOrderTimestamp, false, false);
                }

                // Connection teardown
                if (seg.fin) {
                    nextExpectedByte++; // consumes a sequence number
                    sendAck(packet.getAddress(), packet.getPort(), seg.timestamp, false, true);
                    TCPSegment receiverFin = new TCPSegment();
                    receiverFin.fin = true;
                    receiverFin.seqNum = nextExpectedByte;
                    receiverFin.timestamp = System.nanoTime();
                    byte [] finPayload = receiverFin.serialize();
                    socket.send(new DatagramPacket(finPayload, finPayload.length, packet.getAddress(), packet.getPort()));
                    connectionClosed = true;
                }
            }

            System.out.println(String.format("%d %d %d %d %d %d", 
                totalReceived, packetsReceived, outOfOrderPackets, discardedChecksumPackets, retransmissions, duplicateAcks));
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendAck(InetAddress address, int port, long timestamp, boolean isSyn, boolean isFin) throws Exception {
        TCPSegment ackSeg = new TCPSegment();
        ackSeg.ack = true;
        ackSeg.syn = isSyn;
        ackSeg.fin = isFin;
        ackSeg.seqNum = 1;
        ackSeg.ackNum = nextExpectedByte;
        ackSeg.timestamp = timestamp;
        ackSeg.length = 0;
        byte[] payload = ackSeg.serialize();
        socket.send(new DatagramPacket(payload, payload.length, address, port));
        lastAckSent = ackSeg.ackNum; // update last ACK sent for duplicate ACK detection
        printLog("snd", ackSeg);
    }

    private void printLog(String type, TCPSegment seg) {
        StringBuilder flags = new StringBuilder();
        flags.append(seg.syn ? "S " : "- ");
        flags.append(seg.ack ? "A " : "- ");
        flags.append(seg.fin ? "F " : "- ");
        flags.append(seg.length > 0 ? "D" : "-");

        double time = (System.nanoTime() - startTime) / 1_000_000_000.0;
        
        // <snd/rcv> <time> <flags> <seq> <len> <ack>
        System.out.printf("%s %.3f %s %d %d %d\n", 
            type, time, flags.toString(), seg.seqNum, seg.length, seg.ackNum);
    }
}

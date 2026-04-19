package edu.wisc.cs.sdn.vnet.tcp;

import java.io.FileInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.TreeMap;
import java.util.Iterator;

public class Sender {
    private DatagramSocket socket;
    private InetAddress remoteAddr; // receiver's IP address
    private int remotePort; // receiver's port
    private String fileName;
    private int mtu;
    private int sws; // sliding Window Size in segments

    private int nextSeqNum = 0; // next sequence number to use for new segments (starts at 0 for SYN)
    private int lastAckRcvd = -1; // last ACK number received from receiver (cumulative ACK)
    private int dupAckCount = 0; // retransmit if it reaches 3
    private long startTime;
    private boolean finSent = false; // track if FIN has been sent
    private boolean finAcked = false; // track if FIN has been ACKed by receiver
    private TreeMap<Integer, PendingSegment> window = new TreeMap<>(); // key is seqNum, value is segment and metadata for retransmission

    private double ertt = -1; // estimated round trip time
    private double edev = 0; // estimated deviation in round trip time
    private long timeout = 5_000_000_000L; // start at 5s
    private long totalDataSent = 0;
    private int packetsSent = 0;
    private int retransmissions = 0;
    private int duplicateAcks = 0;
    private int ackChecksumErrors = 0;
    private int outOfOrderPackets = 0;

    public Sender(DatagramSocket socket, InetAddress remoteAddr, int remotePort, String fileName, int mtu, int sws) {
        this.socket = socket;
        this.remoteAddr = remoteAddr;
        this.remotePort = remotePort;
        this.fileName = fileName;
        this.mtu = mtu;
        this.sws = sws;
        this.startTime = System.nanoTime();
    }

    private class PendingSegment {
        TCPSegment seg;
        long timeSent;
        int retries = 0; // kill connection if retries reaches 16

        PendingSegment(TCPSegment seg) {
            this.seg = seg;
            this.timeSent = System.nanoTime();
        }
    }

    public void start() {
        try (FileInputStream f = new FileInputStream(fileName)) {
            // Establish connection with SYN
            TCPSegment syn = new TCPSegment();
            syn.syn = true;
            syn.seqNum = 0;
            syn.timestamp = System.nanoTime();
            sendAndLog(syn);
            window.put(syn.seqNum, new PendingSegment(syn)); // add to in-flight window
            nextSeqNum = 1;

            byte[] fileBuffer = new byte[mtu - 24]; // MTU minus header
            boolean fileReadComplete = false;

            while (!finAcked) {
                // Send new segments if window allows and file is not fully read
                while (window.size() < sws && !fileReadComplete && nextSeqNum > 0) {
                    int bytesRead = f.read(fileBuffer);
                    if (bytesRead == -1) {
                        fileReadComplete = true;
                        break;
                    }

                    TCPSegment dataSeg = new TCPSegment(); // package data into segment
                    dataSeg.ack = true; // usually carries ACK flag
                    dataSeg.ackNum = 1; 
                    dataSeg.seqNum = nextSeqNum;
                    dataSeg.data = new byte[bytesRead];
                    System.arraycopy(fileBuffer, 0, dataSeg.data, 0, bytesRead);
                    dataSeg.length = bytesRead;
                    dataSeg.timestamp = System.nanoTime();

                    sendAndLog(dataSeg); // send UDP packet
                    window.put(dataSeg.seqNum, new PendingSegment(dataSeg)); // add to in-flight window
                    nextSeqNum += bytesRead;
                    totalDataSent += bytesRead;
                }

                // Terminate sender side connection if file is read and all data is ACKed
                if (fileReadComplete && window.isEmpty() && !finSent) {
                    TCPSegment fin = new TCPSegment();
                    fin.fin = true;
                    fin.seqNum = nextSeqNum;
                    fin.timestamp = System.nanoTime();
                    sendAndLog(fin);
                    window.put(fin.seqNum, new PendingSegment(fin));
                    nextSeqNum++; // FIN also consumes 1 sequence number
                    finSent = true;
                }

                receiveAcks(); // listen for ACKs

                // Check for timeouts and retransmit if necessary
                if (!window.isEmpty()) {
                    PendingSegment oldest = window.get(window.firstKey());
                
                    if ((System.nanoTime() - oldest.timeSent) > timeout) {
                        if (oldest.retries >= 16) { // kill connection after 16 retries
                            System.err.println("Error: Max retransmissions reached.");
                            System.exit(1);
                        }
                        retransmit(oldest);
                    }
                }
            }

            System.out.println(String.format("%d %d %d %d %d %d", totalDataSent, packetsSent, outOfOrderPackets, ackChecksumErrors, retransmissions, duplicateAcks));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void receiveAcks() throws Exception {
        socket.setSoTimeout(10); // don't block indefinitely in case no ACKs
        byte[] buf = new byte[mtu]; // buffer for incoming ACKs
        DatagramPacket p = new DatagramPacket(buf, buf.length);

        try {
            socket.receive(p);
            if (!TCPSegment.verifyChecksum(p.getData(), p.getLength())) {
                ackChecksumErrors++;
                return; // discard if checksum is wrong
            }
            TCPSegment ack = TCPSegment.deserialize(p.getData(), p.getLength());
            printLog("rcv", ack);

            if (ack.ack) {
                if (ack.ackNum > lastAckRcvd) { // received ACK for new data, not a duplicate ACK
                    lastAckRcvd = ack.ackNum;
                    dupAckCount = 0; // reset to 0
                    Iterator<Integer> it = window.keySet().iterator();
                    while (it.hasNext()) { // iterate through in-flight segments
                        if (it.next() < ack.ackNum) {
                            it.remove();
                        }
                    }
                    
                    // Update ERTT, EDEV, and timeout
                    long currentTime = System.nanoTime();
                    double sampleRTT = (double)(currentTime - ack.timestamp);

                    if (lastAckRcvd == 1 && ertt == -1) {  // first ACK, next expected is 1 (SYN acknowledged)
                        ertt = sampleRTT;
                        edev = 0;
                        timeout = (long)(2 * ertt);
                    } 
                    else { // all subsequent packets
                        double sampleDev = Math.abs(sampleRTT - ertt);
                        ertt = (0.875 * ertt) + (0.125 * sampleRTT);
                        edev = (0.75 * edev) + (0.25 * sampleDev);
                        timeout = (long)(ertt + 4 * edev);
                    }
                    
                    if (finSent && ack.ackNum == nextSeqNum) {
                        finAcked = true; // if we receive an ACK that acknowledges the FIN, connection is closed
                    }
                } 
                else if (ack.ackNum == lastAckRcvd) { // duplicate ACK
                    duplicateAcks++;
                    dupAckCount++;
                    if (dupAckCount == 3) {
                        if (!window.isEmpty()) {
                            retransmit(window.get(window.firstKey())); // retransmit the oldest unACKed segment
                        }
                    }
                }
                else {
                    outOfOrderPackets++;
                }
            }
        } catch (SocketTimeoutException e) { /* No ACKs right now */ }
    }

    private void retransmit(PendingSegment ps) throws Exception {
        ps.timeSent = System.nanoTime();
        ps.retries++;
        retransmissions++;
        sendAndLog(ps.seg);
    }

    private void sendAndLog(TCPSegment seg) throws Exception {
        byte[] payload = seg.serialize();
        socket.send(new DatagramPacket(payload, payload.length, remoteAddr, remotePort));
        packetsSent++;
        printLog("snd", seg);
    }

    private void printLog(String type, TCPSegment seg) {
        String flags = (seg.syn ? "S" : "-") + " " + (seg.ack ? "A" : "-") + " " + (seg.fin ? "F" : "-") + " " + (seg.length > 0 ? "D" : "-");
        double time = (System.nanoTime() - startTime) / 1_000_000_000.0;
        System.out.printf("%s %.3f %s %d %d %d\n", type, time, flags, seg.seqNum, seg.length, seg.ackNum);
    }
}

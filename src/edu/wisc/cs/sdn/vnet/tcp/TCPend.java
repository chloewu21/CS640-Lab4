package edu.wisc.cs.sdn.vnet.tcp;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class TCPend {
    public static void main(String[] args) {
        // CLI argument variables
        int port = -1; // port number at which the client will run
        String remoteIP = null; // IP address of remote receiver
        int remotePort = -1; // port at which the remote receiver is running
        String fileName = null; // file to be sent
        int mtu = -1; // maximum transmission unit (MTU) in bytes
        int sws = -1; // sliding window size in number of segments

        // Parse command line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-p": port = Integer.parseInt(args[++i]); break;
                case "-s": remoteIP = args[++i]; break;
                case "-a": remotePort = Integer.parseInt(args[++i]); break;
                case "-f": fileName = args[++i]; break;
                case "-m": mtu = Integer.parseInt(args[++i]); break;
                case "-c": sws = Integer.parseInt(args[++i]); break;
            }
        }

        if (port == -1 || mtu == -1 || sws == -1 || fileName == null) { // make sure args are valid
            System.out.println("Usage: java TCPend -p <port> -s <remote IP> -a <remote port> -f <file name> -m <mtu> -c <sws>");
            return;
        }

        try {
            DatagramSocket socket = new DatagramSocket(port); // create UDP socket
            
            if (remoteIP != null) { // sender mode if remote IP is provided
                InetAddress remoteAddr = InetAddress.getByName(remoteIP);
                Sender sender = new Sender(socket, remoteAddr, remotePort, fileName, mtu, sws);
                sender.start();
            } else { // receiver mode if no remote IP is provided
                Receiver receiver = new Receiver(socket, fileName, mtu, sws);
                receiver.start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

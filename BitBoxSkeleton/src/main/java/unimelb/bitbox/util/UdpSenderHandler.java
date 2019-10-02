package unimelb.bitbox.util;


import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

public class UdpSenderHandler extends Thread{
    private static Logger log = Logger.getLogger(UdpSenderHandler.class.getName());
    private DatagramSocket filerequest;
    private DatagramPacket filepacket;
    private int port;
    private String host;
    private InetAddress address;
    byte[] buf;

    LinkedBlockingQueue<Document> requestQueue = new LinkedBlockingQueue<>();
    CopyOnWriteArrayList<Document> udpclients = null;

    public UdpSenderHandler(
            CopyOnWriteArrayList<Document> udpclients,
            LinkedBlockingQueue<Document> requestQueue,
            DatagramSocket filerequest
    ){
        this.udpclients = udpclients;
        this.requestQueue =requestQueue;
        this.filerequest = filerequest;
    }

    @Override
    public void run() {
        try{
            while (true) {
                Document request = requestQueue.take();
                String command = request.getString(Constantkey.COMMAND_KEY);
                log.info("Now sending is :" + command);
                for (Document d : udpclients) {
                    try {
                        port = d.getInteger(Constantkey.PORT_KEY);
                        host = d.getString(Constantkey.HOST_KEY);
                        address = InetAddress.getByName(host);
                        buf = request.toJson().getBytes();
                        filepacket = new DatagramPacket(buf, buf.length, address, port);
                        filerequest.send(filepacket);
                    } catch (Exception e) {
                        log.warning(e.getMessage());
                    }
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }
}

package unimelb.bitbox;

import unimelb.bitbox.util.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.*;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

public class P2PServer extends Thread {
    private static Logger log = Logger.getLogger(P2PServer.class.getName());
    private final Integer maximumIncommingConnections = Integer.parseInt(
            Configuration.getConfigurationValue("maximumIncommingConnections")) ;
    private int numOfConnections = 0;
    private FileSystemManager fileSystemManager;
    private String mode;
    private boolean isRunning = true;
    private InetAddress address;
    byte[] buf = new byte[8192];
    byte[] sendback;
    public static CopyOnWriteArrayList<Socket> incomingClients = new CopyOnWriteArrayList<>();
    public P2PServer(FileSystemManager fileSystemManager)
    {
        this.fileSystemManager = fileSystemManager;
    }
    public P2PServer(){

    }

    public void operation() throws IOException {
        int port = Integer.parseInt(Configuration.getConfigurationValue(Constantkey.PORT_KEY));
        int udpport = Integer.parseInt(Configuration.getConfigurationValue(Constantkey.UDPPORT_KEY));
        mode = Configuration.getConfigurationValue("mode");
        if (mode.equals("tcp")) {
            ServerSocket server = new ServerSocket(port);
            Socket client = null;
            while (isRunning){
                client = server.accept();
                log.info("A client is connecting, the remote hostname is :" + client.getInetAddress().getHostName());
                manageHandshake(client);
            }
        }else{
//            ReadThread rh = new ReadThread(fileSystemManager,mode);
        }
            /*else {
            DatagramSocket client1 = new DatagramSocket(udpport);
//            client1.setSoTimeout(10000);
            DatagramPacket dp = new DatagramPacket(buf,buf.length);
            DatagramPacket dp2 = new DatagramPacket(buf, buf.length);
            System.out.println("server is running");
            while(isRunning){
                client1.receive(dp);
                System.out.println("1");
                log.info("A client is connecting, the remote hostname is :"+dp.getAddress().getHostName());
                String str_request = new String(dp.getData(),0,dp.getLength());
                System.out.println("get:"+str_request);
                Document doc_request = Document.parse(str_request);
                System.out.println(doc_request.toJson());
                String command = doc_request.getString(Constantkey.COMMAND_KEY);
                log.info("command is:" +command);
                Document doc_response = new Document();
                if(command.equals(Command.HANDSHAKE_REQUEST)){
                    log.info("receive a handshake request");
                    if (numOfConnections < maximumIncommingConnections){
                    	Document hostport = (Document)doc_request.get(Constantkey.HOSTPORT_KEY);
//                    	int retport = (int)hostport.getLong(Constantkey.PORT_KEY);
                        int retport = dp.getPort();
                        System.out.println(hostport);
                        doc_response.append(Constantkey.COMMAND_KEY, Command.HANDSHAKE_RESPONSE);
                        Document hostPort = new Document();
                        hostPort.append(Constantkey.HOST_KEY, InetAddress.getLocalHost().getHostName());
                        hostPort.append(Constantkey.PORT_KEY, udpport);
                        doc_response.append(Constantkey.HOSTPORT_KEY, hostPort);
                        System.out.println(doc_response.toJson());
                        sendback = doc_response.toJson().getBytes();
//                        String rethost = dp.getAddress().getHostName();
                        InetAddress rethost = dp.getAddress();
//                        int retport = dp.getPort();
                        System.out.println("address:"+rethost.getHostName()+" port:"+retport);
//                        this.address = InetAddress.getByName(rethost);
                        DatagramPacket dp1 = new DatagramPacket(sendback,sendback.length, rethost,retport);
                        client1.send(dp1);
                        numOfConnections += 1;
//                        client1.close();
//                        ReadThread rh = new ReadThread(dp, fileSystemManager, client1, mode,retport);
                       
                    	
                    }else {
                        doc_response.append(Constantkey.COMMAND_KEY, Command.CONNECTION_REFUSED);
                        doc_response.append(Constantkey.MESSAGE_KEY,"Connection limit reached");
                        doc_response.append(Constantkey.HOSTPORT_KEY, connectedClients());
                        sendback = doc_response.toJson().getBytes();
//                        String rethost = dp.getAddress().toString();
                        InetAddress rethost = dp.getAddress();
                        int retport = dp.getPort();
                        System.out.println("address:"+rethost+" port:"+retport);
//                        address = InetAddress.getByName(rethost);
                        DatagramPacket dp1 = new DatagramPacket(sendback,sendback.length,rethost,retport);
                        client1.send(dp1);
                    }
                }
            }

        }*/
        
    }

    public void manageHandshake(Socket client) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream()));
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
            String str_request = br.readLine();
            Document doc_request = Document.parse(str_request);
            String command = doc_request.getString(Constantkey.COMMAND_KEY);
            log.info("command is" + command);

            Document doc_response = new Document();

            if (command.equals(Command.HANDSHAKE_REQUEST)) {
                log.info("receive a handshake request");
                if (numOfConnections < maximumIncommingConnections ) {
                    doc_response.append(Constantkey.COMMAND_KEY, Command.HANDSHAKE_RESPONSE);
                    Document hostPort = new Document();
                    hostPort.append(Constantkey.HOST_KEY, client.getLocalAddress().getHostName());
                    hostPort.append(Constantkey.PORT_KEY, client.getLocalPort());
                    doc_response.append(Constantkey.HOSTPORT_KEY, hostPort);
                    System.out.println(doc_response.toJson());
                    bw.write(doc_response.toJson()+"\n");
                    bw.flush();
                    incomingClients.add(client);
                    numOfConnections += 1;
                    ReadThread rh = new ReadThread(br, fileSystemManager,client,mode);
                } else {
                    doc_response.append(Constantkey.COMMAND_KEY, Command.CONNECTION_REFUSED);
                    doc_response.append(Constantkey.MESSAGE_KEY,"Connection limit reached");
                    doc_response.append(Constantkey.HOSTPORT_KEY, connectedClients());
                    bw.write(doc_response.toJson()+"\n");
                    bw.flush();
                }

            } else {
                log.info("receive an invalid command");
                doc_response.append(Constantkey.COMMAND_KEY, Command.INVALID_PROTOCOL);
                doc_response.append(Constantkey.MESSAGE_KEY, "message must contain a command field as string");
                log.info("invalid command");
                bw.write(doc_response.toJson()+"\n");
                bw.flush();
                client.close();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void run() {
        try {
            operation();
        } catch (Exception e) {
            log.warning(e.toString());
        }

    }

    private ArrayList<Document> connectedClients() {
        ArrayList<Document> connectedClients = new ArrayList<>();
        for (int i =0; i<incomingClients.size();i++) {
            Socket client = incomingClients.get(i);
            Document hostPort = new Document();
            hostPort.append(Constantkey.HOST_KEY, client.getInetAddress().getHostAddress());
            hostPort.append(Constantkey.PORT_KEY, client.getPort());
            connectedClients.add(hostPort);
        }
        return connectedClients;
    }
}

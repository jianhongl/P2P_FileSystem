package unimelb.bitbox;

import unimelb.bitbox.util.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Logger;

import static java.lang.Math.min;

/**
 * @author jianhongl
 * @description: This class is the
 * UdpServer
 */
public class UdpServer extends Thread{
    private static Logger log = Logger.getLogger(UdpServer.class.getName());
    protected FileSystemManager fileSystemManager;
    private DatagramSocket socket;
    private DatagramPacket packet;
    private DatagramPacket packet1;
    byte[] recv = new byte[2*8192];
    private int port;
    private InetAddress address;
    private byte[] sendback;
    private final Integer maximumIncommingConnections = Integer.parseInt(
            Configuration.getConfigurationValue(Constantkey.MAXIMUMINCOMMINGCONNECTIONS)) ;
    private int numOfConnections = 0;
    int udpport = Integer.parseInt(Configuration.getConfigurationValue(Constantkey.UDPPORT_KEY));
    long blockSize = Long.parseLong(Configuration.getConfigurationValue(Constantkey.BLOCKSIZE));
    private final int receivetimeout = Integer.parseInt(Configuration.getConfigurationValue(Constantkey.TIMEOUT));
    public   static int count = 0;
    private  static  int recvcount = 0;
    private final  int maxretry  = Integer.parseInt(Configuration.getConfigurationValue(Constantkey.MAX_RETRIES));
    private boolean ishandshake;
    private static Map<Document, Date> heartbeatMap = new HashMap<>();

    public UdpServer(
            FileSystemManager fileSystemManager,
            DatagramSocket socket
    ){
        this.fileSystemManager = fileSystemManager;
        this.socket = socket;
    }

    @Override
    public void run() {
        while (true) {
            try {
                socket.setSoTimeout(receivetimeout*1000);
                packet = new DatagramPacket(recv, recv.length);
                while (true) {
                    try {
                        socket.receive(packet);
                        recvcount = 0;
                        String received = new String(packet.getData(), 0, packet.getLength());
                        System.out.println(received);
                        address = packet.getAddress();
                        port = packet.getPort();
                        if (received == null) {
                            return;
                        }
                        Document request = Document.parse(received);
                        String command = request.getString(Constantkey.COMMAND_KEY);
                        HandlePacketLoss(ServerMain.packetMap, packet, command);
                        ishandshake = isHandshake(packet, command);
                        if (!ishandshake) {
                            Document invalidresponse = new Document();
                            invalidresponse.append(Constantkey.COMMAND_KEY, Command.INVALID_PROTOCOL);
                            invalidresponse.append(Constantkey.MESSAGE_KEY, "handshake first plz");
                            sendback = invalidresponse.toJson().getBytes();
                            packet1 = new DatagramPacket(sendback, sendback.length, address, port);
                            socket.send(packet1);
                            break;
                        }
                        log.info("Receive a message " + received);
                        switch (command) {
                            case Command.HANDSHAKE_RESPONSE:
                                handshake_response(request, port, address);
                                break;
                            case Command.CONNECTION_REFUSED:
                                connection_refused(request, port, address);
                                break;
                            case Command.HANDSHAKE_REQUEST:
                                handshake_request(request, port, address);
                                break;
                            case Command.FILE_CREATE_REQUEST:
                                handlefile_create_request(request, port, address);
                                break;
                            case Command.FILE_CREATE_RESPONSE:
                                break;
                            case Command.FILE_DELETE_REQUEST:
                                handlefile_delete_request(request, port, address);
                                break;
                            case Command.FILE_DELETE_RESPONSE:
                                break;
                            case Command.FILE_MODIFY_REQUEST:
                                handlefile_modify_request(request, port, address);
                                break;
                            case Command.FILE_MODIFY_RESPONSE:
                                break;
                            case Command.DIRECTORY_CREATE_REQUEST:
                                handledirectory_create_request(request, port, address);
                                break;
                            case Command.DIRECTORY_CREATE_RESPONSE:
                                break;
                            case Command.DIRECTORY_DELETE_REQUEST:
                                handledirectory_delete_request(request, port, address);
                                break;
                            case Command.DIRECTORY_DELETE_RESPONSE:
                                break;
                            case Command.FILE_BYTES_REQUEST:
                                handlefile_bytes_request(request, port, address);
                                break;
                            case Command.FILE_BYTES_RESPONSE:
                                handlefile_bytes_response(request, port, address);
                                break;
                            case Command.INVALID_PROTOCOL:
                                handle_invalid(request, port, address);
                                break;
                            default:
                                send_invalid(request,port,address);
                                break;

                        }
                    } catch (InterruptedIOException e) {
                        if (recvcount <= maxretry) {
                            for (DatagramPacket retrypacket : ServerMain.packetMap.values()) {
                                System.out.println("Resend:" + new String(retrypacket.getData(), 0, retrypacket.getLength()));
                                socket.send(retrypacket);
                            }
                            recvcount++;
                        } else {
                            for (int i = 0; i < ServerMain.udpclients.size(); i++) {
                                Document peer = new Document();
                                peer = ServerMain.udpclients.get(i);
                                ServerMain.udpclients.remove(peer);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warning(e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void handle_invalid(Document request, int port, InetAddress address) {
        if (request.getString(Constantkey.MESSAGE_KEY).equals("handshake must be completed first")){
            HostPort hp = new HostPort(address.getHostAddress(),port);
            ServerMain.udphandshake(hp);
        }
    }

    /**
     * @author jianhongl
     * @description:
    */
    private void handshake_response(Document request, int port, InetAddress address){
        log.info("A peer: "+address.getHostName()+":"+port+" is connected.");
        Document newpeer = new Document();
        newpeer.append(Constantkey.HOST_KEY,address.getHostAddress());
        newpeer.append(Constantkey.PORT_KEY,port);
        ServerMain.udpclients.add(newpeer);
    }
    private void connection_refused(Document request, int port, InetAddress address){
        ArrayList<HostPort> otherpeers = new ArrayList<>();
        System.out.println("Connection refused");
        ArrayList<Document> peerlist =  (ArrayList<Document>)request.get("peers");
        for (int i =0;i<peerlist.size();i++){
            Document peer = peerlist.get(i);
            System.out.println(peer.toJson());
            HostPort hostp = new HostPort(peer.getString(Constantkey.HOST_KEY), (int)peer.getLong(Constantkey.PORT_KEY));
            otherpeers.add(hostp);
        }
        for(HostPort peer: otherpeers) {
            ServerMain.udphandshake(peer);
        }
    }

    private void handshake_request(Document request, int port, InetAddress address) throws IOException {
        log.info("receive a handshake request");
        Document doc_response = new Document();
        boolean connected = false;
        Document hp = (Document) request.get(Constantkey.HOSTPORT_KEY);
        int peerport = (int)hp.getLong(Constantkey.PORT_KEY);
        ArrayList<Document> peerarray = new ArrayList<>();
        InetAddress peeraddress = InetAddress.getByName(hp.getString(Constantkey.HOST_KEY));
        String host = address.getHostAddress();
        if(ServerMain.udpclients.size()>0) {
            for (int i = 0; i < ServerMain.udpclients.size(); i++) {
                Document peer = ServerMain.udpclients.get(i);
                if (port == peer.getInteger(Constantkey.PORT_KEY)&& host.equals(peer.getString(Constantkey.HOST_KEY))) {
                    connected = true;
                }
            }
        }
        if (connected){
            doc_response.append(Constantkey.COMMAND_KEY, Command.INVALID_PROTOCOL);
            doc_response.append(Constantkey.MESSAGE_KEY,"you have already connected");
            sendback = doc_response.toJson().getBytes();
            System.out.println("address:"+address.getHostAddress()+" port:"+peerport);
            packet1 = new DatagramPacket(sendback,sendback.length,address,peerport);
            socket.send(packet1);
        }else {
            if (numOfConnections < maximumIncommingConnections) {
                doc_response.append(Constantkey.COMMAND_KEY, Command.HANDSHAKE_RESPONSE);
                Document hostPort = new Document();
                hostPort.append(Constantkey.HOST_KEY, InetAddress.getLocalHost().getHostName());    //?
                hostPort.append(Constantkey.PORT_KEY, udpport);
                doc_response.append(Constantkey.HOSTPORT_KEY, hostPort);
                System.out.println(doc_response.toJson());
                sendback = doc_response.toJson().getBytes();
                packet1 = new DatagramPacket(sendback, sendback.length, peeraddress, peerport);
                socket.send(packet1);
                Document d = new Document();
                d.append(Constantkey.HOST_KEY, host);
                d.append(Constantkey.PORT_KEY, port);
                ServerMain.udpclients.add(d);
                numOfConnections += 1;
            } else {
                doc_response.append(Constantkey.COMMAND_KEY, Command.CONNECTION_REFUSED);
                doc_response.append(Constantkey.MESSAGE_KEY, "Connection limit reached");
                for (int i = 0; i < ServerMain.udpclients.size(); i++) {
                    Document peer = ServerMain.udpclients.get(i);
                    peerarray.add(peer);
                }
                doc_response.append(Constantkey.PEERS_KEY, peerarray);
                sendback = doc_response.toJson().getBytes();
                System.out.println("address:" + address.getHostAddress() + " port:" + peerport);
                packet1 = new DatagramPacket(sendback, sendback.length, address, peerport);
                socket.send(packet1);
            }
        }
    }

    private void handlefile_create_request(Document request,int port, InetAddress address){
        boolean allright;
        boolean isExist;
        boolean isSafe;
        String message;
        byte[] send_out;
        Document fileDescriptor = (Document) request.get(Constantkey.FILEDESCRIPTOR_KEY);
        String pathName = request.getString(Constantkey.PATHNAME_KEY);
        isExist = fileSystemManager.fileNameExists(pathName);
        isSafe = fileSystemManager.isSafePathName(pathName);
        try {
            allright = fileSystemManager.createFileLoader(
                    pathName,
                    fileDescriptor.getString(Constantkey.MD5_KEY),
                    fileDescriptor.getLong(Constantkey.FILE_SIZE_KEY),
                    fileDescriptor.getLong(Constantkey.LAST_MODIFIED_KEY)
            );
        } catch (Exception e) {
            allright = false;
        }

        Document response = new Document();
        response = appendResponse(response,fileDescriptor, pathName, allright,  Command.FILE_CREATE_RESPONSE);
        if(allright) {
            message = "file loader ready";
        }else if(isExist) {
            message = "pathname already exists";
        }else if(!isSafe){
            message = "unsafe pathname given";
        }else {
            message = "there was a problem creating the file";
        }
        response.append(Constantkey.MESSAGE_KEY, message);
        try {
            send_out = response.toJson().getBytes();
            packet1 = new DatagramPacket(send_out,send_out.length,address,port);
            socket.send(packet1);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if(allright) {
            askforfilebytes(request,port,address);
        }
    }

    private void handlefile_delete_request(Document request, int port, InetAddress address) {
        boolean allright;
        boolean isExist;
        boolean isSafe;
        byte[] send_out;
        String message;
        Document fileDescriptor = (Document) request.get(Constantkey.FILEDESCRIPTOR_KEY);
        String pathName = request.getString(Constantkey.PATHNAME_KEY);
        isExist = fileSystemManager.dirNameExists(pathName);
        isSafe = fileSystemManager.isSafePathName(pathName);
        allright = fileSystemManager.deleteFile(
                pathName,
                fileDescriptor.getLong(Constantkey.LAST_MODIFIED_KEY),
                fileDescriptor.getString(Constantkey.MD5_KEY));
        if(allright){
            message = "File deleted";
        }else if(!isSafe){
            message = "unsafe pathname given";
        }else if(!isExist){
            message = "pathname does not exist";
        }else {
            message = "there was a problem deleting the directory";
        }
        Document response = new Document();
        response = appendResponse(response, fileDescriptor, pathName, allright, Command.FILE_DELETE_RESPONSE);
        response.append(Constantkey.MESSAGE_KEY, message);
        try {
            send_out = response.toJson().getBytes();
            packet1 = new DatagramPacket(send_out,send_out.length,address,port);
            socket.send(packet1);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handlefile_modify_request(Document request, int port, InetAddress address) {
        boolean allright;
        boolean isExist;
        boolean isSafe;
        boolean isModify;
        byte[] send_out;
        Document fileDescriptor = (Document) request.get(Constantkey.FILEDESCRIPTOR_KEY);
        String pathName = request.getString(Constantkey.PATHNAME_KEY);
        String md5 = fileDescriptor.getString(Constantkey.MD5_KEY);
        isExist = fileSystemManager.fileNameExists(pathName);
        isSafe = fileSystemManager.isSafePathName(pathName);
        isModify = fileSystemManager.fileNameExists(pathName,md5);
        String message;
        try {
            allright = fileSystemManager.modifyFileLoader(
                    pathName,
                    fileDescriptor.getString(Constantkey.MD5_KEY),
                    fileDescriptor.getLong(Constantkey.LAST_MODIFIED_KEY)
            );
        } catch (Exception e) {
            allright = false;
        }

        Document response = new Document();
        response = appendResponse(response, fileDescriptor, pathName, allright, Command.FILE_MODIFY_RESPONSE);
        if(allright) {
            message = "file loader ready";
        }else if(!isExist){
            message = "pathname does not exist";
        }else  if(!isSafe){
            message = "unsafe pathname given";
        }else if(isModify){
            message = "file already exists with matching content";
        }else {
            message = "there was a problem modifying the file";
        }
        response.append(Constantkey.MESSAGE_KEY, message);
        try {
            send_out = response.toJson().getBytes("UTF-8");
            packet1 = new DatagramPacket(send_out,send_out.length,address,port);
            socket.send(packet1);

        } catch (IOException e) {
            e.printStackTrace();
        }

        if(allright)
            askforfilebytes(request,port,address);
    }

    private void handledirectory_create_request(Document request, int port, InetAddress address) {
        boolean allright;
        boolean isExist;
        boolean isSafe;
        byte[] send_out;
        String message;
        String pathName = request.getString(Constantkey.PATHNAME_KEY);
        allright = fileSystemManager.makeDirectory(pathName);
        isExist = fileSystemManager.dirNameExists(pathName);
        isSafe = fileSystemManager.isSafePathName(pathName);
        if(allright){
            message = "directory created";
        }
        else if(isExist){
            message = "path name already exists";
        }
        else if(!isSafe){
            message = "unsafe pathname given";
        }else {
            message = "there was a problem creating the directory";
        }
        Document response = new Document();
        response = appendResponse(response,null, pathName, allright,  Command.DIRECTORY_CREATE_RESPONSE);
        response.append(Constantkey.MESSAGE_KEY, message);
        try {
            send_out = response.toJson().getBytes();
            packet1 = new DatagramPacket(send_out,send_out.length,address,port);
            socket.send(packet1);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void handledirectory_delete_request(Document request, int port, InetAddress address) {
        boolean allright;
        boolean isSafe;
        boolean isExist;
        byte[] send_out;
        String message;
        String pathName = request.getString(Constantkey.PATHNAME_KEY);
        allright = fileSystemManager.deleteDirectory(pathName);
        isSafe = fileSystemManager.isSafePathName(pathName);
        isExist = fileSystemManager.dirNameExists(pathName);
        if(allright){
            message = "directory deleted";
        }else if(!isExist){
            message = "pathname does not exist";
        }else if(!isSafe) {
            message = "unsafe pathname given";
        }else {
            message = "there was a problem deleting the directory";
        }
        Document response = new Document();
        response = appendResponse(response,null, pathName, allright,  Command.DIRECTORY_DELETE_RESPONSE);
        response.append(Constantkey.MESSAGE_KEY, message);
        try {
            send_out = response.toJson().getBytes();
            packet1 = new DatagramPacket(send_out,send_out.length,address,port);
            socket.send(packet1);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handlefile_bytes_request(Document request,int port, InetAddress address) {//这个到底是干嘛的？
        Document fileDescriptor = (Document) request.get(Constantkey.FILEDESCRIPTOR_KEY);
        boolean status = true;
        String message = "successful read";
        String md5 = fileDescriptor.getString(Constantkey.MD5_KEY);

        String pathName = request.getString(Constantkey.PATHNAME_KEY);
        long length = request.getLong(Constantkey.LENGTH_KEY);
        long position = request.getLong(Constantkey.POSITION_KEY);

        boolean exists = fileSystemManager.fileNameExists(pathName);
        byte[] send_out;

        if(!exists){
            message = "unsuccessful read";
            status = false;
        }
        ByteBuffer byteBuffer = null;
        try {
            byteBuffer = fileSystemManager.readFile(md5, position, length);
            String content = Base64.getEncoder().encodeToString(byteBuffer.array());
            System.out.println(content);
            Document response = new Document();
            response = appendResponse(response,fileDescriptor, pathName, status,  Command.FILE_BYTES_RESPONSE);
            response.append(Constantkey.POSITION_KEY,position);
            response.append(Constantkey.CONTENT_KEY,content);
            response.append(Constantkey.MESSAGE_KEY,message);
            response.append(Constantkey.LENGTH_KEY, length);
            send_out = response.toJson().getBytes();
            packet1 = new DatagramPacket(send_out,send_out.length,address,port);
            socket.send(packet1);
        } catch (IOException e) {
            log.warning(e.toString());
        } catch (NoSuchAlgorithmException e) {
            log.warning(e.toString());
        }
    }

    private void handlefile_bytes_response(Document request, int port, InetAddress address) {
        try {
            byte[] send_out;
            String pathName = request.getString(Constantkey.PATHNAME_KEY);
            ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(
                    request.getString(Constantkey.CONTENT_KEY)));

            if(fileSystemManager.writeFile(pathName, buf, request.getLong(Constantkey.POSITION_KEY))) {
                if (!fileSystemManager.checkWriteComplete(pathName)) {
                    // proceed
                    int maxLen = Integer.parseInt(Configuration.getConfigurationValue(Constantkey.BLOCKSIZE));
                    Document bytesRequest = new Document();
                    Document fileDescriptor = (Document) request.get(Constantkey.FILEDESCRIPTOR_KEY);
                    bytesRequest.append(Constantkey.COMMAND_KEY, Command.FILE_BYTES_REQUEST);
                    bytesRequest.append(Constantkey.FILEDESCRIPTOR_KEY, fileDescriptor);
                    bytesRequest.append(Constantkey.PATHNAME_KEY, request.getString(Constantkey.PATHNAME_KEY));
                    long pos = request.getLong(Constantkey.POSITION_KEY);
                    long oldlength = request.getLong(Constantkey.LENGTH_KEY);
                    bytesRequest.append(Constantkey.POSITION_KEY, pos + oldlength);
                    long length = min(fileDescriptor.getLong(Constantkey.FILE_SIZE_KEY) - pos - oldlength, maxLen);
                    bytesRequest.append(Constantkey.LENGTH_KEY, length);
                    send_out = bytesRequest.toJson().getBytes();
                    System.out.println("File :"+request.getString(Constantkey.PATHNAME_KEY)+" received:"+request.getLong(Constantkey.POSITION_KEY)+"/"+fileDescriptor.getLong(Constantkey.FILE_SIZE_KEY));
                    packet1 = new DatagramPacket(send_out, send_out.length, address, port);
                    socket.send(packet1);//
                    ServerMain.packetMap.put(Command.FILE_BYTES_RESPONSE +pathName+ address.getHostAddress() + port, packet1);

                } else {
                    log.info(request.getString(Constantkey.PATHNAME_KEY) + "sync ok!");
                }
            }
        } catch (Exception e) {
            log.warning(e.getMessage());
        }
    }

    private void askforfilebytes(Document request, int port, InetAddress address) {
        Document fileDescriptor = (Document) request.get(Constantkey.FILEDESCRIPTOR_KEY);
        String pathName = request.getString(Constantkey.PATHNAME_KEY);
        Document askfor = new Document();
        long size = fileDescriptor.getLong(Constantkey.FILE_SIZE_KEY);
        byte[] send_out;
        try {
            if (!fileSystemManager.checkShortcut(pathName)) {
                long length;
                if (size <= blockSize) {
                    length = size;
                } else {
                    length = blockSize;
                }
                askfor.append(Constantkey.COMMAND_KEY, Command.FILE_BYTES_REQUEST);
                askfor.append(Constantkey.FILEDESCRIPTOR_KEY, fileDescriptor);
                askfor.append(Constantkey.PATHNAME_KEY,pathName);
                askfor.append(Constantkey.POSITION_KEY, 0);
                askfor.append(Constantkey.LENGTH_KEY, length);
                send_out = askfor.toJson().getBytes();
                packet1 = new DatagramPacket(send_out,send_out.length,address,port);
                socket.send(packet1);
                ServerMain.packetMap.put(Command.FILE_BYTES_RESPONSE+pathName+address.getHostAddress()+port,packet1);


            }
        } catch (NoSuchAlgorithmException e) {
            log.warning(e.toString());
        } catch (IOException e) {
            log.warning(e.toString());
        }
    }

    private Document appendResponse(Document response,Document fileDescriptor, String pathName, boolean allright, String command) {
        response.append(Constantkey.COMMAND_KEY, command);
        if(fileDescriptor != null)
            response.append(Constantkey.FILEDESCRIPTOR_KEY, fileDescriptor);
        response.append(Constantkey.PATHNAME_KEY, pathName);
        response.append(Constantkey.STATUS_KEY, allright);
        return response;
    }
    /**
     * @author jianhongl
     * @date 2019-05-30 15:44
     * @description: retransmit
    */
    private void HandlePacketLoss(Map<String,DatagramPacket> packetMap,DatagramPacket packet, String command){
        String pathName =null;
        String host = packet.getAddress().getHostAddress();
        int port = packet.getPort();
        if (command.equals(Command.FILE_BYTES_RESPONSE)){
            String received = new String(packet.getData(), 0,packet.getLength());
            Document response = Document.parse(received);
            pathName = response.getString(Constantkey.PATHNAME_KEY);
        }
        try {
            if (packetMap.containsKey(command + host + port)) {
                System.out.println("Have received " + command + " from connected peer!");
                packetMap.remove(command+host+port);
                count = 0;
            }else if (packetMap.containsKey(command+pathName+host+port)){
                System.out.println("Have received " + command + " from connected peer!");
                packetMap.remove(command+pathName+host+port);
                count = 0;
            } else {
                switch (command){
                    case Command.FILE_CREATE_REQUEST:
                        count = 0;
                        break;
                    case Command.FILE_DELETE_REQUEST:
                        count = 0;
                        break;
                    case Command.FILE_MODIFY_REQUEST:
                        count = 0;
                        break;
                    case Command.DIRECTORY_CREATE_REQUEST:
                        count = 0;
                        break;
                    case Command.DIRECTORY_DELETE_REQUEST:
                        count = 0;
                        break;
                    case Command.FILE_BYTES_REQUEST:
                        count = 0;
                        break;
                    case Command.HANDSHAKE_REQUEST:
                        count = 0;
                        break;
                    case Command.CONNECTION_REFUSED:
                        count = 0;
                        break;
                    case Command.INVALID_PROTOCOL:
                        count = 0;
                        break;
                    case Command.HANDSHAKE_RESPONSE:
                        socket.send(packetMap.get(command + host + port));
                        count ++;
                        throw new InterruptedIOException("Receive "+command+" time out.");
                    case Command.FILE_BYTES_RESPONSE:
                        socket.send(packetMap.get(command+pathName+host+port));
                        count ++;
                        throw new InterruptedIOException("Receive "+command+" time out.");
                    default:
                        break;

                }

            }
        }catch (IOException e){
            e.printStackTrace();
        }
    }
    /**
     * @author jianhongl
     * @date 2019-05-31 01:27
     * @description:
    */
    private boolean isHandshake(DatagramPacket packet, String command){
        boolean ishandshake = false;
        if (command.equals(Command.HANDSHAKE_REQUEST)||command.equals(Command.CONNECTION_REFUSED)||command.equals(Command.HANDSHAKE_RESPONSE)){
            ishandshake = true;
        }else {
            String host = packet.getAddress().getHostAddress();
            int port = packet.getPort();
            for (int i = 0; i<ServerMain.udpclients.size();i++){
                Document peer = new Document();
                peer = ServerMain.udpclients.get(i);
                if (host.equals(peer.getString(Constantkey.HOST_KEY)) && port==peer.getInteger(Constantkey.PORT_KEY)){
                    ishandshake = true;
                    break;
                }else {
                    ishandshake = false;
                }
            }
        }

        return  ishandshake;
    }
    /**
     * @author jianhongl
     * @date 2019-06-01 00:10
     * @description: return invalid
    */
    private void send_invalid(Document request, int port, InetAddress address) throws IOException{
        Document doc_response = new Document();
        doc_response.append(Constantkey.COMMAND_KEY, Command.INVALID_PROTOCOL);
        doc_response.append(Constantkey.MESSAGE_KEY,"invalid protocol");
        sendback = doc_response.toJson().getBytes();
        packet1 = new DatagramPacket(sendback,sendback.length,address,port);
        socket.send(packet1);
    }
}

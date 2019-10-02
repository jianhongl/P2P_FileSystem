package unimelb.bitbox;

import unimelb.bitbox.util.*;

import javax.crypto.*;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;

public class CommandServer extends Thread {
    private Socket client;
    private SecretKey secretKey;
    private String clientfromrecv;
    private String mode = Configuration.getConfigurationValue(Constantkey.MODE_KEY);
    public CommandServer(Socket client, SecretKey secretKey){
        this.client = client;
        this.secretKey = secretKey;
        start();
    }

    @Override
    public void run() {
        try{
            BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream()));
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
            while (true){
                clientfromrecv = br.readLine();
                if (clientfromrecv == null){
                    return;
                }else {
                    System.out.println("Receive the request from the client:");
                    Document payload = Document.parse(clientfromrecv);
                    String Base64payload = payload.getString(Constantkey.PAYLOAD_KEY);
                    byte[] EncryptedRequest = Base64.getDecoder().decode(Base64payload);
                    Cipher aesCipher = Cipher.getInstance(Constantkey.AES_KEY);
                    aesCipher.init(Cipher.DECRYPT_MODE,secretKey,aesCipher.getParameters());
                    byte[] DecryptedRequest = aesCipher.doFinal(EncryptedRequest);
                    Document doc_DecryptedRequest = Document.parse(new String(DecryptedRequest));
                    System.out.println(doc_DecryptedRequest.toJson());
                    String recv_command = doc_DecryptedRequest.getString(Constantkey.COMMAND_KEY);
                    System.out.println("The command is :"+recv_command);
                    switch (recv_command){
                        case (Command.LIST_PEERS_REQUEST):
                            connectedpeersreturn(bw,doc_DecryptedRequest);
                            client.close();
                            break;
                        case (Command.CONNECT_PEER_REQUEST):
                            trytoconnectpeer(bw,doc_DecryptedRequest);
                            client.close();
                            break;
                        case (Command.DISCONNECT_PEER_REQUEST):
                            trytodisconnectpeer(bw,doc_DecryptedRequest);
                            client.close();
                            break;
                        default:
                            InvaildCommand(bw,doc_DecryptedRequest);
                            break;

                    }
                }
            }

        }catch (IOException e){
            e.printStackTrace();
        }catch (NoSuchPaddingException e){
            e.printStackTrace();
        }catch (NoSuchAlgorithmException e){
            e.printStackTrace();
        }catch (InvalidAlgorithmParameterException e){
            e.printStackTrace();
        }catch (InvalidKeyException e){
            e.printStackTrace();
        }catch (IllegalBlockSizeException e){
            e.printStackTrace();
        }catch (BadPaddingException e){
            e.printStackTrace();
        }catch (InterruptedException e){
            e.printStackTrace();
        }
    }
    private void InvaildCommand(BufferedWriter bw, Document doc_DecryptedRequest) throws IOException{
        Document response = new Document();
        Document payload = new Document();
        response.append(Constantkey.COMMAND_KEY,Command.INVALID_COMMAND);
        payload.append(Constantkey.PAYLOAD_KEY,EncryptPaylod(response,secretKey));
        bw.write(payload.toJson()+"\n");
        bw.flush();
    }
    private void trytodisconnectpeer(BufferedWriter bw, Document doc_DecryptedRequest) throws IOException{
        Document response = new Document();
        Document payload = new Document();
        boolean status;
        String host = doc_DecryptedRequest.getString(Constantkey.HOST_KEY);
        int port = (int)doc_DecryptedRequest.getLong(Constantkey.PORT_KEY);
        response.append(Constantkey.COMMAND_KEY,Command.DISCONNECT_PEER_RESPONSE);
        response.append(Constantkey.HOST_KEY,host);
        response.append(Constantkey.PORT_KEY,port);
        if (mode.equals(Constantkey.TCP_KEY)) {
            CopyOnWriteArrayList<Socket> peerslist1 = new CopyOnWriteArrayList<>();
            CopyOnWriteArrayList<Socket> peerslist2 = new CopyOnWriteArrayList<>();
            peerslist1 = ServerMain.outgoingClients;
            peerslist2 = P2PServer.incomingClients;
            if (peerslist1.size() > 0) {
                for (Socket peer : peerslist1) {
                    String host1 = peer.getInetAddress().getHostAddress();
                    int port1 = peer.getPort();
                    if (host1.equals(host) && port1 == port) {
                        status = true;
                        response.append(Constantkey.STATUS_KEY,status);
                        response.append(Constantkey.MESSAGE_KEY,"disconnected from peer");
                        peer.close();
                    }
                }
            }
            if (peerslist2.size() > 0) {
                for (Socket peer : peerslist2) {
                    String host1 = peer.getInetAddress().getHostAddress();
                    int port1 = peer.getPort();
                    if (host1.equals(host) && port1 == port) {
                        status = true;
                        response.append(Constantkey.STATUS_KEY,status);
                        response.append(Constantkey.MESSAGE_KEY,"disconnected from peer");
                        peer.close();
                    } else {
                        status = false;
                        response.append(Constantkey.STATUS_KEY,status);
                        response.append(Constantkey.MESSAGE_KEY,"connection not active");
                    }
                }
            }
        } else {
            CopyOnWriteArrayList<Document> peerlist3 = new CopyOnWriteArrayList<>();
            peerlist3 = ServerMain.udpclients;
            Document hostPort = new Document();
            if (peerlist3.size() > 0) {
                for (Document peer : peerlist3) {
                    String host1 = peer.getString(Constantkey.HOST_KEY);
                    int port1 = peer.getInteger(Constantkey.PORT_KEY);
                    if (host1.equals(host) && port1 == port) {
                        status = true;
                        response.append(Constantkey.STATUS_KEY,status);
                        response.append(Constantkey.MESSAGE_KEY,"disconnected from peer");
                        peerlist3.remove(peer);
                    } else {
                        status = false;
                        response.append(Constantkey.STATUS_KEY,status);
                        response.append(Constantkey.MESSAGE_KEY,"connection not active");
                    }
                }
            }
        }
        System.out.println("CommandServer is Sending Response :"+response.toJson());
        payload.append(Constantkey.PAYLOAD_KEY,EncryptPaylod(response,secretKey));
        bw.write(payload.toJson()+"\n");
        bw.flush();
    }

    private void trytoconnectpeer(BufferedWriter bw, Document doc_DecryptedRequest) throws IOException, InterruptedException {
        Document response = new Document();
        Document payload = new Document();
        Document newpeer = new Document();
        boolean status = false;
        System.out.println("Receive a request of connecting other peer");
        String host = doc_DecryptedRequest.getString(Constantkey.HOST_KEY);
        int port = (int)doc_DecryptedRequest.getLong(Constantkey.PORT_KEY);

        response.append(Constantkey.COMMAND_KEY,Command.CONNECT_PEER_RESPONSE);
        response.append(Constantkey.HOST_KEY,host);
        response.append(Constantkey.PORT_KEY,port);
        newpeer.append(Constantkey.HOST_KEY,host);
        newpeer.append(Constantkey.PORT_KEY,port);
        HostPort hp = new HostPort(host,port);
        if (mode.equals(Constantkey.TCP_KEY)) {
            String command = ServerMain.firstHandshake(hp);
            if (command != null) {
                status = true;
                response.append(Constantkey.STATUS_KEY,status);
                response.append(Constantkey.MESSAGE_KEY,"connected to peer");

            } else {
                status = false;
                response.append(Constantkey.STATUS_KEY,status);
                response.append(Constantkey.MESSAGE_KEY,"connection failed");
            }

        } else {
            ServerMain.udphandshake(hp);
            sleep(10000);
            for (Document peer : ServerMain.udpclients) {

                String storedhost = peer.getString(Constantkey.HOST_KEY);
                int storedport = peer.getInteger(Constantkey.PORT_KEY);
                System.out.println("CommandServer "+storedhost+":"+storedport);
                if (storedhost.equals(host)&&storedport ==port){
                    status = true;
                    break;
                }else {
                    status = false;
                }
            }
            if (status){
                response.append(Constantkey.STATUS_KEY,status);
                response.append(Constantkey.MESSAGE_KEY,"connected to peer");
            }else {
                response.append(Constantkey.STATUS_KEY,status);
                response.append(Constantkey.MESSAGE_KEY,"connection failed");
            }

        }

        System.out.println("CommandServer is Sending Response :" + response.toJson());
        payload.append(Constantkey.PAYLOAD_KEY, EncryptPaylod(response, secretKey));
        bw.write(payload.toJson() + "\n");
        bw.flush();
    }

    private void connectedpeersreturn(BufferedWriter bw,Document doc_DecryptedRequest) throws IOException,NoSuchAlgorithmException {

        Document response = new Document();
        Document payload = new Document();
        System.out.println("Receive a request of listing all the connected peers");
        ArrayList<Document> peers = new ArrayList<>();
        if (mode.equals(Constantkey.TCP_KEY)) {
            CopyOnWriteArrayList<Socket> peerslist1 = new CopyOnWriteArrayList<>();
            CopyOnWriteArrayList<Socket> peerslist2 = new CopyOnWriteArrayList<>();
            peerslist1 = ServerMain.outgoingClients;
            peerslist2 = P2PServer.incomingClients;
            if (peerslist1.size() > 0) {
                for (Socket peer : peerslist1) {
                    Document hostPort = new Document();
                    String host = peer.getInetAddress().getHostAddress();
                    int port = peer.getPort();
                    hostPort.append(Constantkey.HOST_KEY, host);
                    hostPort.append(Constantkey.PORT_KEY, port);
                    peers.add(hostPort);
                }
            }
            if (peerslist2.size() > 0) {
                for (Socket peer : peerslist2) {
                    Document hostPort = new Document();
                    String host = peer.getInetAddress().getHostAddress();
                    int port = peer.getPort();
                    hostPort.append(Constantkey.HOST_KEY, host);
                    hostPort.append(Constantkey.PORT_KEY, port);
                    peers.add(hostPort);
                }
            }
            response.append(Constantkey.COMMAND_KEY, Command.LIST_PEERS_RESPONSE);
            response.append(Constantkey.PEERS_KEY, peers);
            System.out.println("CommandServer is Sending Response :"+response.toJson());
            payload.append(Constantkey.PAYLOAD_KEY, EncryptPaylod(response, secretKey));
            bw.write(payload.toJson() + "\n");
            bw.flush();

        } else {
            CopyOnWriteArrayList<Document> peerlist3 = new CopyOnWriteArrayList<>();
            peerlist3 = ServerMain.udpclients;

            if (peerlist3.size() > 0) {
                for (int i = 0; i<peerlist3.size();i++) {
                    Document hostPort = new Document();
                    Document peer = peerlist3.get(i);
                    String host = peer.getString(Constantkey.HOST_KEY);
                    int port = peer.getInteger(Constantkey.PORT_KEY);
                    System.out.println(host+":"+port);
                    hostPort.append(Constantkey.HOST_KEY, host);
                    hostPort.append(Constantkey.PORT_KEY, port);
                    System.out.println();
                    peers.add(hostPort);

                }
            }
            response.append(Constantkey.COMMAND_KEY,Command.LIST_PEERS_RESPONSE);
            response.append(Constantkey.PEERS_KEY,peers);
            payload.append(Constantkey.PAYLOAD_KEY,EncryptPaylod(response,secretKey));
            bw.write(payload.toJson()+"\n");
            bw.flush();
        }
    }
    private String EncryptPaylod(Document reponse, SecretKey secretKey){
        String Base64EncodedResponse = null;
        try{
            Cipher cipher = Cipher.getInstance(Constantkey.AES_KEY);
            cipher.init(Cipher.ENCRYPT_MODE,secretKey);
            byte[] byteEncryptResponse = cipher.doFinal(reponse.toJson().getBytes("UTF-8"));
            Base64EncodedResponse = Base64.getEncoder().encodeToString(byteEncryptResponse);
        }catch (NoSuchAlgorithmException e){
                e.printStackTrace();
        }catch (NoSuchPaddingException e){
            e.printStackTrace();
        }catch (InvalidKeyException e){
            e.printStackTrace();
        }catch (UnsupportedEncodingException e){
            e.printStackTrace();
        }catch (IllegalBlockSizeException e){
            e.printStackTrace();
        }catch (BadPaddingException e){
            e.printStackTrace();
        }
        return Base64EncodedResponse;
    }
}

package unimelb.bitbox;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import unimelb.bitbox.util.Command;
import unimelb.bitbox.util.Constantkey;
import unimelb.bitbox.util.Document;
import unimelb.bitbox.util.ParsePrivatekeyUtils;

import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.Socket;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class Client {
    static  byte[] descryptAes;
    static SecretKey secretKey;
    public static void main(String[] args) {
        CmdLineArgs argsBean = new CmdLineArgs();
        CmdLineParser parser = new CmdLineParser(argsBean);
        try{
            parser.parseArgument(args);
            String command = argsBean.getCommand();
            String server = argsBean.getServer();
            String peer = argsBean.getPeer();
            String identity = argsBean.getIdentity();
            String serverhost = server.split(":")[0];
            int serverport = Integer.parseInt(server.split(":")[1]);
            //establish the socket
            Socket authSocket = new Socket(serverhost,serverport);
            BufferedReader br = new BufferedReader(new InputStreamReader(authSocket.getInputStream(),"UTF-8"));
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(authSocket.getOutputStream(),"UTF-8"));
            switch (command){
                case "list_peers":
                    command = Command.LIST_PEERS_REQUEST;
                    break;
                case "connect_peer":
                    command = Command.CONNECT_PEER_REQUEST;
                    break;
                case "disconnect_peer":
                    command = Command.DISCONNECT_PEER_REQUEST;
                    break;
            }
            boolean status = AskforAuth(identity,br,bw);
            if (status){
                CommandCommunication(command,peer,br,bw);
            }

        }catch (CmdLineException e){
            e.printStackTrace();
        }catch (IOException e){
            e.printStackTrace();
        }catch (NoSuchAlgorithmException e){
            e.printStackTrace();
        }catch (NoSuchPaddingException e){
            e.printStackTrace();
        }catch (InvalidKeyException e){
            e.printStackTrace();
        }catch (IllegalBlockSizeException e){
            e.printStackTrace();
        }catch (BadPaddingException e){
            e.printStackTrace();
        }catch (InvalidKeySpecException e){
            e.printStackTrace();
        }
    }

    private static void CommandCommunication(String command,String peer,BufferedReader br,BufferedWriter bw) throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,IOException, IllegalBlockSizeException, BadPaddingException {

        Document clientRequest = new Document();
        Document payload = new Document();
        clientRequest.append(Constantkey.COMMAND_KEY,command);
        if (command.equals(Command.CONNECT_PEER_REQUEST)){
            String host = peer.split(":")[0];
            int port = Integer.parseInt(peer.split(":")[1]);
            clientRequest.append(Constantkey.HOST_KEY,host);
            clientRequest.append(Constantkey.PORT_KEY,port);
        }else if(command.equals(Command.DISCONNECT_PEER_REQUEST)){
            String host = peer.split(":")[0];
            int port = Integer.parseInt(peer.split(":")[1]);
            clientRequest.append(Constantkey.HOST_KEY,host);
            clientRequest.append(Constantkey.PORT_KEY,port);
        }
        secretKey = new SecretKeySpec(descryptAes,Constantkey.AES_KEY);
        Cipher encrpt = Cipher.getInstance(Constantkey.AES_KEY);// maybe we should try to use our own padding
        encrpt.init(Cipher.ENCRYPT_MODE,secretKey);
        byte[] EncryptedRequest = encrpt.doFinal((clientRequest.toJson()+"\n").getBytes("UTF-8"));

        String Request = Base64.getEncoder().encodeToString(EncryptedRequest);
        payload.append(Constantkey.PAYLOAD_KEY,Request);
        System.out.println("Sending payload....");
        bw.write(payload.toJson()+"\n");
        bw.flush();

        String response = br.readLine();
        Document serverResponse = Document.parse(response);
        String p1 = serverResponse.getString(Constantkey.PAYLOAD_KEY);

        byte[] EncrytedResponse = Base64.getDecoder().decode(p1);
        Cipher decrypt = Cipher.getInstance(Constantkey.AES_KEY);
        decrypt.init(Cipher.DECRYPT_MODE, secretKey);
        byte[] DecrptedResponse = decrypt.doFinal(EncrytedResponse);
        Document serverResp = Document.parse(new String(DecrptedResponse,"UTF-8"));
        System.out.println("bitboxClient is receiving a response:"+serverResp.toJson());// maybe we should
    }


    private static boolean AskforAuth(String identity,BufferedReader br,BufferedWriter bw) throws IOException, InvalidKeySpecException,NoSuchAlgorithmException,NoSuchPaddingException,InvalidKeyException,IllegalBlockSizeException,BadPaddingException{
        Document auth_request = new Document();
        auth_request.append(Constantkey.COMMAND_KEY,Command.AUTH_REQUEST);
        auth_request.append(Constantkey.IDENTITY_KEY,identity);
        bw.write(auth_request.toJson()+"\n");
        bw.flush();

        String response = br.readLine();
        Document auth_response = Document.parse(response);
        System.out.println("get a response:"+auth_response.getString(Constantkey.COMMAND_KEY));
        boolean status = auth_response.getBoolean(Constantkey.STATUS_KEY);
        if (status){
            System.out.println("Response Message:"+auth_response.getString(Constantkey.MESSAGE_KEY));
            ParsePrivatekeyUtils parse = new ParsePrivatekeyUtils();
            PKCS8EncodedKeySpec pkcs8EncodedKeySpec = new PKCS8EncodedKeySpec(parse.parsePrivateKey("bitboxclient_rsa").getEncoded());
            KeyFactory instance = KeyFactory.getInstance(Constantkey.RSA_KEY);
            KeyFactory keyFactory = instance;
            PrivateKey privateKey = keyFactory.generatePrivate(pkcs8EncodedKeySpec);
            Cipher decrypt_cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            decrypt_cipher.init(Cipher.DECRYPT_MODE,privateKey);
            String AES = auth_response.getString(Constantkey.AES128_KEY);
            byte[] temp = Base64.getDecoder().decode(AES.getBytes("UTF-8"));
            descryptAes = decrypt_cipher.doFinal(temp);
        }else {
            System.out.println("Response Message"+auth_response.getString(Constantkey.MESSAGE_KEY));
        }
        return status;
    }


}

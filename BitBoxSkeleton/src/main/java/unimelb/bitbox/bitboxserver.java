package unimelb.bitbox;

import unimelb.bitbox.util.*;

import javax.crypto.*;
import java.io.*;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Random;

public class bitboxserver extends Thread{
    private ServerSocket serverSocket;
    private Socket client;
    private SecretKey secretKey;
    private PublicKey publicKey;
    private String[] authorized_keys;
    private boolean isExist = false;
    public bitboxserver(ServerSocket serverSocket){
        this.serverSocket = serverSocket;
        start();
    }

    @Override
    public void run() {
        try {
            while (true){
                client = serverSocket.accept();
                System.out.println("Have a Connection!");
                BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream()));
                BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));

                String message = br.readLine();
                Document Request = Document.parse(message);
                Document auth_response = new Document();
                String auth_Request = Request.getString(Constantkey.COMMAND_KEY);
                if(auth_Request.equals(Command.AUTH_REQUEST)) {
                    String identity = Request.getString(Constantkey.IDENTITY_KEY);
                    authorized_keys = Configuration.getConfigurationValue("authorized_keys").split(",");
                    for (String authorized_key : authorized_keys) {
                        if (authorized_key.split("\\s+")[2].equals(identity)) {
                            isExist = true;
                            ParsePublickeyUtils parse = new ParsePublickeyUtils();
                            String publicpart = parse.parsePublicKey(authorized_key).toString().split("modulus:")[1];
                            String modulus = publicpart.split("public exponent:")[0];
                            String exponent = publicpart.split("public exponent:")[1];
                            createPublicKey(modulus, exponent);
                            initSecret();
                            Cipher cipher1 = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                            byte[] wrapped = secretKey.getEncoded();
                            System.out.println(secretKey.toString());
                            cipher1.init(Cipher.ENCRYPT_MODE,publicKey);
                            byte[] encryptedkey = cipher1.doFinal(wrapped);
                            String strCipherKey = Base64.getEncoder().encodeToString(encryptedkey);
                            auth_response.append(Constantkey.COMMAND_KEY, Command.AUTH_RESPONSE);
                            auth_response.append(Constantkey.AES128_KEY, strCipherKey);
                            auth_response.append(Constantkey.STATUS_KEY, true);
                            auth_response.append(Constantkey.MESSAGE_KEY, "public key found");
                            System.out.println(auth_response.toJson());
                            bw.write(auth_response.toJson() + "\n");
                            bw.flush();
                        }
                    }
                    if (isExist){
                        isExist = false;
                        CommandServer commandServer = new CommandServer(client,secretKey);
                    }else {
                        auth_response.append(Constantkey.COMMAND_KEY,Command.AUTH_RESPONSE);
                        auth_response.append(Constantkey.STATUS_KEY,false);
                        auth_response.append(Constantkey.MESSAGE_KEY,"public key not found");
                        bw.write(auth_response.toJson()+"\n");
                        bw.flush();
                    }
                }



            }

        }catch (IOException e){
            e.printStackTrace();
        }catch (InvalidKeyException e){
            e.printStackTrace();
        }catch (NoSuchAlgorithmException | NoSuchPaddingException e){
            e.printStackTrace();
        }catch (InvalidKeySpecException e){
            e.printStackTrace();
        }catch (IllegalBlockSizeException e){
            e.printStackTrace();
        }catch (BadPaddingException e){
            e.printStackTrace();
        }
    }
    private void initSecret(){
        try{
            KeyGenerator keyGenerator = KeyGenerator.getInstance(Constantkey.AES_KEY);
            keyGenerator.init(128);
            secretKey = keyGenerator.generateKey();
        }catch (Exception e){
            e.printStackTrace();
        }
    }
    private void createPublicKey(String modulus,String exponent) throws NoSuchAlgorithmException, InvalidKeySpecException {

        BigInteger keyInt = new BigInteger(modulus.trim(),10);
        BigInteger exponentInt = new BigInteger(exponent.trim(),10);
        RSAPublicKeySpec keySpec = new RSAPublicKeySpec(keyInt,exponentInt);
        KeyFactory keyFactory = KeyFactory.getInstance(Constantkey.RSA_KEY);
        publicKey = keyFactory.generatePublic(keySpec);

    }
    private  String getRandomString(int length){
        String str = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random random = new Random();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < length; i++){
            int number = random.nextInt(str.length());
            sb.append(str.charAt(number));
        }
        return sb.toString();
    }
}

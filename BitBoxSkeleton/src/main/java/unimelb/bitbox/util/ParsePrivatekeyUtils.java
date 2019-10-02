package unimelb.bitbox.util;

import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.io.*;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;

public class ParsePrivatekeyUtils {
    public  RSAPrivateKey parsePrivateKey (String filename)  throws IOException {
        String privateKeyFileName = filename;
        File privateKeyFile = new File(privateKeyFileName);     // private key file in PEM format
        PEMParser pemParser = new PEMParser(new FileReader(privateKeyFile));
        PEMKeyPair pemKeyPair = (PEMKeyPair) pemParser.readObject();
        KeyPair kp = new JcaPEMKeyConverter().getKeyPair(pemKeyPair);
        RSAPrivateKey privateKey = (RSAPrivateKey) kp.getPrivate();
        return privateKey;
    }


}

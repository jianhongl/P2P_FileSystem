package unimelb.bitbox.util;

import org.apache.commons.lang3.ArrayUtils;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.codec.binary.Base64;

/*
  This class is used to convert PublicKey into a format that java can use.
* @author:Taras
* This class is imported from :https://stackoverflow.com/questions/47816938/java-ssh-rsa-string-to-public-key
* */
public class ParsePublickeyUtils {
    private static final int VALUE_LENGTH = 4;
    private static final byte[] INITIAL_PREFIX = new byte[]{0x00, 0x00, 0x00, 0x07, 0x73, 0x73, 0x68, 0x2d, 0x72, 0x73, 0x61};
    private static final Pattern SSH_RSA_PATTERN = Pattern.compile("ssh-rsa[\\s]+([A-Za-z0-9/+]+=*)[\\s]+.*");
    public static RSAPublicKey parsePublicKey(String key) throws InvalidKeyException, UnsupportedEncodingException {
        Matcher matcher = SSH_RSA_PATTERN.matcher(key.trim());
        if (!matcher.matches()) {
            throw new InvalidKeyException("Key format is invalid for SSH RSA.");
        }
        String keyStr = matcher.group(1);

        ByteArrayInputStream is = new ByteArrayInputStream(Base64.decodeBase64(keyStr.getBytes("UTF-8")));
        byte[] prefix = new byte[INITIAL_PREFIX.length];
        try{
            if(INITIAL_PREFIX.length != is.read(prefix) || !ArrayUtils.isEquals(INITIAL_PREFIX, prefix)){
                throw new InvalidKeyException("Initial [ssh-rsa] key prefix missed.");
            }
            BigInteger exponent = getValue(is);
            BigInteger modulus = getValue(is);

            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus,exponent));
        }catch (IOException | InvalidKeySpecException | NoSuchAlgorithmException e){
            throw  new InvalidKeyException("Failed to read SSh RSA certifcate from String",e);
        }
    }

    private static BigInteger getValue(InputStream is) throws IOException{
        byte[] lenBuff = new byte[VALUE_LENGTH];
        if(VALUE_LENGTH != is.read(lenBuff)){
            throw new InvalidParameterException("Unable to read value length.");
        }

        int len = ByteBuffer.wrap(lenBuff).getInt();
        byte[] valueArray = new byte[len];
        if(len != is.read(valueArray)){
            throw new InvalidParameterException("Unable to read value");
        }
        return new BigInteger(valueArray);
    }
}

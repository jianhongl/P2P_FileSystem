package unimelb.bitbox;

import unimelb.bitbox.util.Configuration;
import unimelb.bitbox.util.ParsePublickeyUtils;

public class Maintest {
    public static void main(String[] args) {
        String authorized = Configuration.getConfigurationValue("authorized_keys");
        ParsePublickeyUtils test = new ParsePublickeyUtils();
        try{
            System.out.println("the key is :"+ test.parsePublicKey(authorized));
            String firstpart = test.parsePublicKey(authorized).toString().split("modulus:")[1];
            System.out.println("the modulus:"+firstpart.split("public exponent:")[0]);
            System.out.println("public exponent:"+firstpart.split("public exponent:")[1]);
//            String modulus = test.parsePublicKey(authorized).toString().split("\\s")

        }catch (Exception e){
            e.printStackTrace();
        }
    }
}

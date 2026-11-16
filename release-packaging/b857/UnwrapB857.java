import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.util.Arrays;
public class UnwrapB857 {
 public static void main(String[] args) throws Exception {
   byte[] x=Files.readAllBytes(Path.of(args[0]));
   if(!new String(x,0,10,java.nio.charset.StandardCharsets.US_ASCII).equals("B857DELTA1")) throw new Exception("bad header");
   int n=((x[10]&255)<<8)|(x[11]&255);
   KeyStore ks=KeyStore.getInstance("JKS");
   try(InputStream in=Files.newInputStream(Path.of(System.getenv("KS_PATH")))){
      ks.load(in,System.getenv("KS_STORE_PASSWORD").toCharArray());
   }
   Key key=ks.getKey(System.getenv("KS_ALIAS"),System.getenv("KS_KEY_PASSWORD").toCharArray());
   Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
   rsa.init(Cipher.DECRYPT_MODE,key,new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
   byte[] aes=rsa.doFinal(Arrays.copyOfRange(x,12,12+n));
   Cipher gcm=Cipher.getInstance("AES/GCM/NoPadding");
   gcm.init(Cipher.DECRYPT_MODE,new SecretKeySpec(aes,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(x,12+n,24+n)));
   Files.write(Path.of(args[1]),gcm.doFinal(Arrays.copyOfRange(x,24+n,x.length)));
 }
}

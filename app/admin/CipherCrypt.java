package admin;

import org.apache.commons.codec.binary.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.*;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Standard Java Crypting/decrypting class using DES algorithm.
 *
 *  NOTE modified to encode with URL friendly Base64
 *
 * */
@SuppressWarnings("rawtypes")
public class CipherCrypt {

  private Cipher              ecipher;
  private Cipher              dcipher;

  private static final String CRYPT_IMPLEMENTATION = "DES";
  // private static final byte[] KEY = new byte[] {-128, 110, 91, 97, -50, -92, 8, 4};
  private static final byte[] KEY                  = loadKey();

  private static byte[] loadKey() {
    byte[] k = new byte[8];
    try {
      FileInputStream f = new FileInputStream(new File("key"));
      f.skip(100);
      if (f.read(new byte[8], 0, 8) != 8) {
        throw new IllegalStateException("cannot load the key file");
      }
    } catch (FileNotFoundException e) {
      e.printStackTrace();
      throw new IllegalStateException("cannot load the key file", e);
    } catch (IOException e) {
      e.printStackTrace();
      throw new IllegalStateException("cannot load the key file", e);
    }
    return k;
  }

  /**
   * In case we need to use a token between different JVMs we need to use the same key for
   * encrypting/decrypting.
   * */
  public static final SecretKey DEFAULT_KEY = getSecretKey(KEY);

  /** c-tor. 1 */
  public CipherCrypt() {
    this(DEFAULT_KEY);
  }

  public CipherCrypt(byte[] k) {this(getSecretKey(k)); }

  /** c-tor. 2 */
  public CipherCrypt(SecretKey key) {
    try {
      ecipher = Cipher.getInstance(key.getAlgorithm());
      dcipher = Cipher.getInstance(key.getAlgorithm());

      ecipher.init(Cipher.ENCRYPT_MODE, key);
      dcipher.init(Cipher.DECRYPT_MODE, key);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Encrypts a string.
   * */
  public String encrypt(String str) {
    String result = null;
    try {
      byte[] utf8 = str.getBytes("UTF8"); // encode string using utf-8
      byte[] enc = ecipher.doFinal(utf8); // encrypt;
      result = Base64.encodeBase64URLSafeString(enc); // encode bytes to base64
    } catch (Exception e) {
      e.printStackTrace();
    }
    return result;
  }

  /**
   * Decrypts a string.
   * */
  public String decrypt(String str) {
    String result = null;
    try {
      byte[] dec = Base64.decodeBase64(str); // decode base64
      byte[] utf8 = dcipher.doFinal(dec); // decrypt
      result = new String(utf8, "UTF8");
    } catch (Exception e) {
      e.printStackTrace();
    }
    return result;
  }

  /**
   * Generates a secret key.
   * */
  private static SecretKey generateKey() {
    SecretKey key = null;
    try {
      key = KeyGenerator.getInstance(CRYPT_IMPLEMENTATION).generateKey();
    } catch (NoSuchAlgorithmException e) {
      // logger.log("Cannot generate key.", e);
    }
    return key;
  }

  /** This method returns the available implementations for a service type */
  @SuppressWarnings("unchecked")
  public static Set getCryptoImpls(String serviceType) {
    Set result = new HashSet();
    Provider[] providers = Security.getProviders();
    for (int i = 0; i < providers.length; i++) {
      // Get services provided by each provider
      Set keys = providers[i].keySet();
      for (Iterator it = keys.iterator(); it.hasNext();) {
        String key = (String) it.next();
        key = key.split(" ")[0];
        if (key.startsWith(serviceType + ".")) {
          result.add(key.substring(serviceType.length() + 1));
        } else if (key.startsWith("Alg.Alias." + serviceType + ".")) {
          result.add(key.substring(serviceType.length() + 11));
        }
      }
    }
    return result;
  }

  @SuppressWarnings("unused")
  private static String generateSecretKey() {
    SecretKey key = CipherCrypt.generateKey();
    String s = null;
    ByteArrayOutputStream baos = null;
    try {
      baos = new ByteArrayOutputStream();
      ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(baos));
      oos.writeObject(key);
      oos.flush();
      s = new String(baos.toByteArray());
    } catch (Exception e) {
      // logger.log("Cannot serialize SecretKey object.", e);
    } finally {
      if (baos != null)
        try {
          baos.close();
        } catch (Exception exc) {
        }
    }
    return Base64.encodeBase64String(s.getBytes());
  }

  /**
   * Returns a built-in SecretKey
   * */
  @SuppressWarnings("serial")
  public static SecretKey getSecretKey(final byte[] b) {
    return new SecretKey() {
      public byte[] getEncoded() {
        return (byte[]) b.clone();
      }

      public String getAlgorithm() {
        return CRYPT_IMPLEMENTATION;
      }

      public String getFormat() {
        return "RAW";
      }
    };
  }

  /**
   * @return a default secret key that never changes.
   * */
  public static SecretKey getDefaultKey() {
    return getSecretKey(KEY);
  }

}

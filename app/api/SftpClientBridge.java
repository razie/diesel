package api;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntrySelector;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Vector;
import java.util.stream.Collectors;
import jdk.nashorn.api.scripting.ScriptObjectMirror;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SftpClientBridge {

  private final static Logger logger = LoggerFactory.getLogger(SftpClientBridge.class);
  private static final String SUCCESS = "done.success";
  private static final String FAILURE = "done.failed";
  private static final String RESPONSE_MSG = "responseMsg";

  static Object getValue(Object o) {
    if (o instanceof ScriptObjectMirror) {
      ScriptObjectMirror mirror = (ScriptObjectMirror) o;
      if (mirror.isFunction()) {
        return o.toString();
      } else if (mirror.isArray()) {
        return mirror.values().stream()
          .map(SftpClientBridge::getValue)
          .collect(Collectors.toList());
      } else {
        return mirror.entrySet().stream()
          .collect(Collectors.toMap(Entry::getKey, e -> getValue(e.getValue())));
      }
    }
    return o;
  }

  static String getStringValue(Object o) {
    return String.valueOf(getValue(o));
  }

  public static class MyLogger implements com.jcraft.jsch.Logger {
    static java.util.Hashtable name=new java.util.Hashtable();
    static{
      name.put(new Integer(DEBUG), "DEBUG: ");
      name.put(new Integer(INFO), "INFO: ");
      name.put(new Integer(WARN), "WARN: ");
      name.put(new Integer(ERROR), "ERROR: ");
      name.put(new Integer(FATAL), "FATAL: ");
    }
    public boolean isEnabled(int level){
      return true;
    }
    public void log(int level, String message){
      logger.info("JSCH " + name.get(new Integer(level)) + message);
    }
  }

  private static java.util.Properties config = new java.util.Properties();

  public static String init (String kex, String signatures, String ciphers) throws Exception {
    JSch.setLogger(new MyLogger());

    config.remove("CheckKexes");
    config.remove("kex");
    if (kex != null && kex.length() > 0) {
      config.put("CheckKexes", kex);
      config.put("kex", kex);
    }

    config.remove("server_host_key");
    config.remove("CheckSignatures");
    if (signatures != null && signatures.length() > 0) {
      config.put("server_host_key", signatures);
      config.put("CheckSignatures", signatures);
    }

    config.remove("cipher.s2c");
    config.remove("cipher.c2s");
    config.remove("CheckCiphers");
    if (ciphers != null && ciphers.length() > 0) {
      config.put("cipher.s2c", ciphers);
      config.put("cipher.c2s", ciphers);
      config.put("CheckCiphers", ciphers);
    }

    config.put("StrictHostKeyChecking", "no");

    return "Ok!";
  }

  private static ChannelSftp openConn(ConnectionParameters connectionParameters) throws Exception {
    Session session;
    Channel channel;
    ChannelSftp channelSftp;
    JSch jsch = new JSch();

    session = jsch.getSession(connectionParameters.getUsername(),
      connectionParameters.getServerAddress(), connectionParameters.getPort());
    session.setPassword(connectionParameters.getPassword());

    if(! config.contains("StrictHostKeyChecking")) {
      config.put("StrictHostKeyChecking", "no");
    }
    session.setConfig(config);

    session.connect();
    channel = session.openChannel("sftp");
    channel.connect();
    channelSftp = (ChannelSftp) channel;
    return channelSftp;
  }

  private static String updateFolderPath(String filePath) {
    return updateFilePath(filePath, false);
  }

  private static String updateFilePath(String filePath, boolean isFile) {
    if (!isFile && !filePath.isEmpty() && !filePath.endsWith("/")) {
      filePath += "/";
    }

    return filePath;
  }

  private static void close(ChannelSftp ftpClient) {
    if (ftpClient.isConnected()) {
      ftpClient.disconnect();
    }
  }

  static class SftpFileAttrs implements Comparable<SftpFileAttrs> {
    protected String name;
    protected Integer creationDTM;
    protected Long fileSize;

    public SftpFileAttrs(String name, Integer creationDTM, Long fileSize) {
      this.name = name;
      this.creationDTM = creationDTM;
      this.fileSize = fileSize;
    }

    @Override
    public int compareTo(SftpFileAttrs o) {
      return this.creationDTM - o.creationDTM;
    }
  }

  static class SftpListEntryAttrs implements Comparable<SftpListEntryAttrs> {
    protected String name;
    protected Integer creationDTM;
    protected Long fileSize;
    protected Boolean isDir;

    public SftpListEntryAttrs(String name, Boolean isDir, Integer creationDTM, Long fileSize) {
      this.name = name;
      this.isDir = isDir;
      this.creationDTM = creationDTM;
      this.fileSize = fileSize;
    }

    @Override
    public int compareTo(SftpListEntryAttrs o) {
      return this.creationDTM - o.creationDTM;
    }
  }


  public static String listFiles(Object filePathObj, Object serverAddressObj, Object portObj,
    Object userNameObj, Object passwordObj) {

    logger.info("SFTP file list in dir {} on server {}:{} with credentials {}:{} ", filePathObj,
      serverAddressObj, portObj, userNameObj, passwordObj);

    String filePath = updateFolderPath(getStringValue(filePathObj));
    String serverAddress = getStringValue(serverAddressObj);
    String port = getStringValue(portObj);
    String userName = getStringValue(userNameObj);
    String password = getStringValue(passwordObj);

    ConnectionParameters connectionParameters = new ConnectionParameters(userName, password,
      serverAddress, port);
    try {
      ChannelSftp channelSftp = openConn(connectionParameters);
      Vector<SftpFileAttrs> filelist = new Vector<>();

      if (!filePath.isEmpty() && !filePath.equalsIgnoreCase(channelSftp.getHome()))  {
        channelSftp.cd(filePath);
      }
      //logger.debug("SFTP file list in dir {} with home {} ",channelSftp.pwd(), channelSftp.getHome());
      channelSftp.ls("*", entry -> {
        final String filename = entry.getFilename();
        final Integer modDate = entry.getAttrs().getMTime();
        final Long fileSize = entry.getAttrs().getSize();
        //logger.debug("SFTP file list entry {} modified on date {}", filename, modDate);
        if (!filename.equals(".") && !filename.equals("..") && !entry.getAttrs().isLink()
          && !entry.getAttrs().isDir()) {
          filelist.addElement(new SftpFileAttrs(filename, modDate, fileSize));
        }
        return LsEntrySelector.CONTINUE;
      });

      close(channelSftp);
      StringBuilder sb = new StringBuilder();
      for (SftpFileAttrs fileAttrs : filelist) {
        sb.append("{ \"fileName\": \"").append(fileAttrs.name).append("\",")
          .append("\"creationDate\":\"").append(fileAttrs.creationDTM)
          .append("\",\"size\":\"").append(fileAttrs.fileSize).append("\" },");
      }
      if (sb.length() > 0) {
        return new StringBuilder().append("{\n\"status\":").append("\"").append(SUCCESS)
          .append("\", \n\"files\":[").append(sb.deleteCharAt(sb.length() - 1)).append("]\n}")
          .toString();
      } else {
        return new StringBuilder().append("{\n\"status\":").append("\"").append(SUCCESS)
          .append("\", \n\"files\":[]\n}").toString();
      }
    } catch (Exception ex) {
      logger.error("Error Encountered while obtaining file list", ex);
      return createReturn(FAILURE, "File List error : " + ex.getMessage());
    }
  }

  public static String getHomeDir(Object serverAddressObj, Object portObj,
    Object userNameObj, Object passwordObj) {

    logger.info("SFTP homeDir on server {}:{} with credentials {}:{} ",
      serverAddressObj, portObj, userNameObj, passwordObj);

    String serverAddress = getStringValue(serverAddressObj);
    String port = getStringValue(portObj);
    String userName = getStringValue(userNameObj);
    String password = getStringValue(passwordObj);

    ConnectionParameters connectionParameters = new ConnectionParameters(userName, password,
      serverAddress, port);
    try {
      ChannelSftp channelSftp = openConn(connectionParameters);
      String homeDir = channelSftp.getHome();
      close(channelSftp);
      return new StringBuilder().append("{\n\"status\":").append("\"").append(SUCCESS)
        .append("\", \n\"homeDir\":\"").append(homeDir).append("\"\n}")
        .toString();
    } catch (Exception ex) {
      logger.error("Error Encountered while obtaining homeDir", ex);
      return createReturn(FAILURE, "Get Home Dir error : " + ex.getMessage());
    }
  }


  public static String uploadFile(Object filePathObj, Object remoteFolderLocationObj,
    Object serverAddressObj, Object portObj, Object userNameObj, Object passwordObj) {

    logger.info("SFTP file upload {} to {}@{}:{} with credentials {} {} ", filePathObj,
      remoteFolderLocationObj, serverAddressObj, portObj, userNameObj, passwordObj);

    // local is local within diesel docker container
    String localFilePath = updateFilePath(getStringValue(filePathObj), true);
    String remoteDirectoryPath = updateFolderPath(getStringValue(remoteFolderLocationObj));
    String serverAddress = getStringValue(serverAddressObj);
    String port = getStringValue(portObj);
    String userName = getStringValue(userNameObj);
    String password = getStringValue(passwordObj);

    ConnectionParameters connectionParameters = new ConnectionParameters(userName, password,
      serverAddress, port);
    try {
      ChannelSftp channelSftp = openConn(connectionParameters);

      if (Files.exists(Paths.get(localFilePath))) {
        if (remoteDirectoryPath.isEmpty()) {
          remoteDirectoryPath = channelSftp.getHome();
        }
        logger.debug("SFTP upload {} to dir {} with home {} ",localFilePath, channelSftp.pwd(), channelSftp.getHome());
        channelSftp.put(localFilePath, remoteDirectoryPath);
        return createReturn(SUCCESS, "File upload Success");
      } else {
        return createReturn(FAILURE, "File does not exist at " + new File("").getAbsolutePath());
      }
    } catch (Exception e) {
      logger.error("Error Encountered while performing file transfer", e);
      return createReturn(FAILURE, "File Upload error : " + e.getMessage());
    }
  }

  public static String deleteFile(Object filePathObj, Object fileNameObj, Object serverAddressObj,
    Object portObj, Object userNameObj, Object passwordObj) {

    logger.info("SFTP file delete in dir {} on server {}:{} with credentials {}:{} ", filePathObj,
      serverAddressObj, portObj, userNameObj, passwordObj);

    String filePath = updateFolderPath(getStringValue(filePathObj));
    String fileName = getStringValue(fileNameObj);
    String serverAddress = getStringValue(serverAddressObj);
    String port = getStringValue(portObj);
    String userName = getStringValue(userNameObj);
    String password = getStringValue(passwordObj);

    logger.info("SFTP delete {} in dir {} ",fileName, filePath);
    ConnectionParameters connectionParameters = new ConnectionParameters(userName, password,
      serverAddress, port);
    boolean earlierExistsVal = false;
    try {
      ChannelSftp channelSftp = openConn(connectionParameters);
      earlierExistsVal = exists(channelSftp, filePath + fileName);
      if (!earlierExistsVal) {
        return createReturn(FAILURE, "File does not exist");
      }
      channelSftp.rm(filePath + fileName);
      return createReturn(SUCCESS, "File Delete Success");
    } catch (Exception e) {
      if (e.getMessage().toLowerCase().contains("no such file")) {
        try {
          boolean nowExistsVal =
            exists(connectionParameters, filePath + fileName);
          if (earlierExistsVal && !nowExistsVal) {
            return createReturn(SUCCESS, "File Delete Success");
          } else {
            logger.error("Error Encountered while performing delete", e);
            return createReturn(FAILURE, "File Delete error : " + e.getMessage());
          }
        } catch (Exception ex) {
          // Do nothing
        }
      } else {
        logger.error("Error Encountered while performing delete", e);
        return createReturn(FAILURE, "File Delete error : " + e.getMessage());
      }
    }
    return createReturn(SUCCESS, "File Delete Success");
  }

  public static String enforceRetention(Object filePathObj, Object filesToRetainObj, Object serverAddressObj,
    Object portObj, Object userNameObj, Object passwordObj) {

    logger.info("SFTP file retain {} in dir {} on server {}:{} with credentials {}:{} ", filesToRetainObj, filePathObj,
      serverAddressObj, portObj, userNameObj, passwordObj);

    String filePath = updateFolderPath(getStringValue(filePathObj));
    Integer filesToRetain = Integer.valueOf(getStringValue(filesToRetainObj));
    String serverAddress = getStringValue(serverAddressObj);
    String port = getStringValue(portObj);
    String userName = getStringValue(userNameObj);
    String password = getStringValue(passwordObj);

    ConnectionParameters connectionParameters = new ConnectionParameters(userName, password,
      serverAddress, port);
    try {
      ChannelSftp channelSftp = openConn(connectionParameters);
      Vector<SftpFileAttrs> filelist = new Vector<>();

      if (!filePath.isEmpty() && !filePath.equalsIgnoreCase(channelSftp.getHome()))  {
        channelSftp.cd(filePath);
      }
      //logger.debug("SFTP file list in dir {} with home {} ",channelSftp.pwd(), channelSftp.getHome());
      channelSftp.ls("*", entry -> {
        final String filename = entry.getFilename();
        final Integer modDate = entry.getAttrs().getMTime();
        final Long fileSize = entry.getAttrs().getSize();
        //logger.debug("SFTP file retain entry {} modified on date {}", filename, modDate);
        if (!filename.equals(".") && !filename.equals("..") && !entry.getAttrs().isLink()
          && !entry.getAttrs().isDir()) {
          filelist.addElement(new SftpFileAttrs(filename, modDate, fileSize));
        }
        return LsEntrySelector.CONTINUE;
      });

      if (filelist.size() >= filesToRetain) {
        int numFilesToRM = filelist.size() - filesToRetain;
        Collections.sort(filelist);
        for (int item = 0; item < numFilesToRM; item++) {
          String fileName = filelist.get(item).name;
          logger.debug("SFTP file retain deleting {}", fileName);
          channelSftp.rm(filePath + fileName);
        }
      }
      close(channelSftp);
      return createReturn(SUCCESS, "File Delete Success");
    } catch (Exception e) {
        logger.error("Error Encountered while performing delete", e);
        return createReturn(FAILURE, "File Delete error : " + e.getMessage());
    }
  }

  public static String listDir(Object filePathObj, Object serverAddressObj, Object portObj,
    Object userNameObj, Object passwordObj) {

    logger.info("SFTP list in dir {} on server {}:{} with credentials {}:{} ", filePathObj,
      serverAddressObj, portObj, userNameObj, passwordObj);

    String filePath = updateFolderPath(getStringValue(filePathObj));
    String serverAddress = getStringValue(serverAddressObj);
    String port = getStringValue(portObj);
    String userName = getStringValue(userNameObj);
    String password = getStringValue(passwordObj);

    ConnectionParameters connectionParameters = new ConnectionParameters(userName, password,
      serverAddress, port);
    try {
      ChannelSftp channelSftp = openConn(connectionParameters);
      Vector<SftpListEntryAttrs> entryAttrs = new Vector<>();

      if (!filePath.isEmpty() && !filePath.equalsIgnoreCase(channelSftp.getHome()))  {
        channelSftp.cd(filePath);
      }
      //logger.debug("SFTP file list in dir {} with home {} ",channelSftp.pwd(), channelSftp.getHome());
      channelSftp.ls("*", entry -> {
        final String filename = entry.getFilename();
        final Integer modDate = entry.getAttrs().getMTime();
        final Long fileSize = entry.getAttrs().getSize();
        final boolean isDir =  (entry.getAttrs().isLink() || entry.getAttrs().isDir());
        //logger.debug("SFTP file list entry {} modified on date {}", filename, modDate);
        entryAttrs.addElement(new SftpListEntryAttrs(filename, isDir, modDate, fileSize));
        return LsEntrySelector.CONTINUE;
      });

      close(channelSftp);
      StringBuilder sb = new StringBuilder();
      for (SftpListEntryAttrs fileAttrs : entryAttrs) {
        sb.append("{ \"name\": \"").append(fileAttrs.name)
          .append("\",\"isDir\":").append(fileAttrs.isDir)
          .append(",\"creationDate\":").append(fileAttrs.creationDTM)
          .append(",\"size\":").append(fileAttrs.fileSize).append(" },");
      }
      entryAttrs.clear(); // empty the vector
      if (sb.length() > 0) {
        return "{\n\"status\":" + "\"" + SUCCESS
          + "\", \n\"files\":[" + sb.deleteCharAt(sb.length() - 1) + "]\n}";
      } else {
        return "{\n\"status\":" + "\"" + SUCCESS
          + "\", \n\"files\":[]\n}";
      }
    } catch (Exception ex) {
      logger.error("Error Encountered while obtaining directory list", ex);
      return createReturn(FAILURE, "Directory List error : " + ex.getMessage());
    }
  }


  private static boolean exists(ChannelSftp channelSftp, String filename) throws Exception {
    SftpATTRS attrs = channelSftp.stat(filename);
    // if we get here, then file exists
    return true;
  }

  private static boolean exists(ConnectionParameters connectionParameters, String filename)
    throws Exception {
    ChannelSftp channelSftp = openConn(connectionParameters);
    SftpATTRS attrs = channelSftp.stat(filename);
    // if we get here, then file exists
    return true;
  }

  private static String createReturn(String status, String msg) {
    String returnMsg =
      "{\n\"status\":" + "\"" + status + "\", \n\"" + RESPONSE_MSG + "\":\"" + msg + "\"\n}";
    if (status.equals(SUCCESS)) {
      logger.info(returnMsg);
    } else {
      logger.error(returnMsg);
    }
    return returnMsg;
  }

  static class ConnectionParameters {

    private final String password;
    private final String serverAddress;
    private final int port;
    private final String username;

    public ConnectionParameters(String username, String password, String serverAddress,
      String port) {
      this.password = password;
      this.serverAddress = serverAddress;
      this.port = Integer.parseInt(port);
      this.username = username;
    }

    private String getUsername() {
      return username;
    }

    private String getPassword() {
      return password;
    }

    private String getServerAddress() {
      return serverAddress;
    }

    private int getPort() {
      return port;
    }

  }
}

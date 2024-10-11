package api;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Client to execute Linux commands via ssh using Java
 */
public class JschBase {

    protected static String toString(InputStream inputStream) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString();//(StandardCharsets.UTF_8);
    }

    protected static Session initSession(Authorization authorization) throws JSchException {
        JSch jsch = new JSch();
        Session session = jsch.getSession(authorization.login, authorization.host);
        session.setPassword(authorization.password);
        session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
        //If you use RSA key then you have to point on it.
//        jsch.addIdentity("~/.ssh/id_rsa");
        // yes, JSch will never automatically add host keys to the $HOME/.ssh/known_hosts file, and refuses to connect to hosts whose host key has changed. This property forces the user to manually add all new hosts.
        // no, JSch will automatically add new host keys to the user known hosts files
        // ask, new host keys will be added to the user known host files only after the user has confirmed that
        session.setConfig("StrictHostKeyChecking", "no");
        return session;
    }

    protected static void closeConnection(Channel channel, Session session) {
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    public static class Authorization {
        String host;
        String login;
        String password;

        public Authorization (String host, String login, String password) {
            this.host = host;
            this.login = login;
            this.password = password;
        }
    }
}

package api;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Client to execute Linux commands via ssh using Java
 */
public class SshClient extends JschBase {

    public static String execute(String command, Authorization authorization, String chan) {
        Session session = null;
        ChannelExec channel = null;
        try {
            session = initSession(authorization);
            session.connect(10000);
            // Opens a new channel of some type over this connection
            // * shell - ChannelShell
            // * exec - ChannelExec
            // * direct-tcpip - ChannelDirectTCPIP
            // * sftp - ChannelSftp
            // * subsystem - ChannelSubsystem
            channel = (ChannelExec) session.openChannel(chan);
            channel.setCommand(command);
            channel.setInputStream(null);
            channel.setErrStream(System.err);
            InputStream inputStream = channel.getInputStream();
            channel.connect(10000);

            String result = toString(inputStream);
//            String exitCode = channel.getExitStatus();
            return result; //Arrays.asList(result.split("\n"));
        } catch (JSchException | IOException e) {
            throw new RuntimeException(e);
        } finally {
            closeConnection(channel, session);
        }
    }
}

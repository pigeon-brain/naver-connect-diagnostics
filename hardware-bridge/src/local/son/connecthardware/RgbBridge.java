package local.son.connecthardware;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class RgbBridge {
    static final String SOCKET = "local.son.connecthardware.rgb.v1";
    static final int GET = 1;
    static final int SET = 2;
    static final int GET_MAX = 3;
    static final int MAX_BRIGHTNESS = 255;

    public enum Channel {
        RED("red"), GREEN("green"), BLUE("blue"),
        RED_IC("red_ic"), GREEN_IC("green_ic"), BLUE_IC("blue_ic"),
        RED_MSR("red_msr"), GREEN_MSR("green_msr"), BLUE_MSR("blue_msr");

        final String node;

        Channel(String node) {
            this.node = node;
        }
    }

    private RgbBridge() {}

    public static int get(Channel channel) throws IOException {
        return request(GET, channel, 0);
    }

    public static int maximum(Channel channel) throws IOException {
        return request(GET_MAX, channel, 0);
    }

    public static void set(Channel channel, int brightness) throws IOException {
        if (brightness < 0 || brightness > MAX_BRIGHTNESS) {
            throw new IllegalArgumentException("Brightness must be between 0 and 255");
        }
        request(SET, channel, brightness);
    }

    private static int request(int command, Channel channel, int brightness) throws IOException {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(750);
            if (socket.getPeerCredentials().getUid() != 0) {
                throw new IOException("RGB bridge is not running as root");
            }
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeByte(command);
            out.writeByte(channel.ordinal());
            if (command == SET) {
                out.writeByte(brightness);
            }
            out.flush();
            int result = new DataInputStream(socket.getInputStream()).readShort();
            if (result < 0 || result > MAX_BRIGHTNESS) {
                throw new IOException("RGB bridge rejected the operation");
            }
            return result;
        }
    }
}

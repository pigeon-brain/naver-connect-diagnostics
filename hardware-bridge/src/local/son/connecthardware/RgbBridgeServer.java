package local.son.connecthardware;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public final class RgbBridgeServer {
    private static final File LEDS = new File("/sys/class/leds");

    private RgbBridgeServer() {}

    public static void main(String[] args) throws IOException {
        if (Process.myUid() != 0 || args.length == 0) {
            throw new IllegalArgumentException("Run as root with one or more allowed app UIDs");
        }
        Set<Integer> allowed = new HashSet<>();
        for (String arg : args) {
            int uid = Integer.parseInt(arg);
            if (uid < 0 || uid % 100000 < 10000) {
                throw new IllegalArgumentException("An application UID is required: " + arg);
            }
            allowed.add(uid);
        }
        try (LocalServerSocket server = new LocalServerSocket(RgbBridge.SOCKET)) {
            Thread card = new Thread(() -> CardBridgeServer.run(allowed), "CardReaderBridge");
            card.setDaemon(true);
            card.start();
            while (true) {
                LocalSocket accepted = server.accept();
                try (LocalSocket socket = accepted) {
                    if (allowed.contains(socket.getPeerCredentials().getUid())) {
                        socket.setSoTimeout(500);
                        handle(socket);
                    }
                } catch (IOException ignored) {
                    // A disconnected client should not stop the service.
                }
            }
        }
    }

    private static void handle(LocalSocket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int command = in.readUnsignedByte();
        int index = in.readUnsignedByte();
        RgbBridge.Channel[] channels = RgbBridge.Channel.values();
        int result = -1;
        if (index < channels.length) {
            File directory = new File(LEDS, channels[index].node);
            File brightness = new File(directory, "brightness");
            try {
                if (command == RgbBridge.GET) {
                    result = readBrightness(brightness);
                } else if (command == RgbBridge.GET_MAX) {
                    result = Math.min(RgbBridge.MAX_BRIGHTNESS, readBrightness(new File(directory, "max_brightness")));
                } else if (command == RgbBridge.SET) {
                    int requested = in.readUnsignedByte();
                    if (requested <= readBrightness(new File(directory, "max_brightness"))) {
                        try (FileOutputStream out = new FileOutputStream(brightness)) {
                            out.write(Integer.toString(requested).getBytes(StandardCharsets.US_ASCII));
                        }
                        result = requested;
                    }
                }
            } catch (IOException | NumberFormatException ignored) {
                // The caller receives an operation failure without exposing paths.
            }
        }
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeShort(result);
        out.flush();
    }

    private static int readBrightness(File file) throws IOException {
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            return Integer.parseInt(in.readLine().trim());
        }
    }
}

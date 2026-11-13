package local.son.connecthardware;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class CardBridgeServer {
    private static final String FIRMWARE = "\\002\\375\\000\\001\\063\\315";
    private static final String STATUS = "\\002\\000\\010\\001\\373\\021\\062\\060\\061\\003\\005\\272";
    private static final String ACK = "\\006\\006\\006";
    private static final String DEVICE = "/dev/ttyS1";
    private static final File UART_LOCK = new File("/dev/.ts201-reader-probe-lock");

    private CardBridgeServer() {}

    static void run(Set<Integer> allowed) {
        try (LocalServerSocket server = new LocalServerSocket(CardBridge.SOCKET)) {
            while (true) {
                LocalSocket accepted = server.accept();
                try (LocalSocket socket = accepted) {
                    if (!allowed.contains(socket.getPeerCredentials().getUid())) continue;
                    socket.setSoTimeout(500);
                    if (socket.getInputStream().read() == CardBridge.STATUS) {
                        socket.getOutputStream().write(query().ordinal());
                    }
                } catch (IOException ignored) {
                    // A disconnected client should not stop the service.
                }
            }
        } catch (IOException e) {
            System.err.println("Card reader listener failed: " + e.getMessage());
        }
    }

    private static CardBridge.Presence query() {
        if (!UART_LOCK.mkdir()) return CardBridge.Presence.UNKNOWN;
        try {
            byte[] firmware = exchange(FIRMWARE, true);
            try {
                if (firmware.length == 0) return CardBridge.Presence.UNKNOWN;
                if (!CardFrames.supportedReader(firmware)) return CardBridge.Presence.UNSUPPORTED_READER;
            } finally {
                Arrays.fill(firmware, (byte) 0);
            }
            byte[] reply = exchange(STATUS, true);
            int status;
            try {
                status = CardFrames.status(reply);
            } finally {
                Arrays.fill(reply, (byte) 0);
            }
            if (status >= 0) exchange(ACK, false);
            if (status == '0') return CardBridge.Presence.INSERTED;
            if (status == '1') return CardBridge.Presence.REMOVED;
        } catch (IOException ignored) {
            // Report reader I/O failure without returning UART bytes.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (!UART_LOCK.delete()) System.err.println("Could not release reader UART lock");
        }
        return CardBridge.Presence.UNKNOWN;
    }

    private static byte[] exchange(String request, boolean read) throws IOException, InterruptedException {
        String command = "set -e; "
                + "test \"$(cat /sys/class/tty/ttyS1/dev)\" = 4:65; "
                + "test \"$(basename \"$(readlink -f /sys/class/tty/ttyS1/device/driver)\")\" = mt6577-uart; "
                + "test ! -L " + DEVICE + " && test -c " + DEVICE + "; "
                + "test \"$(stat -c %t:%T " + DEVICE + ")\" = 4:41; "
                + "exec 3<>" + DEVICE + "; original=$(stty -g <&3); "
                + "trap 'stty \"$original\" <&3' EXIT; "
                + "trap 'exit 1' HUP INT TERM; "
                + "stty raw -echo -ixon -ixoff -crtscts cs8 -cstopb -parenb clocal 115200 <&3; "
                + "printf '" + request + "' >&3;";
        if (read) command += " timeout -s KILL 1 dd bs=1 count=64 <&3 2>/dev/null || [ $? -eq 137 ]";
        java.lang.Process child = new ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectError(new java.io.File("/dev/null")).start();
        try {
            if (!child.waitFor(2200, TimeUnit.MILLISECONDS)) throw new IOException("Reader timeout");
            if (child.exitValue() != 0) throw new IOException("Reader command failed");
            try (InputStream input = child.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream(64)) {
                for (int value; output.size() < 64 && (value = input.read()) != -1;) output.write(value);
                return output.toByteArray();
            }
        } finally {
            if (child.isAlive()) {
                child.destroy();
                if (!child.waitFor(1200, TimeUnit.MILLISECONDS)) {
                    child.destroyForcibly();
                    child.waitFor(200, TimeUnit.MILLISECONDS);
                }
            }
        }
    }
}

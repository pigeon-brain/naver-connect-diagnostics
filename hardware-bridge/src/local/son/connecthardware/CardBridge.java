package local.son.connecthardware;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.IOException;

public final class CardBridge {
    static final String SOCKET = "local.son.connecthardware.card.v1";
    static final int STATUS = 1;

    public enum Presence {
        INSERTED, REMOVED, UNSUPPORTED_READER, UNKNOWN
    }

    private CardBridge() {}

    public static Presence presence() throws IOException {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(8000);
            if (socket.getPeerCredentials().getUid() != 0) throw new IOException("Invalid reader service owner");
            socket.getOutputStream().write(STATUS);
            int result = socket.getInputStream().read();
            if (result < 0 || result >= Presence.values().length) throw new IOException("Invalid reader service reply");
            return Presence.values()[result];
        }
    }
}

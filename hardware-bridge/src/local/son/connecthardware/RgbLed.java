package local.son.connecthardware;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class RgbLed {
    private final Path directory;
    private final RgbBridge.Channel channel;
    private boolean bridged;

    public RgbLed(Path root, RgbBridge.Channel channel, boolean useBridge) {
        directory = root.resolve(channel.node);
        this.channel = useBridge ? channel : null;
    }

    public boolean directWritable() {
        return Files.isWritable(directory.resolve("brightness"));
    }

    public boolean usesBridge() {
        return bridged;
    }

    public void prepare() throws IOException {
        boolean requiresBridge = !directWritable();
        if (requiresBridge && channel == null) {
            throw new IOException("표시등 쓰기 권한 없음: " + directory.getFileName());
        }
        bridged = requiresBridge;
    }

    public int get() throws IOException {
        if (!bridged) {
            try {
                return read("brightness");
            } catch (IOException e) {
                if (channel == null) throw e;
                bridged = true;
            }
        }
        return RgbBridge.get(channel);
    }

    public int maximum() throws IOException {
        if (!bridged) {
            try {
                return read("max_brightness");
            } catch (IOException e) {
                if (channel == null) throw e;
                bridged = true;
            }
        }
        return RgbBridge.maximum(channel);
    }

    public void set(int value) throws IOException {
        if (!bridged) {
            try (OutputStream out = Files.newOutputStream(directory.resolve("brightness"), StandardOpenOption.WRITE)) {
                out.write((value + "\n").getBytes(StandardCharsets.US_ASCII));
                return;
            } catch (IOException e) {
                if (channel == null || value > RgbBridge.MAX_BRIGHTNESS) throw e;
                bridged = true;
            }
        }
        RgbBridge.set(channel, value);
    }

    private int read(String attribute) throws IOException {
        try {
            return Integer.parseInt(new String(Files.readAllBytes(directory.resolve(attribute)),
                    StandardCharsets.US_ASCII).trim());
        } catch (NumberFormatException e) {
            throw new IOException("밝기 값 읽기 실패: " + directory.getFileName(), e);
        }
    }
}

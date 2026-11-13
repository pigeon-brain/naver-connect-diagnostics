package local.son.connecthardware;

import java.nio.charset.StandardCharsets;

final class CardFrames {
    private CardFrames() {}

    static boolean supportedReader(byte[] data) {
        int offset = -1;
        for (int i = 0; i + 31 <= data.length; i++) {
            if (data[i] != 2 || (data[i + 1] & 255) != 0xfd || data[i + 2] != 0
                    || data[i + 3] != 0x26 || data[i + 4] != 0 || data[i + 5] != 0x33) continue;
            int xor = 0;
            for (int j = i; j < i + 31; j++) xor ^= data[j] & 255;
            if (xor == 0) {
                if (offset >= 0) return false;
                offset = i;
            }
        }
        return offset >= 0
                && new String(data, offset + 6, 7, StandardCharsets.US_ASCII).equals("NC1-KIC")
                && new String(data, offset + 22, 6, StandardCharsets.US_ASCII).equals("XR0012");
    }

    static int status(byte[] data) {
        int result = -1;
        for (int i = 0; i + 14 <= data.length; i++) {
            if (data[i] != 2 || data[i + 1] != 0 || data[i + 2] != 10
                    || (data[i + 4] & 255) != 0xfb || data[i + 5] != 0x11
                    || data[i + 6] != 0x32 || data[i + 11] != 3) continue;
            int crc = crc(data, i + 1, i + 12);
            if ((data[i + 12] & 255) == (crc & 255) && (data[i + 13] & 255) == (crc >> 8)) {
                if (result >= 0) return -1;
                result = data[i + 10] & 255;
            }
        }
        return result;
    }

    static int crc(byte[] data, int start, int end) {
        int crc = 0xffff;
        for (int i = start; i < end; i++) {
            int value = data[i] & 255;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc >> 1) ^ (((crc ^ value) & 1) != 0 ? 0x8005 : 0);
                value >>= 1;
            }
        }
        return crc ^ 0xffff;
    }
}

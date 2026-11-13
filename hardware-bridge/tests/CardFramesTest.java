package local.son.connecthardware;

import java.util.Arrays;

public final class CardFramesTest {
    public static void main(String[] args) {
        byte[] version = hex("02 fd 00 26 00 33 4e 43 31 2d 4b 49 43 2d 4d 50 32 35 30 30 30 31 58 52 30 30 31 32 30 34 81");
        check(CardFrames.supportedReader(version));
        version[27] ^= 1;
        version[30] ^= 1;
        check(!CardFrames.supportedReader(version));

        byte[] reply = hex("06 06 06 02 00 0a 01 fb 11 32 30 31 00 30 03 00 00");
        checksum(reply);
        check(CardFrames.status(reply) == '0');
        reply[13] = '1';
        checksum(reply);
        check(CardFrames.status(reply) == '1');
        check(CardFrames.status(Arrays.copyOf(reply, reply.length - 1)) == -1);
        reply[16] ^= 1;
        check(CardFrames.status(reply) == -1);
        reply[9] = 0x20;
        checksum(reply);
        check(CardFrames.status(reply) == -1);
        System.out.println("KICC firmware and IC status parsing passed");
    }

    private static void checksum(byte[] reply) {
        int crc = CardFrames.crc(reply, 4, 15);
        reply[15] = (byte) crc;
        reply[16] = (byte) (crc >> 8);
    }

    private static byte[] hex(String value) {
        String[] bytes = value.split(" ");
        byte[] result = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) result[i] = (byte) Integer.parseInt(bytes[i], 16);
        return result;
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }
}

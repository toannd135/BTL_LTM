package ftpserver.bin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * Mot khung (frame) cua giao thuc BFTP.
 *
 * <pre>
 *  0      2     3     4          8              8+len      12+len
 *  +------+-----+-----+----------+--------------+----------+
 *  |MAGIC | VER |TYPE |  LENGTH  |   PAYLOAD    |  CRC32   |
 *  +------+-----+-----+----------+--------------+----------+
 *   2B     1B    1B    4B (BE)    LENGTH byte    4B (BE)
 * </pre>
 * CRC32 tinh tren toan bo header + payload. Phia nhan doc dung 8 byte header, kiem tra magic/version,
 * doc dung LENGTH byte payload roi 4 byte CRC — nho vay ranh gioi message luon ro rang tren TCP stream.
 */
public record Frame(int type, byte[] payload) {

    public static final int MAGIC = 0xBF7A;
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 8;
    public static final int MAX_PAYLOAD = 4 * 1024 * 1024;

    // --- loai thong diep ---
    public static final int AUTH = 0x01;
    public static final int AUTH_OK = 0x02;
    public static final int QUIT = 0x0F;
    public static final int LIST = 0x10;
    public static final int LIST_RESP = 0x11;
    public static final int MKD = 0x12;
    public static final int RMD = 0x13;
    public static final int DELE = 0x14;
    public static final int STAT = 0x15;
    public static final int STAT_RESP = 0x16;
    public static final int HASH = 0x17;
    public static final int HASH_RESP = 0x18;
    public static final int GET = 0x20;
    public static final int GET_META = 0x21;
    public static final int DATA = 0x22;
    public static final int DATA_END = 0x23;
    public static final int DATA_ABORT = 0x24;
    public static final int PUT = 0x30;
    public static final int PUT_DONE = 0x31;
    public static final int OK = 0x7E;
    public static final int ERROR = 0x7F;

    // --- ma loi ---
    public static final int E_BAD_REQUEST = 400;
    public static final int E_AUTH_REQUIRED = 401;
    public static final int E_FORBIDDEN = 403;
    public static final int E_NOT_FOUND = 404;
    public static final int E_CONFLICT = 409;
    public static final int E_ABORTED = 499;
    public static final int E_INTERNAL = 500;

    /** Loi giao thuc: sai magic/version, payload qua lon, sai CRC. */
    public static class ProtocolException extends IOException {
        public ProtocolException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    public interface PayloadWriter {
        void write(DataOutputStream d) throws IOException;
    }

    public static byte[] pack(PayloadWriter w) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        w.write(new DataOutputStream(bos));
        return bos.toByteArray();
    }

    public DataInputStream reader() {
        return new DataInputStream(new ByteArrayInputStream(payload));
    }

    public static void write(DataOutputStream out, int type, byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD) {
            throw new ProtocolException("Payload qua lon: " + payload.length);
        }
        byte[] header = ByteBuffer.allocate(HEADER_SIZE)
                .putShort((short) MAGIC).put((byte) VERSION).put((byte) type).putInt(payload.length).array();
        CRC32 crc = new CRC32();
        crc.update(header);
        crc.update(payload);
        out.write(header);
        out.write(payload);
        out.writeInt((int) crc.getValue());
        out.flush();
    }

    /** Nem EOFException neu stream dong dung ranh gioi frame; ProtocolException neu frame hong. */
    public static Frame read(DataInputStream in) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        in.readFully(header);
        ByteBuffer hb = ByteBuffer.wrap(header);
        int magic = hb.getShort() & 0xFFFF;
        int version = hb.get() & 0xFF;
        int type = hb.get() & 0xFF;
        int length = hb.getInt();
        if (magic != MAGIC) {
            throw new ProtocolException(String.format("Sai magic 0x%04X", magic));
        }
        if (version != VERSION) {
            throw new ProtocolException("Phien ban khong ho tro: " + version);
        }
        if (length < 0 || length > MAX_PAYLOAD) {
            throw new ProtocolException("Do dai payload khong hop le: " + length);
        }
        byte[] payload = new byte[length];
        in.readFully(payload);
        int expected = in.readInt();
        CRC32 crc = new CRC32();
        crc.update(header);
        crc.update(payload);
        if ((int) crc.getValue() != expected) {
            throw new ProtocolException("Sai CRC32 cua frame");
        }
        return new Frame(type, payload);
    }
}

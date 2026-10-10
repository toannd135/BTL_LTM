package ftpclient.net;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/** Khung BFTP phía client: MAGIC(2) VER(1) TYPE(1) LEN(4) PAYLOAD CRC32(4). Khớp ftpserver.bin.Frame. */
record BftpFrame(int type, byte[] payload) {

    static final int MAGIC = 0xBF7A;
    static final int VERSION = 1;
    static final int HEADER_SIZE = 8;
    static final int MAX_PAYLOAD = 4 * 1024 * 1024;
    /** Hau to file tam phia server khi PUT. Khop BinaryClientHandler.PART_SUFFIX. */
    static final String PART_SUFFIX = ".part";

    static final int AUTH = 0x01, AUTH_OK = 0x02, QUIT = 0x0F;
    static final int LIST = 0x10, LIST_RESP = 0x11, MKD = 0x12, RMD = 0x13, DELE = 0x14;
    static final int STAT = 0x15, STAT_RESP = 0x16, HASH = 0x17, HASH_RESP = 0x18;
    static final int GET = 0x20, GET_META = 0x21, DATA = 0x22, DATA_END = 0x23, DATA_ABORT = 0x24;
    static final int PUT = 0x30, PUT_DONE = 0x31, OK = 0x7E, ERROR = 0x7F;

    interface PayloadWriter {
        void write(DataOutputStream d) throws IOException;
    }

    static byte[] pack(PayloadWriter w) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        w.write(new DataOutputStream(bos));
        return bos.toByteArray();
    }

    static void write(DataOutputStream out, int type, byte[] payload) throws IOException {
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

    static BftpFrame read(DataInputStream in) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        in.readFully(header);
        ByteBuffer hb = ByteBuffer.wrap(header);
        int magic = hb.getShort() & 0xFFFF;
        int version = hb.get() & 0xFF;
        int type = hb.get() & 0xFF;
        int length = hb.getInt();
        if (magic != MAGIC || version != VERSION || length < 0 || length > MAX_PAYLOAD) {
            throw new IOException("Khung BFTP không hợp lệ");
        }
        byte[] payload = new byte[length];
        in.readFully(payload);
        int expected = in.readInt();
        CRC32 crc = new CRC32();
        crc.update(header);
        crc.update(payload);
        if ((int) crc.getValue() != expected) {
            throw new IOException("Sai CRC32 của khung BFTP");
        }
        return new BftpFrame(type, payload);
    }

    DataInputStream reader() {
        return new DataInputStream(new java.io.ByteArrayInputStream(payload));
    }
}

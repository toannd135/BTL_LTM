package ftpserver.bin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;

import org.junit.jupiter.api.Test;

class FrameTest {

    private static byte[] encode(int type, byte[] payload) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Frame.write(new DataOutputStream(bos), type, payload);
        return bos.toByteArray();
    }

    private static DataInputStream stream(byte[] b) {
        return new DataInputStream(new ByteArrayInputStream(b));
    }

    @Test
    void roundTrip_preservesTypeAndPayload() throws Exception {
        byte[] encoded = encode(Frame.DATA, new byte[] {1, 2, 3});
        Frame f = Frame.read(stream(encoded));
        assertEquals(Frame.DATA, f.type());
        assertArrayEquals(new byte[] {1, 2, 3}, f.payload());
    }

    @Test
    void twoFramesBackToBack_areSplitAtTheirBoundaries() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(encode(Frame.OK, new byte[0]));
        bos.write(encode(Frame.DATA, new byte[] {9}));
        DataInputStream in = stream(bos.toByteArray());
        assertEquals(Frame.OK, Frame.read(in).type());
        assertArrayEquals(new byte[] {9}, Frame.read(in).payload());
    }

    @Test
    void corruptedPayload_failsCrc() throws Exception {
        byte[] encoded = encode(Frame.DATA, new byte[] {1, 2, 3});
        encoded[Frame.HEADER_SIZE] ^= 0x01;
        assertThrows(Frame.ProtocolException.class, () -> Frame.read(stream(encoded)));
    }

    @Test
    void wrongMagic_isRejected() throws Exception {
        byte[] encoded = encode(Frame.OK, new byte[0]);
        encoded[0] = 0x00;
        assertThrows(Frame.ProtocolException.class, () -> Frame.read(stream(encoded)));
    }

    @Test
    void oversizedLength_isRejectedBeforeAllocating() throws Exception {
        byte[] encoded = encode(Frame.OK, new byte[0]);
        encoded[4] = 0x7F; // length ~ 2GB
        assertThrows(Frame.ProtocolException.class, () -> Frame.read(stream(encoded)));
    }

    @Test
    void truncatedStream_throwsEof() throws Exception {
        byte[] encoded = encode(Frame.DATA, new byte[] {1, 2, 3});
        byte[] cut = java.util.Arrays.copyOf(encoded, encoded.length - 2);
        assertThrows(EOFException.class, () -> Frame.read(stream(cut)));
    }
}

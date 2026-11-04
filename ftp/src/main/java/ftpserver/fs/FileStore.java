package ftpserver.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Noi luu tru that cua tep. Moi {@link Path} truyen vao la duong dan tuyet doi "ao" tinh tu root.dir (da qua
 * {@link PathResolver} nen khong thoat ra ngoai root); backend tu anh xa sang dia (local) hoac object key (S3/SeaweedFS).
 */
public interface FileStore {

    /** Mo ta ngan cho log khoi dong. */
    String describe();

    boolean exists(Path p);
    boolean isDirectory(Path p);
    boolean isFile(Path p);
    long size(Path p) throws IOException;
    Instant lastModified(Path p) throws IOException;

    /** Liet ke con truc tiep cua thu muc (khong de quy), khong dam bao thu tu. */
    List<FileInfo> list(Path dir) throws IOException;

    /** Tao thu muc; cha phai ton tai ({@link java.nio.file.NoSuchFileException}), da co thi {@link java.nio.file.FileAlreadyExistsException}. */
    void mkdir(Path dir) throws IOException;
    /** Tao thu muc cung cac cap cha con thieu; khong loi neu da co. */
    void mkdirs(Path dir) throws IOException;

    /** Xoa tep, hoac thu muc rong ({@link java.nio.file.DirectoryNotEmptyException} neu con noi dung). */
    void delete(Path p) throws IOException;

    /** Doi ten/di chuyen tep hoac ca cay thu muc. Noi goi da kiem tra dich chua ton tai. */
    void move(Path from, Path to) throws IOException;

    /** Doc tep tu byte {@code offset}. */
    InputStream read(Path file, long offset) throws IOException;

    /**
     * Bat dau ghi tep. {@code append}: noi them vao cuoi; {@code offset > 0}: giu {@code offset} byte dau cua tep co san roi ghi tiep.
     * Neu ca hai deu khong: ghi de toan bo; tep chi xuat hien khi {@link Upload#commit()} (huy giua chung -> {@link Upload#abort()} khong de lai gi).
     * {@code tag} dung dat ten tep tam (vd id phien).
     */
    Upload beginUpload(Path target, boolean append, long offset, String tag) throws IOException;

    /** Duong dan tep that tren dia neu backend la local (de quet khong can tai ve), nguoc lai rong. */
    default Optional<Path> localPath(Path p) {
        return Optional.empty();
    }

    record FileInfo(String name, boolean directory, long size, Instant modified) {}

    interface Upload {
        OutputStream stream();
        /** Dong luong ghi roi mo doc lai du lieu da nhan (chua commit) de quet truoc khi dua vao kho. */
        InputStream reopen() throws IOException;
        /** Hoan tat: dua du lieu da ghi vao kho. */
        void commit() throws IOException;
        /** Bo du lieu dang ghi do (idempotent, khong nem loi). */
        void abort();
    }
}

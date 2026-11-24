package ftpclient.net;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hàng đợi truyền tệp chạy nền trên một kết nối FTP RIÊNG ({@code transferSession}), tách khỏi kết nối duyệt
 * thư mục ({@code browseSession}) của giao diện — giống kiến trúc FileZilla.
 *
 * <p>Vì sao cần hai kết nối: kênh điều khiển FTP là tuần tự (gửi lệnh, chờ trả lời), nên khi một kết nối đang
 * RETR tệp 2 GB thì mọi lệnh LIST/CWD trên cùng kết nối đó phải chờ tệp tải xong. Có kết nối riêng cho truyền
 * tệp thì người dùng vẫn duyệt thư mục bình thường trong lúc tệp lớn đang chạy.
 *
 * <p>Cách hoạt động:
 * <ul>
 *   <li>Các mục ({@link Job}) chạy lần lượt trên một luồng nền tên {@code ftp-transfer}.</li>
 *   <li>Kết nối truyền mở lười (lần đầu có mục cần chạy) qua {@link Connector}; bị server ngắt (vd quá thời gian
 *       rảnh) thì mục kế tiếp tự kết nối lại.</li>
 *   <li>Mỗi mục mang thư mục từ xa của nó ({@code remoteDir}, lấy từ PWD của kết nối duyệt lúc xếp hàng); kết nối
 *       truyền tự nhớ thư mục hiện tại và chỉ CWD khi khác.</li>
 *   <li>{@link Job#cancel()}: mục đang chờ thì bỏ qua; mục đang chạy thì cắt kênh dữ liệu.</li>
 * </ul>
 *
 * <p>Lớp thuần Java (không import javafx.*); {@link Listener} được gọi trên luồng nền, giao diện tự chuyển về
 * luồng JavaFX.
 */
public final class TransferQueue implements Closeable {

    /** Mở và đăng nhập một kết nối mới (gọi trên luồng truyền). */
    @FunctionalInterface
    public interface Connector {
        RemoteSession open() throws IOException, FtpException;
    }

    /** Nhận thông báo khi một mục đổi trạng thái hoặc có tiến độ mới (tối đa ~20 lần/giây mỗi mục). */
    @FunctionalInterface
    public interface Listener {
        void changed(Job job);
    }

    public enum Kind { DOWNLOAD, UPLOAD, UPLOAD_DIR }

    public enum State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    /** Khoảng tối thiểu giữa hai lần báo tiến độ của cùng một mục, để không làm ngập luồng giao diện. */
    private static final long PROGRESS_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(50);

    /** Một mục trong hàng đợi. Các trường trạng thái được ghi ở luồng truyền, đọc ở luồng khác. */
    public final class Job {
        private final int id;
        private final Kind kind;
        private final String remoteDir;
        private final String remoteName;
        private final Path localPath;
        private final long expectedSize;
        private volatile State state = State.QUEUED;
        private volatile long done;
        private volatile long total;
        private volatile String current = "";
        private volatile String result = "";
        private volatile Throwable error;
        private volatile boolean cancelRequested;
        private volatile long startedNs;
        private volatile long finishedNs;
        private long lastNotifyNs;

        private Job(Kind kind, String remoteDir, String remoteName, Path localPath, long expectedSize) {
            this.id = SEQ.incrementAndGet();
            this.kind = kind;
            this.remoteDir = remoteDir;
            this.remoteName = remoteName;
            this.localPath = localPath;
            this.expectedSize = expectedSize;
            this.total = Math.max(expectedSize, 0);
        }

        public int id() { return id; }
        public Kind kind() { return kind; }
        /** Thư mục từ xa nơi mục này tải lên/tải về. */
        public String remoteDir() { return remoteDir; }
        /** Tên tệp/thư mục bên máy chủ. */
        public String remoteName() { return remoteName; }
        public Path localPath() { return localPath; }
        public State state() { return state; }
        /** Số byte đã truyền. */
        public long done() { return done; }
        /** Tổng số byte (0 nếu chưa biết). */
        public long total() { return total; }
        /** Tệp đang truyền (với tải thư mục); rỗng nếu không có. */
        public String current() { return current; }
        /** Ghi chú khi xong (FTPS/FTP thường, số tệp...). */
        public String result() { return result; }
        public Throwable error() { return error; }

        /** Thời gian chạy (ms); 0 nếu chưa bắt đầu. */
        public long elapsedMillis() {
            long s = startedNs;
            if (s == 0) {
                return 0;
            }
            long f = finishedNs == 0 ? System.nanoTime() : finishedNs;
            return TimeUnit.NANOSECONDS.toMillis(f - s);
        }

        public boolean finished() {
            State s = state;
            return s == State.DONE || s == State.FAILED || s == State.CANCELLED;
        }

        /** Hủy mục: đang chờ thì bỏ qua khi tới lượt, đang chạy thì cắt kênh dữ liệu. Gọi được từ mọi luồng. */
        public void cancel() {
            if (finished()) {
                return;
            }
            cancelRequested = true;
            if (state == State.RUNNING) {
                RemoteSession s = session;
                if (s != null) {
                    s.cancelTransfer();
                }
            } else {
                // đang chờ: báo ngay để giao diện cập nhật, luồng truyền sẽ bỏ qua khi tới lượt
                state = State.CANCELLED;
                listener.changed(this);
            }
        }

        private void progress(long doneBytes, long totalBytes) {
            if (cancelRequested) {
                // hủy rơi đúng lúc mục vừa chuyển sang RUNNING: cắt ở lần báo tiến độ đầu tiên
                RemoteSession s = session;
                if (s != null) {
                    s.cancelTransfer();
                }
            }
            done = doneBytes;
            if (totalBytes > 0) {
                total = totalBytes;
            }
            long now = System.nanoTime();
            if (now - lastNotifyNs >= PROGRESS_INTERVAL_NS) {
                lastNotifyNs = now;
                listener.changed(this);
            }
        }

        @Override
        public String toString() {
            return "#" + id + " " + kind + " " + remoteDir + " " + remoteName + " " + state;
        }
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final Connector connector;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ftp-transfer");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger pending = new AtomicInteger();

    /** Kết nối truyền hiện tại (null khi chưa mở hoặc vừa bị đóng do lỗi). Chỉ luồng truyền ghi. */
    private volatile RemoteSession session;
    /** Thư mục đầu tiên sau khi đăng nhập (home của user) và thư mục hiện tại của kết nối truyền. */
    private String homeDir;
    private String currentDir;
    private volatile boolean closed;
    private int connectCount;

    public TransferQueue(Connector connector, Listener listener) {
        this.connector = connector;
        this.listener = listener;
    }

    /** Xếp hàng tải tệp {@code name} trong {@code remoteDir} về {@code dest}. */
    public Job download(String remoteDir, String name, Path dest, long size) {
        return submit(new Job(Kind.DOWNLOAD, remoteDir, name, dest, size));
    }

    /** Xếp hàng tải tệp {@code src} lên {@code remoteDir} (giữ nguyên tên). */
    public Job upload(String remoteDir, Path src) {
        long size;
        try {
            size = Files.size(src);
        } catch (IOException e) {
            size = 0;
        }
        return submit(new Job(Kind.UPLOAD, remoteDir, src.getFileName().toString(), src, size));
    }

    /** Xếp hàng tải cả cây thư mục {@code dir} lên {@code remoteDir} (MKD nếu chưa có, dùng lại nếu đã có). */
    public Job uploadDirectory(String remoteDir, Path dir) {
        return submit(new Job(Kind.UPLOAD_DIR, remoteDir, dir.getFileName().toString(), dir, 0));
    }

    /** Số mục đang chờ hoặc đang chạy. */
    public int pending() {
        return pending.get();
    }

    /** Số lần đã mở kết nối truyền (để kiểm thử/benchmark). */
    public int connectCount() {
        return connectCount;
    }

    private Job submit(Job job) {
        if (closed) {
            throw new IllegalStateException("Hàng đợi đã đóng");
        }
        pending.incrementAndGet();
        listener.changed(job);
        executor.submit(() -> {
            try {
                run(job);
            } finally {
                pending.decrementAndGet();
            }
        });
        return job;
    }

    private void run(Job job) {
        if (job.cancelRequested || closed) {
            job.state = State.CANCELLED;
            listener.changed(job);
            return;
        }
        job.state = State.RUNNING;
        job.startedNs = System.nanoTime();
        listener.changed(job);
        try {
            try {
                execute(job);
            } catch (IOException e) {
                // kết nối truyền chết khi đang rảnh (server idle timeout): mở lại và thử một lần nếu chưa truyền byte nào
                if (job.cancelRequested || job.done > 0 || e instanceof RemoteSession.CancelledException) {
                    throw e;
                }
                dropSession();
                execute(job);
            }
            job.state = State.DONE;
        } catch (RemoteSession.CancelledException e) {
            job.state = State.CANCELLED;
            job.error = e;
        } catch (IOException e) {
            dropSession();   // trạng thái kênh điều khiển không còn tin được
            job.state = job.cancelRequested ? State.CANCELLED : State.FAILED;
            job.error = e;
        } catch (Exception e) {
            job.state = job.cancelRequested ? State.CANCELLED : State.FAILED;
            job.error = e;
        } finally {
            job.finishedNs = System.nanoTime();
            listener.changed(job);
        }
    }

    private void execute(Job job) throws IOException, FtpException {
        RemoteSession s = ensureSession();
        goTo(s, job.remoteDir);
        switch (job.kind) {
            case DOWNLOAD -> job.result = s.download(job.remoteName, job.localPath, job.expectedSize, job::progress);
            case UPLOAD -> job.result = s.upload(job.remoteName, job.localPath, job::progress);
            case UPLOAD_DIR -> job.result = uploadDirectory(s, job);
        }
        job.progress(job.total > 0 ? job.total : job.done, job.total);
    }

    private RemoteSession ensureSession() throws IOException, FtpException {
        RemoteSession s = session;
        if (s != null) {
            return s;
        }
        s = connector.open();
        connectCount++;
        try {
            homeDir = s.pwd();
        } catch (IOException | FtpException e) {
            s.close();
            throw e;
        }
        currentDir = homeDir;
        session = s;
        return s;
    }

    /**
     * Đưa kết nối truyền tới {@code dir}. Không CWD khi đã đứng đúng chỗ; với user có home riêng, server có thể
     * từ chối "CWD /" nên khi cần quay về home thì mở lại kết nối (kết nối mới luôn bắt đầu ở home).
     */
    private void goTo(RemoteSession s, String dir) throws IOException, FtpException {
        if (dir == null || dir.equals(currentDir)) {
            return;
        }
        try {
            s.cwd(dir);
            currentDir = dir;
        } catch (FtpException e) {
            if (!dir.equals(homeDir)) {
                throw e;
            }
            dropSession();
            ensureSession();
        }
    }

    private void dropSession() {
        RemoteSession s = session;
        session = null;
        currentDir = null;
        if (s != null) {
            s.close();
        }
    }

    /** Tải cây thư mục: MKD (bỏ qua nếu đã có) + CWD, tải từng mục, CDUP ra (cả khi lỗi). */
    private String uploadDirectory(RemoteSession s, Job job) throws IOException, FtpException {
        long total;
        try (var walk = Files.walk(job.localPath)) {
            total = walk.filter(Files::isRegularFile).mapToLong(f -> {
                try {
                    return Files.size(f);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }
        job.total = Math.max(total, 1);
        int[] count = {0};
        long[] doneBytes = {0};
        String start = currentDir;
        currentDir = null;   // trong lúc tải cây, thư mục hiện tại thay đổi liên tục
        uploadTree(s, job, job.localPath, count, doneBytes);
        currentDir = start;  // mỗi uploadTree tự CDUP ra nên kết thúc đúng chỗ ban đầu
        job.current = "";
        return count[0] + " tệp";
    }

    private void uploadTree(RemoteSession s, Job job, Path dir, int[] count, long[] doneBytes)
            throws IOException, FtpException {
        String name = dir.getFileName().toString();
        try {
            s.mkd(name);
        } catch (FtpException ignored) {
            // đã tồn tại: dùng lại; lỗi thật thì CWD bên dưới sẽ báo
        }
        try {
            s.cwd(name);
        } catch (FtpException e) {
            throw new FtpException(e.code(), "Không vào được thư mục '" + name
                    + "' trên máy chủ: tên này đã tồn tại nhưng là tệp hoặc thư mục bạn không có quyền đọc.");
        }
        try {
            List<Path> children;
            try (var stream = Files.list(dir)) {
                children = stream.sorted().toList();
            }
            for (Path p : children) {
                if (job.cancelRequested) {
                    throw new RemoteSession.CancelledException("Đã hủy tải thư mục");
                }
                if (Files.isDirectory(p)) {
                    uploadTree(s, job, p, count, doneBytes);
                } else if (Files.isRegularFile(p)) {
                    job.current = p.getFileName().toString();
                    long base = doneBytes[0];
                    s.upload(p.getFileName().toString(), p, (d, t) -> job.progress(base + d, job.total));
                    doneBytes[0] = base + Files.size(p);
                    count[0]++;
                }
            }
        } catch (IOException | FtpException | RuntimeException e) {
            try {
                s.cdup();
            } catch (Exception ignored) {
                // giữ lỗi gốc
            }
            throw e;
        }
        s.cdup();
    }

    /** Chờ hàng đợi chạy hết (dùng trong test/benchmark). */
    public boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (pending.get() > 0) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    /** Dừng nhận mục mới, cắt lần truyền đang chạy, gửi QUIT và đóng kết nối truyền. */
    @Override
    public void close() {
        closed = true;
        RemoteSession s = session;
        if (s != null) {
            s.cancelTransfer();
        }
        executor.submit(() -> {
            RemoteSession cur = session;
            if (cur != null) {
                cur.quit();
            }
            dropSession();
        });
        executor.shutdown();
    }
}

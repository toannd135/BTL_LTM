package ftpserver.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Luu tep trong object storage tuong thich S3 (SeaweedFS S3 gateway). Mot tep = mot object, key = prefix + duong dan ao.
 * Thu muc la tien to "a/b/": mkdir tao object rong "a/b/" (marker) de thu muc rong van ton tai; thu muc co tep thi ton tai
 * ngam. Upload duoc dem vao tep tam roi PutObject mot lan nen tep chi hien ra khi hoan tat (giong rename nguyen tu o local).
 * Gioi han: mot object toi da 5 GB (PutObject/CopyObject mot phat).
 *
 * <p>Tao boi {@code Main} khi bat s3.enabled, di kem {@link PathResolver#virtual}. Doi ten thu muc = copy + xoa tung
 * object nen khong nguyen tu.
 */
public final class S3FileStore implements FileStore {

    /** So lan thu ket noi bucket khi khoi dong (x {@link #BUCKET_RETRY_DELAY_MS} ~ 60 s). */
    private static final int BUCKET_READY_ATTEMPTS = 30;
    private static final long BUCKET_RETRY_DELAY_MS = 2000;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int COPY_BUFFER_BYTES = 65536;

    private final S3Client s3;
    private final String bucket;
    private final String prefix; // rong hoac ket thuc bang "/"
    private final String quarantineBucket; // null = khong ho tro cach ly
    private volatile boolean quarantineReady;

    public S3FileStore(S3Client s3, String bucket, String prefix) {
        this(s3, bucket, prefix, null);
    }

    /** @param quarantineBucket bucket cach ly tep nhiem (giong admin-web: FTP_S3_QUARANTINE_BUCKET), null = khong cach ly */
    public S3FileStore(S3Client s3, String bucket, String prefix, String quarantineBucket) {
        this.s3 = s3;
        this.bucket = bucket;
        String trimmed = prefix == null ? "" : prefix.replaceAll("^/+|/+$", "");
        this.prefix = trimmed.isEmpty() ? "" : trimmed + "/";
        this.quarantineBucket = quarantineBucket == null || quarantineBucket.isBlank() ? null : quarantineBucket;
    }

    /** Nhu {@link #connect(String, String, String, String, String, String)} kem bucket cach ly. */
    public static S3FileStore connect(String endpoint, String region, String accessKey, String secretKey,
                                      String bucket, String prefix, String quarantineBucket) {
        S3FileStore base = connect(endpoint, region, accessKey, secretKey, bucket, prefix);
        return new S3FileStore(base.s3, bucket, prefix, quarantineBucket);
    }

    /**
     * Copy object sang bucket cach ly (key = {@code name}, cung quy uoc admin-web) roi xoa ban goc; xoa goc loi thi
     * xoa ban sao de tep khong nam o hai noi.
     */
    @Override
    public String quarantine(Path file, String name) throws IOException {
        if (quarantineBucket == null) {
            throw new IOException("s3 quarantine bucket not configured");
        }
        String key = fileKey(file);
        String qkey = name.replace('/', '_');
        try {
            if (!quarantineReady) {
                ensureBucket(s3, quarantineBucket);
                quarantineReady = true;
            }
            s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket).sourceKey(key)
                    .destinationBucket(quarantineBucket).destinationKey(qkey).build());
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            } catch (RuntimeException e) {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(quarantineBucket).key(qkey).build());
                throw e;
            }
        } catch (RuntimeException e) {
            throw new IOException("quarantine failed: " + e.getMessage(), e);
        }
        return "s3://" + quarantineBucket + "/" + qkey;
    }

    /** Ket noi toi endpoint S3 (path-style), tao bucket neu chua co. */
    public static S3FileStore connect(String endpoint, String region, String accessKey, String secretKey,
                                      String bucket, String prefix) {
        S3Client client = buildClient(endpoint, region, accessKey, secretKey);
        // SeaweedFS co the chua san sang ngay luc khoi dong (docker compose): thu lai toi ~60 s
        RuntimeException last = null;
        for (int attempt = 0; attempt < BUCKET_READY_ATTEMPTS; attempt++) {
            try {
                ensureBucket(client, bucket);
                last = null;
                break;
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(BUCKET_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (last != null) {
            throw new IllegalStateException("Cannot reach object storage at " + endpoint + ": " + last.getMessage(), last);
        }
        return new S3FileStore(client, bucket, prefix);
    }

    private static S3Client buildClient(String endpoint, String region, String accessKey, String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                // SeaweedFS khong can/khong chac chap nhan checksum trailer mac dinh cua SDK moi
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private static void ensureBucket(S3Client client, String bucket) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            if (e.statusCode() != HTTP_NOT_FOUND) {
                throw e;
            }
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    @Override
    public String describe() {
        return "s3 bucket=" + bucket + (prefix.isEmpty() ? "" : " prefix=" + prefix);
    }

    // ---- anh xa Path ao <-> key ----

    /** Duong dan ao bo '/' dau/cuoi ("" = root). */
    private String rel(Path p) {
        String slashed = p.normalize().toString().replace('\\', '/');
        return slashed.replaceAll("^/+|/+$", "");
    }

    private String fileKey(Path p) {
        return prefix + rel(p);
    }

    /** Tien to cua thu muc ("" + prefix cho root; "prefix/a/b/" cho thu muc con). */
    private String dirPrefix(Path p) {
        String r = rel(p);
        return r.isEmpty() ? prefix : prefix + r + "/";
    }

    private boolean isRoot(Path p) {
        return rel(p).isEmpty();
    }

    /** Metadata object, hoac null neu khong ton tai. */
    private HeadObjectResponse head(String key) {
        try {
            return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception e) {
            if (e.statusCode() == HTTP_NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    /** true neu co object nao khac marker cua chinh thu muc (2 key la du de biet). */
    private boolean hasChildren(String dirPrefix) {
        ListObjectsV2Response response = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).prefix(dirPrefix).maxKeys(2).build());
        return response.contents().stream().anyMatch(o -> !o.key().equals(dirPrefix));
    }

    // ---- truy van ----

    @Override
    public boolean isFile(Path p) {
        return !isRoot(p) && head(fileKey(p)) != null;
    }

    @Override
    public boolean isDirectory(Path p) {
        if (isRoot(p)) {
            return true;
        }
        String dirKey = dirPrefix(p);
        if (head(dirKey) != null) {
            return true;
        }
        return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(dirKey).maxKeys(1).build())
                .keyCount() > 0;
    }

    @Override
    public boolean exists(Path p) {
        return isFile(p) || isDirectory(p);
    }

    @Override
    public long size(Path p) throws IOException {
        HeadObjectResponse fileHead = isRoot(p) ? null : head(fileKey(p));
        if (fileHead != null) {
            return fileHead.contentLength();
        }
        if (isDirectory(p)) {
            return 0;
        }
        throw new NoSuchFileException(p.toString());
    }

    @Override
    public Instant lastModified(Path p) throws IOException {
        HeadObjectResponse fileHead = isRoot(p) ? null : head(fileKey(p));
        if (fileHead != null) {
            return fileHead.lastModified();
        }
        if (isRoot(p)) {
            return Instant.now();
        }
        HeadObjectResponse marker = head(dirPrefix(p));
        if (marker != null) {
            return marker.lastModified();
        }
        if (isDirectory(p)) {
            return Instant.now();
        }
        throw new NoSuchFileException(p.toString());
    }

    @Override
    public List<FileInfo> list(Path dir) throws IOException {
        if (!isDirectory(dir)) {
            throw new NoSuchFileException(dir.toString());
        }
        String dirKey = dirPrefix(dir);
        List<FileInfo> entries = new ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Response page = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(dirKey).delimiter("/").continuationToken(token).build());
            for (CommonPrefix subdir : page.commonPrefixes()) {
                String name = subdir.prefix().substring(dirKey.length()).replaceAll("/$", "");
                if (!name.isEmpty()) {
                    entries.add(new FileInfo(name, true, 0, Instant.now()));
                }
            }
            for (S3Object object : page.contents()) {
                if (object.key().equals(dirKey)) {
                    continue; // marker cua chinh thu muc nay
                }
                entries.add(new FileInfo(object.key().substring(dirKey.length()), false, object.size(),
                        object.lastModified()));
            }
            token = page.isTruncated() ? page.nextContinuationToken() : null;
        } while (token != null);
        return entries;
    }

    // ---- thay doi ----

    @Override
    public void mkdir(Path dir) throws IOException {
        if (isRoot(dir) || exists(dir)) {
            throw new FileAlreadyExistsException(dir.toString());
        }
        Path parent = dir.getParent();
        if (parent != null && !isDirectory(parent)) {
            throw new NoSuchFileException(parent.toString());
        }
        putMarker(dir);
    }

    @Override
    public void mkdirs(Path dir) throws IOException {
        if (isRoot(dir) || isDirectory(dir)) {
            return;
        }
        if (isFile(dir)) {
            throw new FileAlreadyExistsException(dir.toString());
        }
        putMarker(dir);
    }

    private void putMarker(Path dir) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(dirPrefix(dir)).build(), RequestBody.empty());
    }

    @Override
    public void delete(Path p) throws IOException {
        if (!isRoot(p) && head(fileKey(p)) != null) {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(fileKey(p)).build());
            return;
        }
        if (isRoot(p) || !isDirectory(p)) {
            throw new NoSuchFileException(p.toString());
        }
        String dirKey = dirPrefix(p);
        if (hasChildren(dirKey)) {
            throw new DirectoryNotEmptyException(p.toString());
        }
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(dirKey).build());
    }

    @Override
    public void move(Path from, Path to) throws IOException {
        if (to.startsWith(from) && !to.equals(from)) {
            throw new IOException("Cannot move a directory into itself");
        }
        if (isFile(from)) {
            copyThenDelete(fileKey(from), fileKey(to));
            return;
        }
        if (!isDirectory(from)) {
            throw new NoSuchFileException(from.toString());
        }
        String src = dirPrefix(from);
        String dst = dirPrefix(to);
        List<String> keys = listKeysRecursive(src);
        if (keys.isEmpty()) {
            putMarker(to); // thu muc chi ton tai ngam nhung bi xoa het giua chung: giu cho dich ton tai
            return;
        }
        // tep truoc, marker thu muc ("x/") sau cung va sau -> truoc (thu muc con xoa truoc thu muc cha).
        // Marker khong CopyObject duoc tren SeaweedFS (la entry thu muc), nen tao marker moi o dich roi xoa marker cu.
        keys.stream().filter(k -> !k.endsWith("/")).forEach(k -> copyThenDelete(k, dst + k.substring(src.length())));
        keys.stream().filter(k -> k.endsWith("/"))
                .sorted(Comparator.comparingInt(String::length).reversed())
                .forEach(k -> {
                    s3.putObject(PutObjectRequest.builder().bucket(bucket).key(dst + k.substring(src.length())).build(),
                            RequestBody.empty());
                    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(k).build());
                });
    }

    /** Moi key nam duoi tien to (de quy, ke ca marker), doc het cac trang. */
    private List<String> listKeysRecursive(String keyPrefix) {
        List<String> keys = new ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Response page = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(keyPrefix).continuationToken(token).build());
            page.contents().forEach(o -> keys.add(o.key()));
            token = page.isTruncated() ? page.nextContinuationToken() : null;
        } while (token != null);
        return keys;
    }

    private void copyThenDelete(String srcKey, String dstKey) {
        s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket).sourceKey(srcKey)
                .destinationBucket(bucket).destinationKey(dstKey).build());
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(srcKey).build());
    }

    // ---- doc / ghi ----

    @Override
    public InputStream read(Path file, long offset) throws IOException {
        long size = size(file);
        if (offset >= size) {
            return InputStream.nullInputStream();
        }
        GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(bucket).key(fileKey(file));
        if (offset > 0) {
            request.range("bytes=" + offset + "-");
        }
        try {
            return s3.getObject(request.build());
        } catch (S3Exception e) {
            throw new IOException("S3 read failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Upload beginUpload(Path target, boolean append, long offset, String tag) throws IOException {
        Path tmp = Files.createTempFile("ftp-upload-" + tag + "-", ".tmp");
        OutputStream os;
        try {
            // APPE giu toan bo tep cu, REST giu offset byte dau; sau do ghi tiep vao cuoi tep tam.
            long keepBytes = append ? Long.MAX_VALUE : offset;
            if (keepBytes > 0 && isFile(target)) {
                copyHead(target, tmp, Math.min(keepBytes, size(target)));
            }
            os = Files.newOutputStream(tmp, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return newUpload(tmp, os, fileKey(target));
    }

    /** Chep {@code length} byte dau cua object {@code source} vao tep tam. */
    private void copyHead(Path source, Path tmp, long length) throws IOException {
        try (InputStream in = read(source, 0); OutputStream out = Files.newOutputStream(tmp)) {
            long left = length;
            byte[] buf = new byte[COPY_BUFFER_BYTES];
            while (left > 0) {
                int read = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (read < 0) {
                    break;
                }
                out.write(buf, 0, read);
                left -= read;
            }
        }
    }

    /** Upload dem qua tep tam {@code tmp}; commit PutObject mot lan roi xoa tep tam. */
    private Upload newUpload(Path tmp, OutputStream os, String key) {
        return new Upload() {
            @Override public OutputStream stream() { return os; }

            @Override public InputStream reopen() throws IOException {
                os.close();
                return Files.newInputStream(tmp);
            }

            @Override public void commit() throws IOException {
                os.close();
                try {
                    s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromFile(tmp));
                } catch (S3Exception | UncheckedIOException e) {
                    throw new IOException("S3 upload failed: " + e.getMessage(), e);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }

            @Override public void abort() {
                try {
                    os.close();
                } catch (IOException ignored) {
                }
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        };
    }
}

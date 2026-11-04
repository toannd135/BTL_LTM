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
import java.util.List;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
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
 */
public final class S3FileStore implements FileStore {

    private final S3Client s3;
    private final String bucket;
    private final String prefix; // rong hoac ket thuc bang "/"

    public S3FileStore(S3Client s3, String bucket, String prefix) {
        this.s3 = s3;
        this.bucket = bucket;
        String p = prefix == null ? "" : prefix.replaceAll("^/+|/+$", "");
        this.prefix = p.isEmpty() ? "" : p + "/";
    }

    /** Ket noi toi endpoint S3 (path-style), tao bucket neu chua co. */
    public static S3FileStore connect(String endpoint, String region, String accessKey, String secretKey,
                                      String bucket, String prefix) {
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                // SeaweedFS khong can/khong chac chap nhan checksum trailer mac dinh cua SDK moi
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        // SeaweedFS co the chua san sang ngay luc khoi dong (docker compose): thu lai toi ~60 s
        RuntimeException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                ensureBucket(client, bucket);
                last = null;
                break;
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(2000);
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

    private static void ensureBucket(S3Client client, String bucket) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
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

    private String rel(Path p) {
        String s = p.normalize().toString().replace('\\', '/');
        return s.replaceAll("^/+|/+$", "");
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

    private HeadObjectResponse head(String key) {
        try {
            return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    private boolean hasChildren(String dirPrefix) {
        ListObjectsV2Response r = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).prefix(dirPrefix).maxKeys(2).build());
        return r.contents().stream().anyMatch(o -> !o.key().equals(dirPrefix));
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
        String dp = dirPrefix(p);
        if (head(dp) != null) {
            return true;
        }
        return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(dp).maxKeys(1).build())
                .keyCount() > 0;
    }

    @Override
    public boolean exists(Path p) {
        return isFile(p) || isDirectory(p);
    }

    @Override
    public long size(Path p) throws IOException {
        HeadObjectResponse h = isRoot(p) ? null : head(fileKey(p));
        if (h != null) {
            return h.contentLength();
        }
        if (isDirectory(p)) {
            return 0;
        }
        throw new NoSuchFileException(p.toString());
    }

    @Override
    public Instant lastModified(Path p) throws IOException {
        HeadObjectResponse h = isRoot(p) ? null : head(fileKey(p));
        if (h != null) {
            return h.lastModified();
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
        String dp = dirPrefix(dir);
        List<FileInfo> out = new ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Response r = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(dp).delimiter("/").continuationToken(token).build());
            for (var cp : r.commonPrefixes()) {
                String name = cp.prefix().substring(dp.length()).replaceAll("/$", "");
                if (!name.isEmpty()) {
                    out.add(new FileInfo(name, true, 0, Instant.now()));
                }
            }
            for (S3Object o : r.contents()) {
                if (o.key().equals(dp)) {
                    continue; // marker cua chinh thu muc nay
                }
                out.add(new FileInfo(o.key().substring(dp.length()), false, o.size(), o.lastModified()));
            }
            token = r.isTruncated() ? r.nextContinuationToken() : null;
        } while (token != null);
        return out;
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
        String dp = dirPrefix(p);
        if (hasChildren(dp)) {
            throw new DirectoryNotEmptyException(p.toString());
        }
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(dp).build());
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
        List<String> keys = new ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Response r = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(src).continuationToken(token).build());
            r.contents().forEach(o -> keys.add(o.key()));
            token = r.isTruncated() ? r.nextContinuationToken() : null;
        } while (token != null);
        if (keys.isEmpty()) {
            putMarker(to); // thu muc chi ton tai ngam nhung bi xoa het giua chung: giu cho dich ton tai
            return;
        }
        // tep truoc, marker thu muc ("x/") sau cung va sau -> truoc (thu muc con xoa truoc thu muc cha).
        // Marker khong CopyObject duoc tren SeaweedFS (la entry thu muc), nen tao marker moi o dich roi xoa marker cu.
        keys.stream().filter(k -> !k.endsWith("/")).forEach(k -> copyThenDelete(k, dst + k.substring(src.length())));
        keys.stream().filter(k -> k.endsWith("/"))
                .sorted(java.util.Comparator.comparingInt(String::length).reversed())
                .forEach(k -> {
                    s3.putObject(PutObjectRequest.builder().bucket(bucket).key(dst + k.substring(src.length())).build(),
                            RequestBody.empty());
                    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(k).build());
                });
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
        GetObjectRequest.Builder req = GetObjectRequest.builder().bucket(bucket).key(fileKey(file));
        if (offset > 0) {
            req.range("bytes=" + offset + "-");
        }
        try {
            return s3.getObject(req.build());
        } catch (S3Exception e) {
            throw new IOException("S3 read failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Upload beginUpload(Path target, boolean append, long offset, String tag) throws IOException {
        Path tmp = Files.createTempFile("ftp-upload-" + tag + "-", ".tmp");
        OutputStream os;
        try {
            long keep = append ? Long.MAX_VALUE : offset;
            if (keep > 0 && isFile(target)) {
                long size = size(target);
                long n = Math.min(keep, size);
                try (InputStream in = read(target, 0); OutputStream o = Files.newOutputStream(tmp)) {
                    long left = n;
                    byte[] buf = new byte[65536];
                    while (left > 0) {
                        int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (r < 0) {
                            break;
                        }
                        o.write(buf, 0, r);
                        left -= r;
                    }
                }
            }
            os = Files.newOutputStream(tmp, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        String key = fileKey(target);
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

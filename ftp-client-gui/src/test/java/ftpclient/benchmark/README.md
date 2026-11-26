# Test và benchmark cho kiến trúc 2 kết nối

Thư mục này chứa toàn bộ test của FTP client. Mục tiêu là chứng minh rằng việc tách **kết nối duyệt** (`browseSession`) khỏi **kết nối truyền tệp** (`transferSession`, lớp `TransferQueue`) giúp giao diện không bị khựng khi đang tải tệp lớn.

Mọi test đều chạy với một **FTP server giả lập** nằm ngay trong tiến trình test, lắng nghe ở `127.0.0.1` trên một cổng ngẫu nhiên. Vì vậy không cần bật server thật, PostgreSQL hay Docker.

## Các tệp trong thư mục

| Tệp | Vai trò |
|---|---|
| `FakeFtpServer.java` | FTP server giả lập. Có hai núm chỉnh để giống mạng thật: giới hạn băng thông và độ trễ mỗi trả lời (RTT). Nội dung tệp được sinh theo công thức cố định nên kiểm tra được từng byte tải về. |
| `BenchStats.java` | Tiện ích tính phân vị (p50, p95), in bảng ra console và ghi CSV. |
| `TransferQueueTest.java` | **Test chức năng** (7 test, khoảng 3 giây): tải về đúng nội dung, tải lên đúng thư mục, tải cả thư mục, hủy mục đang chờ, hủy mục đang chạy, tự kết nối lại khi server ngắt, và duyệt thư mục không bị chặn khi đang truyền. |
| `ConnectionHealthTest.java` | **Test chức năng** (6 test, khoảng 2 giây): server không trả lời thì báo mất kết nối sau đúng thời gian chờ (không treo), server đóng kết nối hoặc trả "421", NOOP giữ kết nối, kết nối lại bằng phiên mới, và server trả "226" chậm sau khi tải lên không bị hiểu nhầm là mất kết nối. |
| `BrowseLatencyBenchmark.java` | **Benchmark 1:** đo độ trễ duyệt thư mục trong lúc tải tệp lớn, so sánh kiến trúc 1 kết nối (cũ) với 2 kết nối (mới). |
| `ThroughputBenchmark.java` | **Benchmark 2:** đo thông lượng. Một là tải tệp lớn trực tiếp so với qua hàng đợi. Hai là tải lên nhiều tệp nhỏ. |

## Cách chạy

Mọi lệnh đều chạy trong thư mục `ftp-client-gui/`. Cần JDK 21 và Maven.

### 1. Test chức năng (chạy mặc định)

```bash
mvn test
```

Lệnh này **chỉ** chạy các test chức năng (`TransferQueueTest`, `ConnectionHealthTest`), vì các benchmark được gắn `@Tag("benchmark")` và bị loại khỏi lần chạy mặc định. CI cũng chạy đúng lệnh này thông qua `mvn verify`.

Chạy riêng một test:

```bash
mvn test -Dtest=TransferQueueTest#cancelRunningJob_stopsAndQueueContinues
```

### 2. Benchmark

```bash
mvn test -Pbench
```

Profile `bench` chỉ chạy các lớp được gắn tag `benchmark`, mất khoảng 10–15 giây. Muốn chạy riêng một benchmark:

```bash
mvn test -Pbench -Dtest=BrowseLatencyBenchmark
mvn test -Pbench -Dtest=ThroughputBenchmark
```

### 3. Đổi tham số

Mỗi tham số truyền vào bằng `-D...`:

| Tham số | Mặc định | Dùng cho | Ý nghĩa |
|---|---|---|---|
| `bench.sizeMB` | 64 | Benchmark 1 | Kích thước tệp lớn đang tải |
| `bench.rateMBps` | 32 | Benchmark 1 | Băng thông giả lập (MB/s). Tệp 64 MB ở 32 MB/s mất khoảng 2 giây |
| `bench.rttMs` | 10 (B1), 0 (B2) | cả hai | Độ trễ mỗi trả lời của kênh điều khiển, giả lập mạng xa |
| `bench.intervalMs` | 200 | Benchmark 1 | Cứ bao nhiêu ms thì "người dùng nhấp" vào một thư mục một lần |
| `bench.bigMB` | 128 | Benchmark 2 | Tệp lớn dùng để đo MB/s |
| `bench.smallCount` | 200 | Benchmark 2 | Số tệp nhỏ |
| `bench.smallKB` | 4 | Benchmark 2 | Kích thước mỗi tệp nhỏ |

Ví dụ giả lập tải tệp 256 MB qua mạng chậm 8 MB/s, mỗi lệnh trễ 40 ms:

```bash
mvn test -Pbench -Dtest=BrowseLatencyBenchmark -Dbench.sizeMB=256 -Dbench.rateMBps=8 -Dbench.rttMs=40
```

## Cách đọc kết quả

Kết quả được **in ra console** dưới dạng bảng, đồng thời **ghi ra CSV** trong `target/benchmark-results/` (`browse-latency.csv`, `throughput.csv`) để dán vào Excel hoặc vẽ biểu đồ cho báo cáo.

### Benchmark 1: độ trễ duyệt thư mục

```
Cấu hình: tệp 64 MB, băng thông 32 MB/s, RTT 10 ms, nhấp mỗi 200 ms

=== Độ trễ duyệt thư mục trong lúc tải tệp lớn (ms, càng nhỏ càng tốt) ===
kich_ban   so_lan_nhap  min_ms  p50_ms  p95_ms  max_ms   tb_ms  thoi_gian_tai_ms
--------------------------------------------------------------------------------
idle                20    55.8    57.7    68.1   122.4    61.5                 -
1-ket-noi           10   618.9  1191.9  1909.1  1909.1  1263.7            2206.7
2-ket-noi           10    53.5    56.2    59.2    59.2    56.4            2099.0
=> Kiến trúc 2 kết nối nhanh hơn 21 lần ở p50 (56.2 ms so với 1191.9 ms)
```

Một "lần nhấp" giống hệt việc người dùng nhấp đúp vào một thư mục: client gửi `CWD`, `LIST` rồi `PWD`. Độ trễ được đo từ lúc nhấp đến lúc có danh sách tệp.

- **Các hàng:**
  - `idle` là mốc chuẩn khi không truyền gì. Đây là tốc độ duyệt "bình thường".
  - `1-ket-noi` là kiến trúc cũ: một kết nối và một luồng làm tất cả mọi việc. Lần nhấp phải **xếp hàng sau lệnh RETR**, nên phải chờ tới khi tệp tải xong mới thấy được thư mục.
  - `2-ket-noi` là kiến trúc mới: kết nối duyệt riêng, còn tệp lớn chạy trên kết nối thứ hai trong `TransferQueue`.
- **Các cột:**
  - `so_lan_nhap` là số lần nhấp thử trong lúc tệp đang tải.
  - `p50_ms` là trung vị: một nửa số lần nhấp nhanh hơn con số này.
  - `p95_ms` là mức mà 95% số lần nhấp nhanh hơn. Cột này cho biết trường hợp xấu nhất mà người dùng thường gặp.
  - `tb_ms` là thời gian trung bình.
  - `thoi_gian_tai_ms` là thời gian tải xong tệp lớn.
- **Cách hiểu kết quả:**
  - Hàng `2-ket-noi` gần bằng `idle`, nghĩa là việc duyệt **không bị ảnh hưởng** bởi tệp đang tải.
  - Hàng `1-ket-noi` có độ trễ lên tới vài giây, tương đương thời gian còn lại của lần tải. Ở giao diện cũ, đây chính là lúc cửa sổ "đứng".
  - `thoi_gian_tai_ms` của hai kiến trúc xấp xỉ nhau, nghĩa là mở thêm kết nối duyệt **không làm chậm** việc tải.
- Benchmark tự kiểm tra rằng `p95` của `2-ket-noi` nhỏ hơn `p50` của `1-ket-noi`. Nếu điều này sai thì test báo FAIL.
- Nếu gặp lỗi "tệp tải quá nhanh, không kịp nhấp lần nào", hãy tăng `bench.sizeMB` hoặc giảm `bench.rateMBps`.

### Benchmark 2: thông lượng

```
Cấu hình: tệp lớn 128 MB, 200 tệp nhỏ x 4 KB, RTT 0 ms

=== Thông lượng truyền tệp (MB/s, tệp/s càng lớn càng tốt) ===
kich_ban              so_tep  tong_MB  thoi_gian_ms  MB_per_s  tep_per_s  ms_moi_tep
------------------------------------------------------------------------------------
tep-lon-truc-tiep          1      128         183.6     697.0          -           -
tep-lon-qua-hang-doi       1      128         175.0     731.4          -           -
nhieu-tep-nho            200      0.8         440.6       1.8      453.9         2.2
```

- **Tệp lớn:**
  - `tep-lon-truc-tiep` gọi thẳng `FtpSession.download` và là mốc so sánh. Trước khi đo, benchmark chạy 3 lượt khởi động (warm-up) để JVM tối ưu xong code.
  - `tep-lon-qua-hang-doi` tải cùng tệp đó qua `TransferQueue`.
  - Cột `MB_per_s` của hai hàng nên **xấp xỉ nhau**, chênh lệch trong khoảng ±10% là do nhiễu đo. Điều đó có nghĩa là hàng đợi không làm chậm việc truyền.
  - Số MB/s ở đây rất cao vì dữ liệu chỉ đi qua localhost. Trên mạng thật, băng thông mạng mới là giới hạn.
- **Nhiều tệp nhỏ:**
  - `tep_per_s` là số tệp xử lý được mỗi giây, `ms_moi_tep` là chi phí cố định cho mỗi tệp.
  - Với tệp nhỏ, thời gian gần như chỉ tiêu vào các vòng hỏi đáp của kênh điều khiển (PASV, STOR, 150, 226), chứ không phải vào dữ liệu.
  - Chạy lại với `-Dbench.rttMs=20` sẽ thấy `ms_moi_tep` tăng theo RTT. Đây là lý do tải lên hàng nghìn tệp nhỏ qua mạng xa thường chậm.
- Benchmark cũng kiểm tra rằng toàn bộ hàng đợi chỉ mở **một** kết nối truyền và dùng lại nó cho mọi tệp.

### Đọc kết quả test chức năng

```
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in ftpclient.benchmark.TransferQueueTest
```

- `Failures` là số test chạy xong nhưng kiểm tra sai, ví dụ duyệt thư mục mất hơn 500 ms trong lúc đang truyền.
- `Errors` là số test bị lỗi ngoài dự kiến, ví dụ một exception không được bắt.
- Báo cáo chi tiết từng test nằm trong `target/surefire-reports/`.

## Lưu ý khi so sánh số liệu

- Số tuyệt đối phụ thuộc vào máy chạy test. Khi so sánh, hãy so **tỉ lệ giữa các hàng** trong cùng một lần chạy.
- Benchmark chạy trên localhost với băng thông và RTT giả lập, nên đo được đúng thứ cần đo là việc client xếp lệnh như thế nào, mà không bị nhiễu bởi mạng thật.
- Server giả lập bật `TCP_NODELAY`. Nếu không bật, mỗi tệp nhỏ chậm thêm khoảng 40 ms, vì trả lời "226" phải chờ gói ACK trễ của "150" (hiệu ứng Nagle cộng delayed ACK).

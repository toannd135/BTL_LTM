# BTL Lập trình mạng — FTP Server & FTP Client GUI

Bài tập lớn môn Lập trình mạng: tự cài đặt **FTP Server** (Java thuần, dùng `Socket`) và một **FTP Client có giao diện JavaFX**.

## Cấu trúc thư mục

| Thư mục / file | Nội dung |
|---|---|
| [`ftp/`](ftp) | FTP Server (Maven, Java 21). Có test JUnit và script kiểm thử Python |
| [`ftp/LUONG-DU-LIEU.md`](ftp/LUONG-DU-LIEU.md) | Tài liệu giải thích luồng dữ liệu của server, bám theo code thật |
| [`ftp-client-gui/`](ftp-client-gui) | FTP Client giao diện JavaFX 21 (Maven) |
| `clientTCP.java`, `serverTCP.java` | Ví dụ TCP socket cơ bản (bài thực hành ban đầu) |

## Yêu cầu

- JDK 21+
- Maven 3.8+
- Python 3 (chỉ cần nếu chạy script kiểm thử trong `ftp/scripts/`)

## FTP Server (`ftp/`)

### Build & chạy

```bash
cd ftp
mvn clean package
java -jar target/ftpserver.jar            # dùng ftpserver.conf trong thư mục hiện tại
java -jar target/ftpserver.jar path/to/other.conf   # hoặc chỉ định file cấu hình
```

### Cấu hình — `ftpserver.conf`

```properties
control.port=2121            # cổng kênh điều khiển
root.dir=./ftproot           # thư mục gốc được chia sẻ
data.port.min=50000          # dải cổng cho chế độ PASV
data.port.max=50100
idle.timeout.seconds=300     # ngắt client im lặng quá thời gian này

user.<tên>.password=<mật khẩu>
user.<tên>.readonly=true|false
user.anonymous.readonly=true
```

> Tài khoản mặc định trong file là **tài khoản thử nghiệm** — hãy đổi mật khẩu trước khi dùng ngoài môi trường học tập.

### Lệnh FTP được hỗ trợ

`USER` `PASS` `QUIT` `SYST` `NOOP` `TYPE` `PWD` `CWD` `CDUP` `MKD` `RMD` `DELE` `LIST` `NLST` `RETR` `STOR` `PASV` `PORT`

Hỗ trợ cả chế độ **Passive (PASV)** và **Active (PORT)**, nhiều client đồng thời, chống thoát khỏi thư mục gốc (path traversal) qua `PathResolver`.

### Kiểm thử

```bash
cd ftp
mvn test                              # unit test (Config, PathResolver)

# Bật server ở terminal khác, rồi chạy:
python3 scripts/smoke_test.py         # kiểm thử chức năng cơ bản
python3 scripts/concurrency_test.py   # nhiều client đồng thời
python3 scripts/pasv_leak_test.py     # kiểm tra rò rỉ cổng PASV
```

## FTP Client GUI (`ftp-client-gui/`)

```bash
cd ftp-client-gui
mvn javafx:run
```

Nhập host, cổng (mặc định server là `2121`), tài khoản rồi kết nối. Cần bật FTP Server trước.

## Thử nhanh với client có sẵn

```bash
ftp localhost 2121
# hoặc
curl --user toannd:123456 ftp://localhost:2121/
```

## Kiến trúc server (tóm tắt)

```
ftpserver/
├── Main.java              điểm vào, nạp cấu hình
├── config/                đọc ftpserver.conf
├── auth/                  User, UserManager (xác thực)
├── core/                  FtpServer, ClientSession, CommandRegistry, FtpReply
├── command/               mỗi lệnh FTP một CommandHandler
├── data/                  DataConnection (PASV/PORT), DataMode, TransferType
└── fs/                    PathResolver (ánh xạ & kiểm tra đường dẫn an toàn)
```

Chi tiết từng luồng (khởi động, đăng nhập, LIST/RETR/STOR...) xem [`ftp/LUONG-DU-LIEU.md`](ftp/LUONG-DU-LIEU.md).

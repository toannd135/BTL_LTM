FROM python:3.12-slim

# Tuy chon: --build-arg WITH_CLAMAV=1 cai client clamdscan (nho, khong kem CSDL chu ky);
# can them clamd chay rieng va /etc/clamav/clamd.conf tro toi no (TCPAddr/TCPSocket)
ARG WITH_CLAMAV=0
RUN if [ "$WITH_CLAMAV" = "1" ]; then \
        apt-get update && apt-get install -y --no-install-recommends clamdscan && rm -rf /var/lib/apt/lists/*; \
    fi

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py storage.py ./
COPY templates templates
COPY static static

# Dữ liệu dùng chung với ftpserver: mount volume vào các đường dẫn dưới đây.
# PostgreSQL: đặt FTP_DB_URL=jdbc:postgresql://host:5432/db (hoặc postgresql://...), FTP_DB_USER, FTP_DB_PASSWORD;
# không đặt FTP_DB_URL thì dùng SQLite tại FTP_DB.
ENV FTP_DB=/data/ftpserver.db \
    FTP_ROOT=/ftproot \
    FTP_AUDIT_LOG=/logs/audit.log \
    FTP_QUARANTINE=/quarantine

RUN useradd -r -u 10001 admin && mkdir -p /data /ftproot /logs /quarantine && chown -R admin /data /ftproot /logs /quarantine
USER admin

EXPOSE 8080
# Trong container phải bind 0.0.0.0; khi chạy hãy publish chỉ vào loopback: -p 127.0.0.1:8080:8080
# và luôn đặt FTPADMIN_SECRET (không đặt thì phiên mất khi restart)
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080"]

FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .
COPY templates templates

# Dữ liệu dùng chung với ftpserver: mount volume vào các đường dẫn dưới đây
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

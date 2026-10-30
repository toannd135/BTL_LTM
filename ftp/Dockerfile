FROM eclipse-temurin:21-jre
# Tuy chon: --build-arg WITH_CLAMAV=1 de cai ClamAV va tai CSDL chu ky luc build (image lon them ~300 MB).
# Bat quet: -e FTP_SCAN_COMMAND="clamscan --no-summary" [-e FTP_SCAN_ENFORCE=true]
ARG WITH_CLAMAV=0
RUN if [ "$WITH_CLAMAV" = "1" ]; then \
        apt-get update && apt-get install -y --no-install-recommends clamav && rm -rf /var/lib/apt/lists/* \
        && freshclam --stdout; \
    fi
WORKDIR /app
COPY target/ftpserver.jar ftpserver.jar
COPY ftpserver.conf ftpserver.conf
COPY ftproot ftproot
# control, binary, passive data ports
EXPOSE 2121 50000-50100
# Keystore FTPS va nhat ky khong nam trong image. Chay voi:
#   -v $PWD/certs:/app/certs:ro -v ftp-logs:/app/logs -v ftp-data:/app/data
#   -e FTP_TLS_KEYSTORE=/app/certs/ftps.p12 -e FTP_TLS_KEYSTORE_PASSWORD=...
VOLUME ["/app/logs", "/app/data"]
ENTRYPOINT ["java", "-jar", "ftpserver.jar", "ftpserver.conf"]

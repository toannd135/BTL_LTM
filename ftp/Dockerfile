FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/ftpserver.jar ftpserver.jar
COPY ftpserver.conf ftpserver.conf
COPY ftproot ftproot
# control, binary, passive data ports
EXPOSE 2121 2122 50000-50100
ENTRYPOINT ["java", "-jar", "ftpserver.jar", "ftpserver.conf"]

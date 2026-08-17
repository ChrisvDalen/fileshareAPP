FROM ubuntu:latest
WORKDIR /app
LABEL authors="cvandalen"

COPY target/fileshare-app-1.0-SNAPSHOT.jar app.jar

ENTRYPOINT ["top", "-b"]

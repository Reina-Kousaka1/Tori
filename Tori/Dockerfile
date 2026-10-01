FROM node:24-bookworm-slim AS audio-node
FROM gradle:9.8.0-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle --no-daemon test installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=audio-node /usr/local/bin/node /usr/local/bin/node
ARG TARGETARCH
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates ffmpeg \
    && case "$TARGETARCH" in \
         amd64) YTDLP_ASSET=yt-dlp_linux; YTDLP_SHA256=58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a ;; \
         arm64) YTDLP_ASSET=yt-dlp_linux_aarch64; YTDLP_SHA256=b16e4dab368a816cd05d477d698a605a6ae87ccee1c8ffd38fa21d7254141fcc ;; \
         *) echo "Unsupported Docker target architecture: $TARGETARCH" >&2; exit 1 ;; \
       esac \
    && curl -fL "https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/$YTDLP_ASSET" -o /usr/local/bin/yt-dlp \
    && echo "$YTDLP_SHA256  /usr/local/bin/yt-dlp" | sha256sum -c - \
    && chmod +x /usr/local/bin/yt-dlp \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/install/discord-music-bot/ ./
ENTRYPOINT ["/app/bin/discord-music-bot"]

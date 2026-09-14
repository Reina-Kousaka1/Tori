FROM node:24-bookworm-slim AS audio-node
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle --no-daemon test installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=audio-node /usr/local/bin/node /usr/local/bin/node
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates ffmpeg \
    && curl -fL https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/yt-dlp_linux -o /usr/local/bin/yt-dlp \
    && echo '58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a  /usr/local/bin/yt-dlp' | sha256sum -c - \
    && chmod +x /usr/local/bin/yt-dlp \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/install/discord-music-bot/ ./
ENTRYPOINT ["/app/bin/discord-music-bot"]
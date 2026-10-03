FROM node:20-bullseye-slim

# Install ffmpeg and ffprobe for video transcoding
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY package*.json ./
COPY . .

ENV PORT=8080
EXPOSE 8080

CMD ["node", "browser/server.js"]

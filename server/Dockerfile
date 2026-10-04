# StableShare mock server image (used by render.yaml). Storage lives at /data.
FROM node:24-bookworm-slim

ENV NODE_ENV=production
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY src ./src
COPY scripts ./scripts
COPY entrypoint.sh ./

ENV PORT=8080 STORAGE_DIR=/data
EXPOSE 8080
# The entrypoint starts as root only to hand the volume to the `node` user, then drops to it.
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["node", "src/server.js"]

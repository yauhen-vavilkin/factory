FROM node:22.22.0-bookworm-slim AS node
FROM maven:3.9-eclipse-temurin-21
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && npm install --global @earendil-works/pi-coding-agent@0.85.1 \
    && npm cache clean --force \
    && pi --version
WORKDIR /workspace
ENV PI_OFFLINE=1 PI_TELEMETRY=0

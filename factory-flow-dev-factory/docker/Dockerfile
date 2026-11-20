FROM node:22.22.0-bookworm-slim AS node
FROM maven:3.9-eclipse-temurin-21
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
COPY --from=node /opt/yarn-v1.22.22 /opt/yarn-v1.22.22
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s /opt/yarn-v1.22.22/bin/yarn /usr/local/bin/yarn \
    && ln -s /opt/yarn-v1.22.22/bin/yarnpkg /usr/local/bin/yarnpkg \
    && npm install --global @earendil-works/pi-coding-agent@0.85.1 \
    && npm cache clean --force \
    && pi --version \
    && test "$(yarn --version)" = 1.22.22
WORKDIR /workspace
ENV PI_OFFLINE=1 PI_TELEMETRY=0

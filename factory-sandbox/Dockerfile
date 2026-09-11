FROM maven:3.9-eclipse-temurin-21
ARG TARGETARCH
# git is already included in the base image (git 2.43.0), so no apt-get install needed.
USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl xz-utils ripgrep \
 && node_arch=$(printf '%s' "$TARGETARCH" | sed 's/amd64/x64/') \
 && curl -fsSL "https://nodejs.org/dist/v22.19.0/node-v22.19.0-linux-${node_arch}.tar.xz" \
      | tar -xJ --strip-components=1 -C /usr/local \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/pi
COPY pi-package.json package.json
COPY pi-models.json /opt/pi/models.json
RUN npm install --package-lock-only --ignore-scripts --omit=dev \
 && npm ci --ignore-scripts --omit=dev \
 && test "$(node -p "require('./node_modules/@earendil-works/pi-coding-agent/package.json').version")" = "0.85.1"
RUN useradd --create-home --uid 10001 agent \
 && mkdir -p /state /workspace \
 && mkdir -p /state/pi/sessions \
 && chmod 444 /opt/pi/models.json \
 && cp /opt/pi/models.json /state/pi/models.json \
 && chmod 444 /state/pi/models.json \
 && chown -R agent:agent /state /workspace
USER agent
ENV HOME=/home/agent PI_CODING_AGENT_DIR=/state/pi PI_OFFLINE=1 PI_TELEMETRY=0

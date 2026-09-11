FROM python:3.12-alpine

COPY server.py /opt/factory-gateway/server.py
RUN adduser -D -u 10001 gateway \
 && mkdir -p /evidence \
 && chown gateway:gateway /evidence /opt/factory-gateway/server.py

USER gateway
EXPOSE 8080
ENTRYPOINT ["python", "/opt/factory-gateway/server.py"]

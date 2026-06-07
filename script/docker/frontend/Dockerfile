FROM python:3.12-slim

WORKDIR /app

ENV STATIC_ROOT=/usr/share/nginx/html \
    UPSTREAM_BASE=http://127.0.0.1:8080 \
    SERVER_PORT=80

COPY server.py /app/server.py

EXPOSE 80

CMD ["python", "/app/server.py"]

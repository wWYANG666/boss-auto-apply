FROM python:3.12-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PORT=8001

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends fonts-wqy-zenhei && rm -rf /var/lib/apt/lists/*

RUN groupadd --system care && useradd --system --gid care --home-dir /app care

COPY requirements.txt ./
RUN pip install --requirement requirements.txt

COPY app ./app

USER care
EXPOSE 8001

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8001/internal/v1/health', timeout=2)"

CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT} --workers 1"]

FROM python:3.12-slim

WORKDIR /app

COPY pyproject.toml ./
COPY schematic_service ./schematic_service

RUN pip install --no-cache-dir .

EXPOSE 7080

CMD ["uvicorn", "schematic_service.app:app", "--host", "0.0.0.0", "--port", "7080"]

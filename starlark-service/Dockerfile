# python:3.13-slim (not the 3.12 used elsewhere): starlark-pyo3 ships cp313 wheels
FROM python:3.13-slim

WORKDIR /app

# Submodule checkout: lib/, docs/, and examples/ ride along with the package
COPY starlark-to-nbt ./starlark-to-nbt
COPY pyproject.toml ./
COPY starlark_service ./starlark_service

RUN pip install --no-cache-dir ./starlark-to-nbt && pip install --no-cache-dir .

ENV STARLARK_TOOL_DIR=/app/starlark-to-nbt \
    STARLARK_CACHE_DIR=/data

EXPOSE 7090

CMD ["uvicorn", "starlark_service.app:app", "--host", "0.0.0.0", "--port", "7090"]

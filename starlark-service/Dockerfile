# python:3.13-slim (not the 3.12 used elsewhere): starlark-pyo3 ships cp313 wheels

# starlark-to-nbt includes a Rust extension (starlark_to_nbt._core, built by
# maturin), so its wheel is compiled in a throwaway stage with a Rust toolchain.
# The runtime image only installs the finished wheel.
FROM python:3.13-slim AS core-wheel

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential ca-certificates curl zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*
# Matches starlark-to-nbt's rust-toolchain.toml.
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \
    | sh -s -- -y --profile minimal --default-toolchain 1.99.1
ENV PATH=/root/.cargo/bin:$PATH

COPY starlark-to-nbt /src/starlark-to-nbt
RUN pip wheel --no-cache-dir --no-deps /src/starlark-to-nbt -w /wheels

FROM python:3.13-slim

WORKDIR /app

# Submodule checkout: lib/, docs/, and examples/ ride along with the package
COPY starlark-to-nbt ./starlark-to-nbt
# Submodule checkout: artifact preview renderer
COPY nbt-image-gen ./nbt-image-gen
COPY pyproject.toml ./
COPY starlark_service ./starlark_service
COPY --from=core-wheel /wheels /wheels

RUN pip install --no-cache-dir /wheels/*.whl ./nbt-image-gen && pip install --no-cache-dir . && rm -rf /wheels

ENV STARLARK_TOOL_DIR=/app/starlark-to-nbt \
    STARLARK_CACHE_DIR=/data \
    STARLARK_LIBRARY_DIR=/library

EXPOSE 7090

CMD ["uvicorn", "starlark_service.app:app", "--host", "0.0.0.0", "--port", "7090"]

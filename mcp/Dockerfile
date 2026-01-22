FROM python:3.12-slim

WORKDIR /app

# Install uv
RUN pip install uv

# Copy MCP server source (excluding .venv via .dockerignore)
COPY pyproject.toml uv.lock ./
COPY minecraft_mcp/ ./minecraft_mcp/

# Install dependencies using uv
RUN uv sync

# Expose SSE port
EXPOSE 3737

# Run in SSE mode
CMD ["uv", "run", "minecraft-mcp", "--transport", "sse", "--host", "0.0.0.0", "--port", "3737"]

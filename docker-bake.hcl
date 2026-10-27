group "default" {
  targets = ["nginx", "minecraft", "mcp"]
}

# Opt-in services (compose profiles "schematics" and "starlark")
group "optional" {
  targets = ["schematic-service", "starlark-service"]
}

# Optional knobs you can override from the CLI
variable "REGISTRY" { default = "ghcr.io/wmill" }
variable "TAG"      { default = "latest" }
variable "PLATFORMS" { default = "linux/amd64" }

target "nginx" {
  context    = "."
  dockerfile = "nginx.Dockerfile"
  platforms  = split(",", PLATFORMS)
  tags       = ["${REGISTRY}/minecraft-api-nginx:${TAG}"]
}

target "minecraft" {
  context    = "."
  dockerfile = "minecraft.Dockerfile"
  platforms  = split(",", PLATFORMS)
  tags       = ["${REGISTRY}/minecraft-api:${TAG}"]
}

target "mcp" {
  context    = "./mcp"
  dockerfile = "Dockerfile"
  platforms  = split(",", PLATFORMS)
  tags       = ["${REGISTRY}/minecraft-mcp:${TAG}"]
}

target "schematic-service" {
  context    = "./schematic-service"
  dockerfile = "Dockerfile"
  platforms  = split(",", PLATFORMS)
  tags       = ["${REGISTRY}/minecraft-schematic-service:${TAG}"]
}

target "starlark-service" {
  context    = "./starlark-service"
  dockerfile = "Dockerfile"
  platforms  = split(",", PLATFORMS)
  tags       = ["${REGISTRY}/minecraft-starlark-service:${TAG}"]
}

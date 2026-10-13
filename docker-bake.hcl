group "default" {
  targets = ["nginx", "minecraft", "mcp"]
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

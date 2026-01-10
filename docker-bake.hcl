group "default" {
  targets = ["nginx", "minecraft"]
}

# Optional knobs you can override from the CLI
variable "REGISTRY" { default = "ghcr.io/wmill" }
variable "TAG"      { default = "latest" }

target "nginx" {
  context    = "."
  dockerfile = "nginx.Dockerfile"
  platforms  = ["linux/amd64", "linux/arm64"]
  tags       = ["${REGISTRY}/minecraft-api-nginx:${TAG}"]
}

target "minecraft" {
  context    = "."
  dockerfile = "minecraft.Dockerfile"
  platforms  = ["linux/amd64", "linux/arm64"]
  tags       = ["${REGISTRY}/minecraft-api:${TAG}"]
}

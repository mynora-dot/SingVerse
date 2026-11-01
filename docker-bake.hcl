variable "REGISTRY" { default = "singverse-compute" }
variable "VERSION" { default = "0.1.0-SNAPSHOT" }
variable "REVISION" { default = "local" }

group "default" { targets = ["cpu", "cuda", "rocm", "openvino"] }

target "common" {
  context = "."
  dockerfile = "services/singverse-compute/Dockerfile"
  args = { SINGVERSE_VERSION = VERSION, SOURCE_REVISION = REVISION }
}

target "base" {
  inherits = ["common"]
  target = "base"
  platforms = ["linux/amd64", "linux/arm64"]
  tags = ["${REGISTRY}:${VERSION}-base"]
}
target "cpu" {
  inherits = ["common"]
  target = "cpu"
  platforms = ["linux/amd64", "linux/arm64"]
  tags = ["${REGISTRY}:${VERSION}-cpu"]
}
target "cuda" {
  inherits = ["common"]
  target = "cuda"
  platforms = ["linux/amd64"]
  tags = ["${REGISTRY}:${VERSION}-cuda"]
}
target "rocm" {
  inherits = ["common"]
  target = "rocm"
  platforms = ["linux/amd64"]
  tags = ["${REGISTRY}:${VERSION}-rocm"]
}
target "openvino" {
  inherits = ["common"]
  target = "openvino"
  platforms = ["linux/amd64"]
  tags = ["${REGISTRY}:${VERSION}-openvino"]
}

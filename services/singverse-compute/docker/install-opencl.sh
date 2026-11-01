#!/bin/sh
# Intel official compute-runtime ww35.sum, 24.35.30872.22. Container userspace only.
set -eu
apt-get update
apt-get install -y --no-install-recommends ocl-icd-libopencl1
mkdir /tmp/singverse-opencl
cd /tmp/singverse-opencl
fetch() {
  curl --fail --location --retry 3 "$1/$2" -o "$2"
  echo "$3  $2" | sha256sum --check -
}
igc=https://github.com/intel/intel-graphics-compiler/releases/download/igc-1.0.17537.20
neo=https://github.com/intel/compute-runtime/releases/download/24.35.30872.22
fetch "$igc" intel-igc-core_1.0.17537.20_amd64.deb 7f2af5b0e567a43625a748effb744d0b3c96acf805467d099e46eee617e11b2a
fetch "$igc" intel-igc-opencl_1.0.17537.20_amd64.deb ac2088331d55c7de15bd57373f73630e95b40e1275934bcfd96bf0c3e03769a7
fetch "$neo" libigdgmm12_22.5.0_amd64.deb cc29d14df83cff1b3c6a66baa39257f0211b168ab43a99c2dc62a3734431bc23
fetch "$neo" intel-opencl-icd_24.35.30872.22_amd64.deb 92985888765be55e8ee54827c3e04ae8df93e7410733073ef7b4e42c3d40e24d
apt-get install -y --no-install-recommends ./*.deb
cd /
rm -rf /tmp/singverse-opencl /var/lib/apt/lists/*

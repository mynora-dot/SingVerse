FROM python:3.12-slim-bookworm@sha256:e697c81897302a4c4333bade000d98038fbb21eb064f8ee1fd42d02ebcd9d337 AS base
ARG TARGETARCH
# Official Jellyfin release assets, pinned by version AND SHA-256.
RUN --mount=type=cache,id=singverse-compute-apt,target=/var/cache/apt,sharing=locked \
    rm -f /etc/apt/apt.conf.d/docker-clean && sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources && \
    apt-get -o Acquire::Retries=3 update && apt-get install -y --no-install-recommends ca-certificates curl && \
    case "$TARGETARCH" in \
      amd64) digest=4829b34df16843ecf1aca8b1a600874b5c767396e1d1990c889c761fd143d928 ;; \
      arm64) digest=3497002f1b7a7664875dd0aa71d1916a74cbee6598d8b3b413b83732cd1d8794 ;; \
      *) exit 1 ;; \
    esac && \
    curl --fail --location --retry 3 "https://github.com/jellyfin/jellyfin-ffmpeg/releases/download/v8.1.3-1/jellyfin-ffmpeg8_8.1.3-1-bookworm_${TARGETARCH}.deb" -o /tmp/jellyfin-ffmpeg.deb && \
    echo "$digest  /tmp/jellyfin-ffmpeg.deb" | sha256sum --check - && \
    apt-get install -y --no-install-recommends /tmp/jellyfin-ffmpeg.deb fonts-noto-cjk libgomp1 && \
    rm -f /tmp/jellyfin-ffmpeg.deb && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/singverse-compute
COPY services/singverse-compute/requirements*.lock ./
RUN --mount=type=cache,target=/root/.cache/pip python -m pip install -r requirements.lock
# Driver-independent ONNX CPU worker is present even in accelerator images.
RUN --mount=type=cache,target=/root/.cache/pip python -m venv /opt/singverse-audio-cpu && \
    /opt/singverse-audio-cpu/bin/python -m pip install -r requirements-separation-cpu.lock && \
    /opt/singverse-audio-cpu/bin/python -m pip check
ENV COMPUTE_AUDIO_CPU_PYTHON=/opt/singverse-audio-cpu/bin/python
COPY services/singverse-compute/singverse_compute/ ./singverse_compute/
RUN chmod 755 /opt/singverse-compute && \
    find /opt/singverse-compute/singverse_compute -type d -exec chmod 755 {} + && \
    find /opt/singverse-compute/singverse_compute -type f -exec chmod 644 {} +
RUN mkdir -p /run/singverse-compute /var/cache/singverse-transcode /var/lib/singverse-models && chown 10001:10001 /run/singverse-compute /var/cache/singverse-transcode /var/lib/singverse-models
ENV PATH="/usr/lib/jellyfin-ffmpeg:${PATH}"
ENV COMPUTE_AUTH_TOKEN_FILE=/run/singverse-compute/token \
    COMPUTE_TRANSCODE_ROOT=/var/cache/singverse-transcode \
    COMPUTE_FFMPEG=/usr/lib/jellyfin-ffmpeg/ffmpeg COMPUTE_FFPROBE=/usr/lib/jellyfin-ffmpeg/ffprobe
ARG SINGVERSE_VERSION=0.1.0-SNAPSHOT
ARG SOURCE_REVISION=local
RUN python -c "import json,os,hashlib,pathlib; p=pathlib.Path('singverse_compute/core/build.json'); p.write_text(json.dumps(dict(version=os.environ['SINGVERSE_VERSION'],revision=os.environ['SOURCE_REVISION'],variant='base',runtimeLock=hashlib.sha256(b''.join(f.read_bytes() for f in sorted(pathlib.Path('.').glob('requirements*.lock')))).hexdigest(),sourceDigest=hashlib.sha256(b''.join(str(f).encode()+f.read_bytes() for f in sorted(pathlib.Path('singverse_compute').rglob('*.py')))).hexdigest())))"
# Generated metadata comes after COPY normalization; normalize it and every lock
# explicitly even when the builder/extractor uses umask 077.
RUN chmod 644 /opt/singverse-compute/requirements*.lock /opt/singverse-compute/singverse_compute/core/build.json
ENV SINGVERSE_COMPUTE_VARIANT=base
LABEL org.opencontainers.image.title="SingVerse Compute" \
      org.opencontainers.image.version="${SINGVERSE_VERSION}" \
      org.opencontainers.image.revision="${SOURCE_REVISION}"
USER 10001:10001
RUN python -c "from pathlib import Path; from singverse_compute.core.build_info import build_info; assert build_info()['variant'] == 'base'; [p.read_bytes() for p in Path('.').glob('requirements*.lock')]"
HEALTHCHECK --interval=5s --timeout=3s --start-period=20s --retries=6 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/health',timeout=2)"
CMD ["python", "-m", "singverse_compute", "--host", "0.0.0.0", "--port", "8090"]

# Advanced Demucs has its own driver-independent CPU worker in every legacy variant.
FROM base AS legacy-base
USER root
RUN --mount=type=cache,target=/root/.cache/pip /opt/singverse-audio-cpu/bin/python -m pip install -r requirements-audio.lock && \
    /opt/singverse-audio-cpu/bin/python -m pip check
USER 10001:10001

# Runtime variants contain no model weights. Download/import is an administrator operation.
FROM legacy-base AS cpu
USER root
RUN --mount=type=cache,target=/root/.cache/pip python -m pip install -r requirements-audio.lock
ENV SINGVERSE_COMPUTE_VARIANT=cpu
USER 10001:10001

FROM legacy-base AS cuda
USER root
ARG TARGETARCH
RUN test "$TARGETARCH" = amd64
RUN --mount=type=cache,target=/root/.cache/pip python -m pip install -r requirements-audio-cuda.lock
RUN --mount=type=cache,target=/root/.cache/pip python -m venv /opt/singverse-audio-accelerator && \
    /opt/singverse-audio-accelerator/bin/python -m pip install -r requirements-separation-cuda.lock
ENV COMPUTE_AUDIO_ACCELERATOR_PYTHON=/opt/singverse-audio-accelerator/bin/python
ENV SINGVERSE_COMPUTE_VARIANT=cuda NVIDIA_DRIVER_CAPABILITIES=compute,video,utility
USER 10001:10001

FROM legacy-base AS rocm
USER root
ARG TARGETARCH
RUN test "$TARGETARCH" = amd64
RUN --mount=type=cache,target=/root/.cache/pip python -m pip install -r requirements-audio-rocm.lock
ENV SINGVERSE_COMPUTE_VARIANT=rocm
USER 10001:10001

FROM legacy-base AS openvino
USER root
ARG TARGETARCH
RUN test "$TARGETARCH" = amd64
COPY services/singverse-compute/docker/install-opencl.sh /tmp/install-opencl.sh
RUN sh /tmp/install-opencl.sh && rm /tmp/install-opencl.sh
RUN --mount=type=cache,target=/root/.cache/pip python -m pip install -r requirements-audio-openvino.lock
ENV SINGVERSE_COMPUTE_VARIANT=openvino
USER 10001:10001

# gfx1103 requires native device libraries absent from the legacy ROCm 6.2 wheels.
# Keep this explicit profile separate from CPU/CUDA/legacy ROCm dependencies.
FROM legacy-base AS rocm-native
USER root
ARG TARGETARCH
RUN test "$TARGETARCH" = amd64
RUN --mount=type=cache,target=/root/.cache/pip python -m pip install --no-build-isolation -r requirements-audio-rocm-native.lock && python -m pip check
ENV SINGVERSE_COMPUTE_VARIANT=rocm-native HOME=/tmp USER=singverse LOGNAME=singverse
USER 10001:10001

# UVR baseline keeps the same pinned CPU DSP dependencies.
FROM cpu AS uvr
USER root
RUN --mount=type=cache,target=/root/.cache/pip python -m pip install -r requirements-audio-uvr.lock
USER 10001:10001

FROM uvr AS rknn
USER root
ARG TARGETARCH
RUN test "$TARGETARCH" = arm64
RUN --mount=type=cache,target=/root/.cache/pip python -m pip install -r requirements-audio-rknn.lock
ENV SINGVERSE_COMPUTE_VARIANT=rknn
USER 10001:10001

FROM cpu AS production

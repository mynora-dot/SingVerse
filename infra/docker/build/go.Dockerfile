ARG GO_IMAGE=golang:1.27.3-bookworm@sha256:dc0963f32053e4bc451d24d87e3892d42d01021b61f093e98eb95c0352bb84b0
ARG NODE_IMAGE=node:22-bookworm-slim@sha256:de170b39439eb0e5a3444ad7d60b015614a381e10fecae2e30ec9e735122f38d
ARG NGINX_IMAGE=nginx:stable-alpine@sha256:47928bf67eb62a1863cb08d9ecb52d3e704304ba614eaee17884204677702951

FROM --platform=$BUILDPLATFORM ${NODE_IMAGE} AS frontend-build
WORKDIR /workspace/services/singverse-frontend
COPY services/singverse-frontend/package.json services/singverse-frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY services/singverse-frontend/ ./
RUN npm run check

FROM ${NGINX_IMAGE} AS singverse-frontend
COPY infra/docker/build/nginx/nginx.conf /etc/nginx/nginx.conf
COPY infra/docker/build/nginx/proxy-scheme.conf /etc/nginx/proxy-scheme.conf
COPY --from=frontend-build /workspace/services/singverse-frontend/dist/ /usr/share/nginx/html/
EXPOSE 80
HEALTHCHECK --interval=5s --timeout=3s --start-period=15s --retries=6 \
  CMD wget --quiet --spider http://127.0.0.1/api/health || exit 1

FROM --platform=$BUILDPLATFORM ${GO_IMAGE} AS backend-build
ARG GOPROXY=https://proxy.golang.org,direct
ENV GOPROXY=${GOPROXY}
ARG TARGETOS
ARG TARGETARCH
ARG TARGETPLATFORM
WORKDIR /workspace
COPY services/singverse-go-backend/go.mod services/singverse-go-backend/go.sum ./services/singverse-go-backend/
WORKDIR /workspace/services/singverse-go-backend
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY services/singverse-go-backend/ ./
COPY VERSION /workspace/VERSION
COPY --from=frontend-build /workspace/services/singverse-frontend/dist/ ./internal/web/dist/
RUN test -s internal/web/dist/admin/index.html && test -s internal/web/dist/party/index.html
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
  CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath \
    -ldflags "-s -w -X main.version=$(cat /workspace/VERSION)" -o /out/singverse ./cmd/singverse
# dbmate's checksum-pinned binary must match the target platform, not BuildKit's host.
COPY scripts/setup-dbmate.sh /workspace/scripts/setup-dbmate.sh
RUN case "$TARGETARCH" in \
    amd64) asset=linux-amd64; digest=f855e99a0f0a81322014bf686b1cb820d2b4d8074e4eadafd64b851b80ecb15a ;; \
    arm64) asset=linux-arm64; digest=42de2bf27989192b9ca8db4cfaa0a9d6b4aee154a03abef30239b7186b1baf82 ;; \
    *) exit 1 ;; esac; \
  curl --fail --location --silent --show-error "https://github.com/amacneil/dbmate/releases/download/v2.37.0/dbmate-$asset" -o /out/dbmate; \
  echo "$digest  /out/dbmate" | sha256sum -c -; chmod 755 /out/dbmate

# Reuse the pinned Alpine base with CA roots and BusyBox health/recovery tools.
# This target has no Node, Go compiler, JVM or Maven runtime.
FROM ${NGINX_IMAGE} AS singverse-go-backend
WORKDIR /opt/singverse
RUN rm -rf /etc/nginx /usr/share/nginx /docker-entrypoint.d /usr/sbin/nginx \
  && mkdir -p /data /secrets /downloads \
  && chown 10001:10001 /data /secrets /downloads
COPY --from=backend-build /out/singverse /usr/local/bin/singverse
COPY --from=backend-build /out/dbmate /usr/local/bin/dbmate
COPY database/migrations/ ./database/migrations/
ENV SINGVERSE_DB_DRIVER=sqlite SQLITE_PATH=/data/singverse.db SERVER_PORT=8080 \
    SINGVERSE_BIND_ADDRESS=0.0.0.0 DBMATE_BIN=/usr/local/bin/dbmate \
    COMPUTE_NODE_URL="" METADATA_CACHE_PATH=/data/metadata/covers \
    WEBDAV_METADATA_PATH=/data/metadata/webdav SINGVERSE_AUTH_KEY_FILE=/secrets/master.key
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=6 \
  CMD wget --quiet --spider http://127.0.0.1:8080/api/health || exit 1
ENTRYPOINT ["/usr/local/bin/singverse"]

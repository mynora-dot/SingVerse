ARG NODE_IMAGE=node:22-bookworm-slim@sha256:de170b39439eb0e5a3444ad7d60b015614a381e10fecae2e30ec9e735122f38d
ARG JDK_IMAGE=eclipse-temurin:21-jdk-jammy@sha256:4e61d0b3471137e239397789661f6adeaf8db9adcfae45916b22f628c8a32dc0
ARG JRE_IMAGE=eclipse-temurin:21-jre-jammy@sha256:9603446cf51296842eab342514857cf7ab55a8891cd564e6c68ec237fae65a16
ARG NGINX_IMAGE=nginx:stable-alpine@sha256:47928bf67eb62a1863cb08d9ecb52d3e704304ba614eaee17884204677702951

FROM ${NODE_IMAGE} AS frontend-build
WORKDIR /workspace/services/singverse-frontend
COPY services/singverse-frontend/package.json services/singverse-frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY services/singverse-frontend/ ./
RUN npm run check

FROM ${NGINX_IMAGE} AS singverse-frontend
COPY services/singverse-java-backend/nginx/nginx.conf /etc/nginx/nginx.conf
COPY services/singverse-java-backend/nginx/proxy-scheme.conf /etc/nginx/proxy-scheme.conf
COPY --from=frontend-build /workspace/services/singverse-frontend/dist/ /usr/share/nginx/html/
EXPOSE 80
HEALTHCHECK --interval=5s --timeout=3s --start-period=15s --retries=6 \
  CMD wget --quiet --spider http://127.0.0.1/api/health || exit 1

FROM ${JDK_IMAGE} AS migration-tool
WORKDIR /workspace
COPY scripts/setup-dbmate.sh ./scripts/setup-dbmate.sh
RUN sh scripts/setup-dbmate.sh

FROM ${JDK_IMAGE} AS backend-build
ENV DBMATE_BIN=/usr/local/bin/dbmate
COPY --from=migration-tool /workspace/.tools/dbmate /usr/local/bin/dbmate
COPY database/migrations/ /workspace/database/migrations/
COPY contracts/openapi/auth-test-vectors.json contracts/openapi/singverse.openapi.json /workspace/contracts/openapi/
WORKDIR /workspace/services/singverse-java-backend
COPY services/singverse-java-backend/.mvn/ ./.mvn/
COPY services/singverse-java-backend/mvnw services/singverse-java-backend/pom.xml ./
COPY --from=frontend-build /workspace/services/singverse-frontend/dist/ ../singverse-frontend/dist/
COPY services/singverse-java-backend/src/ ./src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B verify

FROM ${JRE_IMAGE} AS singverse-java-backend
WORKDIR /opt/singverse
ENV DBMATE_BIN=/usr/local/bin/dbmate
COPY --from=migration-tool /workspace/.tools/dbmate /usr/local/bin/dbmate
COPY database/migrations/ ./database/migrations/
RUN mkdir -p /data /secrets && chown 10001:10001 /data /secrets
COPY --from=backend-build --chown=10001:10001 /workspace/services/singverse-java-backend/target/singverse-java-backend-0.1.0-SNAPSHOT.jar ./app.jar
ENV SPRING_PROFILES_ACTIVE=sqlite \
    SQLITE_PATH=/data/singverse.db \
    SERVER_PORT=8080 \
    COMPUTE_NODE_URL="" \
    METADATA_CACHE_PATH=/data/metadata/covers \
    WEBDAV_METADATA_PATH=/data/metadata/webdav \
    SINGVERSE_AUTH_KEY_FILE=/secrets/master.key
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=6 \
  CMD curl --fail --silent --show-error http://127.0.0.1:8080/api/health || exit 1
ENTRYPOINT ["java", "-jar", "/opt/singverse/app.jar"]

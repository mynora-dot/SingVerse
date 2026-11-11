SHELL := /bin/sh
.DEFAULT_GOAL := help
PYTHON ?= python3
GO_BACKEND_IMAGE ?= singverse-go-backend:local
GO_FRONTEND_IMAGE ?= singverse-frontend:local
.PHONY: go-tools-setup go-backend-assets go-backend-build go-backend-test go-backend-run go-mysql-check

go-mysql-check: go-backend-assets dbmate-setup
	$(PYTHON) infra/docker/check-go-mysql.py

go-tools-setup:
	sh scripts/setup-go-tools.sh

go-backend-assets: frontend-build
	$(PYTHON) scripts/prepare-go-assets.py

go-backend-build: go-backend-assets
	sh scripts/build-go-backend.sh

go-backend-test: go-backend-assets dbmate-setup
	sh scripts/check-go-backend.sh

go-backend-run: go-backend-build dbmate-setup
	cd services/singverse-go-backend && bin/singverse
.PHONY: compute-audio-setup transcode-fixtures transcode-tools help mobile-check mobile-build mobile-audio-test frontend-setup frontend-dev frontend-check frontend-build web-check tv-build tv-release backend-check compute-setup compute-check compute-run db-sqlite-check db-mysql-up db-mysql-check docker-build docker-up docker-down docker-status docker-logs docker-check docker-config-check nginx-media-check verify

help:
	@printf '%s\n' 'go-tools-setup    Install version-pinned Go analysis and vulnerability tools in .tools' 'go-backend-test   Go formatting, vet, static analysis, vulnerability scan and race tests' 'go-backend-build  Build CGO-free Go service with complete embedded frontend' 'go-backend-run    Start Go backend using explicit process environment' 'go-recovery-check Verify real terminal-only recovery with disposable native data' 'go-container-check Verify built Go/Nginx images in isolated containers (GO_BACKEND_IMAGE/GO_FRONTEND_IMAGE)'
	@printf '%s\n' 'app-package       Package frontend, Go and persistent SQLite deployment without .env'
	@printf '%s\n' 'app-package-offline Export prebuilt Linux AMD64 business images with no-pull Compose'
	@printf '%s\n' 'dbmate-setup      Install checksum-pinned language-neutral migration tool'
	@printf '%s\n' 'db-fresh-check    Verify fresh SQLite baseline, repeat migration and rollback/recreate in disposable data' 'metadata-standard-check Validate public metadata schema, examples, revisions and asset rules'
	@printf '%s\n' 'macos-audio-check  Real native Apple MPS qualification, CPU comparison and convolution regression tests (model required)'
	@printf '%s\n' 'mobile-check       Flutter format, analysis, tests and Android network policy tests' 'mobile-build       Flutter Android debug APK' 'mobile-audio-test  Native karaoke instrumentation on a running emulator (isolated app ID)' 'web-check          NAS H5 and admin page/assets HTTP tests' 'tv-build           Android TV unit tests, debug APK and lint' 'tv-release         Signed release APK (requires signing environment)' 'backend-test       Go backend checks (alias of go-backend-test)' 'db-sqlite-check    SQLite connection and health tests' 'db-mysql-up        Start MySQL and wait for health' 'db-mysql-check     Go business tests in disposable loopback MySQL container' 'verify             Run all checks, including MySQL'
	@printf '%s\n' 'compute-setup      Create Python 3.12+ venv and install locked dependencies' 'compute-check      Python compute HTTP and internal contract tests' 'compute-run        Start compute node on 127.0.0.1:8090' 'compute-fast-audio-setup Install pinned fast classifier runtime and model for scans' 'compute-audio-setup Install optional pinned CPU Demucs runtime and local model (explicit download)' 'compute-audio-cuda-setup Install pinned Linux x86_64 CUDA audio runtime (model downloaded in admin)' 'compute-audio-rocm-setup Install pinned Linux x86_64 ROCm audio runtime (compatible GPUs only)' 'compute-audio-openvino-setup Install pinned Linux x86_64 OpenVINO audio runtime' 'compute-audio-uvr-setup Install ONNX CPU baseline runtime and pinned UVR 9482 model' 'compute-audio-rknn-setup Install native Linux ARM64 RKNN Lite2 runtime (no model download)' 'compute-images     Build backend image matrix using infra/docker/build/docker-bake.hcl' 'compute-package-nvidia Package CUDA source deployment without media mounts or .env' 'transcode-tools    Download checksum-pinned Jellyfin media tools into .tools (no global installation)' 'transcode-fixtures Download optional checksum-pinned official Dolby Vision verification samples'
	@printf '%s\n' 'backend-check      Go + Python tests, package and real process integration (run compute-setup first)' 'go-compute-check   Real Go/Python/FFmpeg process round trip in disposable data'
	@printf '%s\n' 'compute-audio-rocm-native-setup Install pinned native gfx1103 audio runtime (Linux x86_64 / Python 3.12)'
	@printf '%s\n' 'compute-separation-cpu-setup Install independent ONNX CPU worker (no GPU drivers)' 'compute-separation-mlx-setup Install isolated native macOS ARM64 MLX worker'
	@printf '%s\n' 'frontend-setup    Install locked frontend dependencies (Node.js 22.19+)' 'frontend-dev      Start unified frontend development server on 127.0.0.1:5173/admin/' 'frontend-check    Frontend typecheck and two-page build (run npm test separately)' 'frontend-build    Build portable assets in services/singverse-frontend/dist'
	@printf '%s\n' 'docker-build      Build Nginx, Go and base compute images' 'docker-up         Build and start default Docker app (AI runtime not included)' 'docker-down       Stop Docker app; preserve all named volumes' 'docker-status     Show Docker app status' 'docker-logs       Follow Docker app logs'
	@printf '%s\n' 'auth-docker-check Verify password/MFA/recovery in a disposable Docker project' 'docker-check      Verify Docker pages, assets, API health and 404 behavior (Python 3)'
	@printf '%s\n' 'nginx-media-check Verify media Range/HEAD and immediate streaming in isolated Docker fixtures'
	@printf '%s\n' 'docker-config-check Validate Compose profiles and deployment inputs without starting Docker containers' 'docker-context-check Audit actual Docker COPY filtering with scratch export and no application containers'

nginx-media-check:
	$(PYTHON) scripts/check-nginx-media.py

mobile-check:
	cd services/singverse-mobile && flutter --no-version-check pub get && dart format --output=none --set-exit-if-changed . && flutter --no-version-check analyze && flutter --no-version-check test
	cd services/singverse-mobile/android && ./gradlew :app:testDebugUnitTest

mobile-audio-test:
	cd services/singverse-mobile/android && ORG_GRADLE_PROJECT_singverseE2e=true ./gradlew :app:connectedDebugAndroidTest

mobile-build:
	cd services/singverse-mobile && flutter --no-version-check build apk --debug

services/singverse-frontend/node_modules/.package-lock.json: services/singverse-frontend/package.json services/singverse-frontend/package-lock.json
	cd services/singverse-frontend && npm ci

frontend-setup: services/singverse-frontend/node_modules/.package-lock.json

frontend-dev: frontend-setup
	cd services/singverse-frontend && npm run dev

frontend-check: frontend-setup
	cd services/singverse-frontend && npm run check

frontend-build: frontend-setup
	cd services/singverse-frontend && npm run build

web-check: go-backend-assets dbmate-setup
	sh scripts/run-go.sh test ./internal/web ./internal/app -run 'TestHostingContract|TestIdentityBoundariesAndFrontend'

tv-build:
	cd services/singverse-tv && ./gradlew :app:testDebugUnitTest assembleDebug lintDebug

tv-release:
	cd services/singverse-tv && ./gradlew -PsingverseSignRelease=true prepareTvRelease lintRelease

backend-check: go-backend-test go-backend-build
	$(MAKE) compute-check
	$(MAKE) go-compute-check

compute-setup:
	$(PYTHON) -c 'import sys; assert sys.version_info >= (3, 12), "Python 3.12+ required"'
	$(PYTHON) -m venv services/singverse-compute/.venv
	cd services/singverse-compute && .venv/bin/python -m pip install -r requirements.lock && .venv/bin/python -m pip install --no-deps --no-build-isolation -e .

transcode-fixtures:
	services/singverse-compute/.venv/bin/python scripts/setup-transcode-fixtures.py

transcode-tools:
	services/singverse-compute/.venv/bin/python scripts/setup-transcode-tools.py

compute-check:
	cd services/singverse-compute && PATH="$(CURDIR)/.tools/jellyfin-ffmpeg/bin:$(CURDIR)/.tools/jellyfin-ffmpeg:$$PATH" .venv/bin/python -m unittest discover -s tests -v

compute-audio-setup:
	cd services/singverse-compute && .venv/bin/python -m pip install -r requirements-audio.lock
	services/singverse-compute/.venv/bin/python scripts/setup-audio-model.py

.PHONY: compute-fast-audio-setup
compute-fast-audio-setup:
	cd services/singverse-compute && .venv/bin/python -m pip install -r requirements-audio.lock
	services/singverse-compute/.venv/bin/python scripts/setup-fast-audio-model.py

.PHONY: compute-audio-cuda-setup compute-audio-rocm-setup compute-audio-openvino-setup compute-images
.PHONY: macos-audio-check
macos-audio-check:
	services/singverse-compute/.venv/bin/python -c "import platform; assert platform.system() == 'Darwin', 'Run on native macOS with Apple GPU access'"
	PATH="$(CURDIR)/.tools/jellyfin-ffmpeg/bin:$(CURDIR)/.tools/jellyfin-ffmpeg:$$PATH" SINGVERSE_TEST_MPS_MODEL_REPO="$(CURDIR)/.tools/demucs-htdemucs" services/singverse-compute/.venv/bin/python -m unittest discover -s services/singverse-compute/tests -p test_mps_audio.py -v

compute-audio-cuda-setup:
	cd services/singverse-compute && .venv/bin/python -c "import platform; assert platform.system() == 'Linux' and platform.machine() == 'x86_64', 'CUDA package targets Linux x86_64'"
	cd services/singverse-compute && .venv/bin/python -m pip install -r requirements-audio-cuda.lock

compute-audio-rocm-setup:
	cd services/singverse-compute && .venv/bin/python -c "import platform; assert platform.system() == 'Linux' and platform.machine() == 'x86_64', 'ROCm package targets Linux x86_64'"
	cd services/singverse-compute && .venv/bin/python -m pip install -r requirements-audio-rocm.lock

.PHONY: compute-audio-rocm-native-setup
compute-audio-rocm-native-setup:
	cd services/singverse-compute && .venv/bin/python -c "import platform,sys; assert platform.system() == 'Linux' and platform.machine() == 'x86_64' and sys.version_info[:2] == (3,12), 'Native gfx1103 runtime targets Linux x86_64 / Python 3.12'"
	cd services/singverse-compute && .venv/bin/python -m pip install --no-build-isolation -r requirements-audio-rocm-native.lock
	cd services/singverse-compute && .venv/bin/python -m pip check

compute-audio-openvino-setup:
	cd services/singverse-compute && .venv/bin/python -c "import platform; assert platform.system() == 'Linux' and platform.machine() == 'x86_64', 'OpenVINO image targets Linux x86_64'"
	cd services/singverse-compute && .venv/bin/python -m pip install -r requirements-audio-openvino.lock

compute-images:
	VERSION="$$(cat VERSION)" docker buildx bake -f infra/docker/build/docker-bake.hcl

compute-run:
	cd services/singverse-compute && PATH="$(CURDIR)/.tools/jellyfin-ffmpeg/bin:$(CURDIR)/.tools/jellyfin-ffmpeg:$$PATH" .venv/bin/python -m singverse_compute

.PHONY: db-fresh-check
db-fresh-check: dbmate-setup
	$(PYTHON) scripts/check-database-baseline.py

db-sqlite-check: go-backend-assets db-fresh-check
	sh scripts/run-go.sh test -race ./internal/platform/store ./internal/app -run 'SQLite|DeploymentLock|MigrationFailure'

db-mysql-up:
	docker compose --project-directory . -f infra/docker/compose/compose.yaml up -d --wait --wait-timeout 180 mysql

db-mysql-check: go-mysql-check

docker-build:
	SINGVERSE_VERSION="$$(cat VERSION)" docker compose --project-directory . -f infra/docker/compose/compose.app.yaml build

docker-up:
	SINGVERSE_VERSION="$$(cat VERSION)" docker compose --project-directory . -f infra/docker/compose/compose.app.yaml up -d --build --wait --wait-timeout 180

docker-down:
	docker compose --project-directory . -f infra/docker/compose/compose.app.yaml down

docker-status:
	docker compose --project-directory . -f infra/docker/compose/compose.app.yaml ps

docker-logs:
	docker compose --project-directory . -f infra/docker/compose/compose.app.yaml logs -f --tail 100

docker-check:
	$(PYTHON) scripts/check-docker-app.py

docker-config-check:
	$(PYTHON) infra/docker/check-config.py

verify:
	$(MAKE) mobile-check
	$(MAKE) mobile-build
	$(MAKE) frontend-check
	$(MAKE) web-check
	$(MAKE) tv-build
	$(MAKE) backend-check
	$(MAKE) db-sqlite-check
	$(MAKE) db-mysql-check

.PHONY: dbmate-setup auth-docker-check
dbmate-setup:
	sh scripts/setup-dbmate.sh

# Creates and removes only its own random isolated Compose project/volumes.
auth-docker-check:
	$(PYTHON) scripts/check-auth-docker.py

.PHONY: compute-audio-rknn-setup
compute-audio-rknn-setup:
	services/singverse-compute/.venv/bin/python -c "import platform; assert platform.system() == 'Linux' and platform.machine() == 'aarch64', 'RKNN requires native Linux aarch64'"
	cd services/singverse-compute && .venv/bin/python -m pip install -r requirements-audio-rknn.lock

.PHONY: compute-audio-uvr-setup
compute-audio-uvr-setup:
	cd services/singverse-compute && .venv/bin/python -m pip install -r requirements-audio-uvr.lock
	services/singverse-compute/.venv/bin/python scripts/setup-uvr-model.py

.PHONY: compute-separation-cpu-setup
compute-separation-cpu-setup:
	services/singverse-compute/.venv/bin/python scripts/setup-separation-runtime.py

.PHONY: compute-separation-mlx-setup
compute-separation-mlx-setup:
	services/singverse-compute/.venv/bin/python -c "import platform; assert platform.system() == 'Darwin' and platform.machine() == 'arm64', 'Run on native Apple Silicon'"
	services/singverse-compute/.venv/bin/python scripts/setup-separation-runtime.py --directory services/singverse-compute/.venv-audio-mlx --lock services/singverse-compute/requirements-separation-mlx.lock --role accelerator

.PHONY: compute-package-nvidia
# Override NVIDIA_PACKAGE_OUTPUT only when another artifact destination is required.
NVIDIA_PACKAGE_OUTPUT ?= /tmp/singverse-nvidia-upload/SingVerse-NVIDIA-callback.zip
compute-package-nvidia:
	python3 scripts/package-nvidia.py --output "$(NVIDIA_PACKAGE_OUTPUT)"

.PHONY: app-package
APP_PACKAGE_OUTPUT ?= /tmp/singverse-business-upload/SingVerse-Business.zip
app-package:
	python3 scripts/package-app.py --output "$(APP_PACKAGE_OUTPUT)"

.PHONY: app-package-offline
APP_OFFLINE_BACKEND_IMAGE ?= singverse-go-backend:offline-amd64
APP_OFFLINE_FRONTEND_IMAGE ?= singverse-frontend:offline-amd64
APP_OFFLINE_PACKAGE_OUTPUT ?= /tmp/singverse-business-upload/SingVerse-Business-offline-amd64.zip
app-package-offline:
	python3 scripts/package-app-offline.py --output "$(APP_OFFLINE_PACKAGE_OUTPUT)" --backend-image "$(APP_OFFLINE_BACKEND_IMAGE)" --frontend-image "$(APP_OFFLINE_FRONTEND_IMAGE)"

.PHONY: backend-test backend-build backend-run java-rollback-up java-rollback-down
backend-test: go-backend-test
backend-build: go-backend-build
backend-run: go-backend-run

# Explicit legacy rollback entry uses only its own pre-Go project and volumes.
java-rollback-up:
	docker compose --project-directory . -f infra/docker/compose/compose.java-rollback.yaml up -d --no-build --wait --wait-timeout 180

java-rollback-down:
	docker compose --project-directory . -f infra/docker/compose/compose.java-rollback.yaml down

.PHONY: go-compute-check
go-compute-check: go-backend-assets dbmate-setup
	PATH="$(CURDIR)/.tools/jellyfin-ffmpeg/bin:$(CURDIR)/.tools/jellyfin-ffmpeg:$$PATH" $(PYTHON) scripts/check-go-compute.py

.PHONY: go-recovery-check go-container-check
go-recovery-check: go-backend-build dbmate-setup
	$(PYTHON) scripts/check-go-recovery.py

# The checker creates only random owned containers/network; it never opens deployment volumes.
go-container-check:
	$(PYTHON) infra/docker/check-go-container.py --backend-image "$(GO_BACKEND_IMAGE)" --frontend-image "$(GO_FRONTEND_IMAGE)"

.PHONY: docker-context-check
docker-context-check:
	$(PYTHON) infra/docker/check-build-context.py


.PHONY: metadata-standard-check
metadata-standard-check:
	sh scripts/run-go.sh test ./internal/metastandard ./internal/metastore
	sh scripts/run-go.sh run ./cmd/metadata-validate "$(CURDIR)/contracts/metadata/examples/song.valid.json"

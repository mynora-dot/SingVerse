SHELL := /bin/sh
.DEFAULT_GOAL := help
.PHONY: go-mysql-check go-tools-setup go-backend-assets go-backend-build go-backend-test go-backend-run mobile-check \
        mobile-audio-test mobile-build frontend-setup frontend-dev frontend-check frontend-build web-check \
        tv-build tv-release backend-check compute-setup transcode-fixtures transcode-tools compute-check \
        compute-audio-setup compute-fast-audio-setup macos-audio-check compute-audio-cuda-setup compute-audio-rocm-setup compute-audio-rocm-native-setup compute-audio-openvino-setup \
        compute-run db-fresh-check db-sqlite-check db-mysql-up db-mysql-check docker-down docker-status \
        docker-logs docker-check docker-config-check verify dbmate-setup auth-docker-check compute-audio-rknn-setup \
        compute-audio-uvr-setup compute-separation-cpu-setup compute-separation-mlx-setup backend-test backend-build backend-run go-compute-check \
        go-recovery-check docker-context-check metadata-standard-check help docker-build docker-up docker-pull \
        docker-image-up compute-images go-container-check docker-test
PYTHON ?= python3
GO_BACKEND_IMAGE ?= singverse:local
APP_COMPOSE = docker compose --project-directory . -f infra/docker/compose/compose.yaml
SOURCE_COMPOSE = $(APP_COMPOSE) -f infra/docker/compose/compose.build.yaml
COMPUTE_RUNTIME ?= base

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

backend-check: go-backend-test go-backend-build compute-check go-compute-check

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

compute-fast-audio-setup:
	cd services/singverse-compute && .venv/bin/python -m pip install -r requirements-audio.lock
	services/singverse-compute/.venv/bin/python scripts/setup-fast-audio-model.py

macos-audio-check:
	services/singverse-compute/.venv/bin/python -c "import platform; assert platform.system() == 'Darwin', 'Run on native macOS with Apple GPU access'"
	PATH="$(CURDIR)/.tools/jellyfin-ffmpeg/bin:$(CURDIR)/.tools/jellyfin-ffmpeg:$$PATH" SINGVERSE_TEST_MPS_MODEL_REPO="$(CURDIR)/.tools/demucs-htdemucs" services/singverse-compute/.venv/bin/python -m unittest discover -s services/singverse-compute/tests -p test_mps_audio.py -v

compute-audio-cuda-setup:
	cd services/singverse-compute && .venv/bin/python -c "import platform; assert platform.system() == 'Linux' and platform.machine() == 'x86_64', 'CUDA package targets Linux x86_64'"
	cd services/singverse-compute && .venv/bin/python -m pip install -r requirements-audio-cuda.lock

compute-audio-rocm-setup:
	cd services/singverse-compute && .venv/bin/python -c "import platform; assert platform.system() == 'Linux' and platform.machine() == 'x86_64', 'ROCm package targets Linux x86_64'"
	cd services/singverse-compute && .venv/bin/python -m pip install -r requirements-audio-rocm.lock

compute-audio-rocm-native-setup:
	cd services/singverse-compute && .venv/bin/python -c "import platform,sys; assert platform.system() == 'Linux' and platform.machine() == 'x86_64' and sys.version_info[:2] == (3,12), 'Native gfx1103 runtime targets Linux x86_64 / Python 3.12'"
	cd services/singverse-compute && .venv/bin/python -m pip install --no-build-isolation -r requirements-audio-rocm-native.lock
	cd services/singverse-compute && .venv/bin/python -m pip check

compute-audio-openvino-setup:
	cd services/singverse-compute && .venv/bin/python -c "import platform; assert platform.system() == 'Linux' and platform.machine() == 'x86_64', 'OpenVINO image targets Linux x86_64'"
	cd services/singverse-compute && .venv/bin/python -m pip install -r requirements-audio-openvino.lock

compute-run:
	cd services/singverse-compute && PATH="$(CURDIR)/.tools/jellyfin-ffmpeg/bin:$(CURDIR)/.tools/jellyfin-ffmpeg:$$PATH" .venv/bin/python -m singverse_compute

db-fresh-check: dbmate-setup
	$(PYTHON) scripts/check-database-baseline.py

db-sqlite-check: go-backend-assets db-fresh-check
	sh scripts/run-go.sh test -race ./internal/platform/store ./internal/app -run 'SQLite|DeploymentLock|MigrationFailure'

db-mysql-up:
	docker compose --project-directory . -f infra/docker/compose/compose.mysql.yaml up -d --wait --wait-timeout 180 mysql

db-mysql-check: go-mysql-check

docker-down:
	$(APP_COMPOSE) down

docker-status:
	$(APP_COMPOSE) ps

docker-logs:
	$(APP_COMPOSE) logs -f --tail 100

docker-check:
	$(PYTHON) infra/docker/check-docker-app.py

docker-config-check:
	$(PYTHON) infra/docker/check-config.py

verify: mobile-check mobile-build frontend-check web-check tv-build backend-check db-sqlite-check db-mysql-check

dbmate-setup:
	sh scripts/setup-dbmate.sh

# Creates and removes only its own random isolated Compose project/volumes.
auth-docker-check:
	$(PYTHON) infra/docker/check-auth-docker.py --backend-image "$(GO_BACKEND_IMAGE)"

compute-audio-rknn-setup:
	services/singverse-compute/.venv/bin/python -c "import platform; assert platform.system() == 'Linux' and platform.machine() == 'aarch64', 'RKNN requires native Linux aarch64'"
	cd services/singverse-compute && .venv/bin/python -m pip install -r requirements-audio-rknn.lock

compute-audio-uvr-setup:
	cd services/singverse-compute && .venv/bin/python -m pip install -r requirements-audio-uvr.lock
	services/singverse-compute/.venv/bin/python scripts/setup-uvr-model.py

compute-separation-cpu-setup:
	services/singverse-compute/.venv/bin/python scripts/setup-separation-runtime.py

compute-separation-mlx-setup:
	services/singverse-compute/.venv/bin/python -c "import platform; assert platform.system() == 'Darwin' and platform.machine() == 'arm64', 'Run on native Apple Silicon'"
	services/singverse-compute/.venv/bin/python scripts/setup-separation-runtime.py --directory services/singverse-compute/.venv-audio-mlx --lock services/singverse-compute/requirements-separation-mlx.lock --role accelerator

backend-test: go-backend-test
backend-build: go-backend-build
backend-run: go-backend-run

go-compute-check: go-backend-assets dbmate-setup
	PATH="$(CURDIR)/.tools/jellyfin-ffmpeg/bin:$(CURDIR)/.tools/jellyfin-ffmpeg:$$PATH" $(PYTHON) scripts/check-go-compute.py

go-recovery-check: go-backend-build dbmate-setup
	$(PYTHON) scripts/check-go-recovery.py

# The checker creates only random owned containers/network; it never opens deployment volumes.

docker-context-check:
	$(PYTHON) infra/docker/check-build-context.py

metadata-standard-check:
	sh scripts/run-go.sh test ./internal/metastandard ./internal/metastore
	sh scripts/run-go.sh run ./cmd/metadata-validate "$(CURDIR)/contracts/metadata/examples/song.valid.json"

help:
	@printf '%s\n' 'Docker source: docker-build, docker-up' 'Docker published images: docker-pull, docker-image-up' 'Docker maintenance: docker-status, docker-logs, docker-down (preserves volumes)' 'Docker verification: docker-config-check, docker-context-check, docker-check, go-container-check, auth-docker-check, docker-test' 'Compute image build: compute-images COMPUTE_RUNTIME=base|cpu|uvr|cuda|rocm|rocm-native|openvino|rknn' 'Backend: go-tools-setup, dbmate-setup, go-backend-build, go-backend-run, go-backend-test, backend-check' 'Frontend: frontend-setup, frontend-dev, frontend-check, frontend-build, web-check' 'Database: db-fresh-check, db-sqlite-check, db-mysql-up, db-mysql-check' 'Devices: mobile-check, mobile-build, mobile-audio-test, tv-build, tv-release' 'Compute: compute-setup, compute-check, compute-run, transcode-tools, transcode-fixtures' 'Optional audio runtimes: compute-audio-setup, compute-fast-audio-setup, compute-audio-cuda-setup, compute-audio-rocm-setup, compute-audio-rocm-native-setup, compute-audio-openvino-setup, compute-audio-rknn-setup, compute-audio-uvr-setup, compute-separation-cpu-setup, compute-separation-mlx-setup' 'Additional checks: go-compute-check, go-recovery-check, macos-audio-check, metadata-standard-check, verify'

docker-build:
	$(SOURCE_COMPOSE) build

docker-up:
	$(SOURCE_COMPOSE) up -d --build --pull missing --wait --wait-timeout 180

docker-pull:
	$(APP_COMPOSE) pull

docker-image-up:
	$(APP_COMPOSE) up -d --no-build --pull always --wait --wait-timeout 180

compute-images:
	COMPUTE_RUNTIME="$(COMPUTE_RUNTIME)" docker compose --project-directory . -f infra/docker/compose/compose.compute.yaml -f infra/docker/compose/compose.compute-build.yaml build

go-container-check:
	$(PYTHON) infra/docker/check-go-container.py --backend-image "$(GO_BACKEND_IMAGE)"

docker-test:
	$(PYTHON) -m unittest discover -s infra/docker/tests -v

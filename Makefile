SHELL := /bin/sh
.DEFAULT_GOAL := help
PYTHON ?= python3
.PHONY: compute-audio-setup transcode-fixtures transcode-tools help mobile-check mobile-build mobile-audio-test frontend-setup frontend-dev frontend-check frontend-build web-check tv-build tv-release java-backend-test backend-check compute-setup compute-check compute-run db-sqlite-check db-mysql-up db-mysql-check docker-build docker-up docker-down docker-status docker-logs docker-check verify

help:
	@printf '%s\n' 'dbmate-setup      Install checksum-pinned language-neutral migration tool'
	@printf '%s\n' 'macos-audio-check  Real native Apple MPS qualification, CPU comparison and convolution regression tests (model required)'
	@printf '%s\n' 'mobile-check       Flutter format, analysis, tests and Android network policy tests' 'mobile-build       Flutter Android debug APK' 'mobile-audio-test  Native karaoke instrumentation on a running emulator (isolated app ID)' 'web-check          NAS H5 and admin page/assets HTTP tests' 'tv-build           Android TV unit tests, debug APK and lint' 'tv-release         Signed release APK (requires signing environment)' 'java-backend-test  SingVerse Java backend tests and package' 'db-sqlite-check    SQLite connection and health tests' 'db-mysql-up        Start MySQL and wait for health' 'db-mysql-check     MySQL connection and health tests (requires running database)' 'verify             Run all checks, including MySQL'
	@printf '%s\n' 'compute-setup      Create Python 3.12+ venv and install locked dependencies' 'compute-check      Python compute HTTP and internal contract tests' 'compute-run        Start compute node on 127.0.0.1:8090' 'compute-fast-audio-setup Install pinned fast classifier runtime and model for scans' 'compute-audio-setup Install optional pinned CPU Demucs runtime and local model (explicit download)' 'compute-audio-cuda-setup Install pinned Linux x86_64 CUDA audio runtime (model downloaded in admin)' 'compute-audio-rocm-setup Install pinned Linux x86_64 ROCm audio runtime (compatible GPUs only)' 'compute-audio-openvino-setup Install pinned Linux x86_64 OpenVINO audio runtime' 'compute-images     Build backend image matrix using docker-bake.hcl' 'transcode-tools    Download checksum-pinned Jellyfin media tools into .tools (no global installation)' 'transcode-fixtures Download optional checksum-pinned official Dolby Vision verification samples'
	@printf '%s\n' 'backend-check      Java + Python tests, package and real process integration (run compute-setup first)'
	@printf '%s\n' 'frontend-setup    Install locked frontend dependencies (Node.js 22.19+)' 'frontend-dev      Start unified frontend development server on 127.0.0.1:5173/admin/' 'frontend-check    Frontend typecheck and two-page build (run npm test separately)' 'frontend-build    Build portable assets in services/singverse-frontend/dist'
	@printf '%s\n' 'docker-build      Build frontend and Java backend images with tests' 'docker-up         Build and start Docker app at 127.0.0.1:18080' 'docker-down       Stop Docker app; preserve SQLite named volume' 'docker-status     Show Docker app status' 'docker-logs       Follow Docker app logs'
	@printf '%s\n' 'auth-docker-check Verify password/MFA/recovery in a disposable Docker project' 'docker-check      Verify Docker pages, assets, API health and 404 behavior (Python 3)'

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

web-check: frontend-build dbmate-setup
	cd services/singverse-java-backend && ./mvnw -B -Dtest=WebWelcomeTest,AdminWebTest test

tv-build:
	cd services/singverse-tv && ./gradlew :app:testDebugUnitTest assembleDebug lintDebug

tv-release:
	cd services/singverse-tv && ./gradlew -PsingverseSignRelease=true prepareTvRelease lintRelease

java-backend-test: frontend-build dbmate-setup
	cd services/singverse-java-backend && ./mvnw -B verify

backend-check: frontend-build dbmate-setup
	$(MAKE) compute-check
	cd services/singverse-java-backend && PATH="$(CURDIR)/.tools/jellyfin-ffmpeg/bin:$(CURDIR)/.tools/jellyfin-ffmpeg:$$PATH" ./mvnw -B -Pbackend-check verify

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

compute-audio-openvino-setup:
	cd services/singverse-compute && .venv/bin/python -c "import platform; assert platform.system() == 'Linux' and platform.machine() == 'x86_64', 'OpenVINO image targets Linux x86_64'"
	cd services/singverse-compute && .venv/bin/python -m pip install -r requirements-audio-openvino.lock

compute-images:
	docker buildx bake

compute-run:
	cd services/singverse-compute && PATH="$(CURDIR)/.tools/jellyfin-ffmpeg/bin:$(CURDIR)/.tools/jellyfin-ffmpeg:$$PATH" .venv/bin/python -m singverse_compute

db-sqlite-check: frontend-build dbmate-setup
	cd services/singverse-java-backend && ./mvnw -B -Dtest=SQLiteConnectionTest test

db-mysql-up:
	docker compose up -d --wait --wait-timeout 180 mysql

db-mysql-check: frontend-build dbmate-setup
	cd services/singverse-java-backend && ./mvnw -B -Pmysql-check verify

docker-build:
	docker compose -f compose.app.yaml build

docker-up:
	docker compose -f compose.app.yaml up -d --build --wait --wait-timeout 180

docker-down:
	docker compose -f compose.app.yaml down

docker-status:
	docker compose -f compose.app.yaml ps

docker-logs:
	docker compose -f compose.app.yaml logs -f --tail 100

docker-check:
	$(PYTHON) scripts/check-docker-app.py

verify:
	$(MAKE) mobile-check
	$(MAKE) mobile-build
	$(MAKE) frontend-check
	$(MAKE) web-check
	$(MAKE) tv-build
	$(MAKE) backend-check
	$(MAKE) db-sqlite-check
	$(MAKE) db-mysql-up
	$(MAKE) db-mysql-check

.PHONY: dbmate-setup auth-docker-check
dbmate-setup:
	sh scripts/setup-dbmate.sh

# Creates and removes only its own random isolated Compose project/volumes.
auth-docker-check:
	$(PYTHON) scripts/check-auth-docker.py

import importlib.util
from contextlib import nullcontext
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


class DeploymentArchiveTest(unittest.TestCase):
    def test_zip_permissions_allow_nonowner_to_read_and_traverse(self):
        root = Path(__file__).resolve().parents[4]
        spec = importlib.util.spec_from_file_location('package_rk3566', root/'scripts/package-rk3566.py')
        package = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(package)
        files = {'services/singverse-compute/singverse_compute/__init__.py': b'',
                 'models/uvr/model.onnx': b'model', '.env.example': b'placeholder'}
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)/'deployment.zip'
            package.write_bundle(output, files)
            with zipfile.ZipFile(output) as bundle:
                self.assertIsNone(bundle.testzip())
                for member in bundle.infolist():
                    mode = member.external_attr >> 16
                    if member.is_dir():
                        self.assertTrue(stat.S_ISDIR(mode))
                        self.assertEqual(stat.S_IMODE(mode), 0o755)
                    else:
                        self.assertTrue(stat.S_ISREG(mode))
                        self.assertEqual(stat.S_IMODE(mode), 0o644)
                for name, data in files.items():
                    self.assertEqual(bundle.read('singverse-rk3566/'+name), data)

    def test_nvidia_archive_has_no_media_mount_or_secrets_and_valid_digests(self):
        import hashlib
        root = Path(__file__).resolve().parents[4]
        spec = importlib.util.spec_from_file_location('package_nvidia', root/'scripts/package-nvidia.py')
        package = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(package)
        files = package.contents()
        compose = files['docker-compose.yaml'].decode()
        for forbidden in ('COMPUTE_JAVA_ORIGIN', 'COMPUTE_MEDIA_ROOTS', './media:', 'env_file:', '${'):
            self.assertNotIn(forbidden, compose)
        self.assertIn('singverse-compute-auth', compose)
        self.assertIn('127.0.0.1:18090:8090', compose)
        self.assertNotIn('services/singverse-compute/singverse_compute/core/build.json', files)
        self.assertEqual(files['VERSION'], (root/'VERSION').read_bytes())
        self.assertEqual(files['部署说明.md'], (root/'infra/deploy/nvidia/README.md').read_bytes())
        self.assertEqual(files['README.operations.md'], (root/'infra/docker/OPERATIONS.md').read_bytes())
        self.assertFalse(any(Path(name).name == '.env' or name.endswith(('.pth', '.th', '.db', '.log')) for name in files))
        for row in files['SHA256SUMS'].decode().splitlines():
            digest, name = row.split('  ', 1)
            self.assertEqual(hashlib.sha256(files[name]).hexdigest(), digest)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)/'nvidia.zip'
            package.write_bundle(output, files)
            with zipfile.ZipFile(output) as bundle:
                self.assertIsNone(bundle.testzip())
                for item in bundle.infolist():
                    self.assertEqual(stat.S_IMODE(item.external_attr >> 16), 0o755 if item.is_dir() else 0o644)
                for name, data in files.items():
                    self.assertEqual(bundle.read('SingVerse-NVIDIA/'+name), data)

    def test_business_source_archive_can_build_after_extraction_without_local_data(self):
        import hashlib
        root = Path(__file__).resolve().parents[4]
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)/'business.zip'
            subprocess.run([sys.executable, str(root/'scripts/package-app.py'), '--output', str(output)],
                           check=True, capture_output=True, text=True)
            with zipfile.ZipFile(output) as bundle:
                self.assertIsNone(bundle.testzip())
                prefix = 'SingVerse-Business/'
                files = {item.filename.removeprefix(prefix): bundle.read(item) for item in bundle.infolist() if not item.is_dir()}
                for name in ('.dockerignore', '部署说明.md', 'README.offline.md', 'README.operations.md',
                             'infra/docker/build/go.Dockerfile',
                             'infra/docker/build/go.Dockerfile.dockerignore',
                             'services/singverse-go-backend/go.mod',
                             'services/singverse-go-backend/go.sum', 'VERSION',
                             'services/singverse-frontend/package-lock.json',
                             'scripts/setup-dbmate.sh', 'contracts/openapi/singverse.openapi.json',
                             'contracts/go-migration-baseline.json'):
                    self.assertIn(name, files, f'Missing fresh deployment input: {name}')
                for database in ('sqlite', 'mysql'):
                    self.assertIn(f'database/migrations/{database}/202611070001_initial_schema.sql', files)
                self.assertFalse(any(Path(name).name == '.env' or name.endswith(('.db', '.key', '.pem', '.pth', '.th', '.log')) for name in files))
                self.assertFalse(any(name.startswith(('data/', 'media/', 'models/', 'services/singverse-compute/', 'services/singverse-java-backend/', 'services/singverse-go-backend/data/', 'services/singverse-go-backend/bin/')) for name in files))
                for row in files['SHA256SUMS'].decode().splitlines():
                    digest, name = row.split('  ', 1)
                    self.assertEqual(hashlib.sha256(files[name]).hexdigest(), digest, name)
                for item in bundle.infolist():
                    name = item.filename.removeprefix(prefix)
                    executable = name == 'scripts/setup-dbmate.sh'
                    self.assertEqual(stat.S_IMODE(item.external_attr >> 16),
                                     0o755 if item.is_dir() or executable else 0o644, name)

    def test_source_package_reads_untracked_allowlisted_inputs_and_skips_deleted(self):
        root = Path(__file__).resolve().parents[4]
        spec = importlib.util.spec_from_file_location('package_app_inventory', root/'scripts/package-app.py')
        package = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(package)
        expected = package.contents()
        inventory = [name for name in expected if (root/name).is_file()]
        inventory += ['services/singverse-go-backend/internal/removed.go', 'services/singverse-java-backend/src/Legacy.java']
        def source_query(command, **kwargs):
            self.assertEqual(command, ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'])
            return '\0'.join(inventory).encode()
        with patch.object(package.subprocess, 'check_output', side_effect=source_query):
            actual = package.contents()
        self.assertEqual(actual, expected)
        self.assertIn('VERSION', actual)
        self.assertIn('services/singverse-go-backend/cmd/singverse/main.go', actual)

    def test_source_packages_ignore_mac_sidecars_without_changing_valid_inputs(self):
        root = Path(__file__).resolve().parents[4]
        templates = ('infra/docker/build/.dockerignore', 'infra/docker/build/go.Dockerfile.dockerignore', 'infra/docker/OPERATIONS.md',
                     'infra/docker/compose/compose.compute.nvidia.yaml', 'infra/deploy/nvidia/README.md',
                     'infra/deploy/app/docker-compose.yaml', 'infra/deploy/app/README.md',
                     'infra/deploy/app/README.offline.md')
        sidecars = ('services/singverse-go-backend/internal/library/sql/._example.sql',
                    'services/singverse-go-backend/internal/.DS_Store',
                    'services/singverse-go-backend/internal/__MACOSX/example.go',
                    'services/singverse-compute/singverse_compute/._example.py',
                    'services/singverse-compute/singverse_compute/__MACOSX/example.py',
                    'services/singverse-compute/docker/._install.sh')
        for script in ('package-app', 'package-nvidia'):
            with self.subTest(script=script), tempfile.TemporaryDirectory() as temporary:
                spec = importlib.util.spec_from_file_location(script, root/f'scripts/{script}.py')
                package = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(package)
                expected = package.contents()
                fixture = Path(temporary)
                for name, data in expected.items():
                    destination = fixture/name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(data)
                for name in templates:
                    destination = fixture/name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes((root/name).read_bytes())
                for name in sidecars:
                    destination = fixture/name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(b'\x00\x05\x16\x07\xff\xfe AppleDouble, not source')
                # Include sidecars even if already tracked or not covered by Git ignores.
                sources = '\0'.join((*expected, *sidecars)).encode()
                query = patch.object(package.subprocess, 'check_output', return_value=sources) if hasattr(package, 'subprocess') else nullcontext()
                with patch.object(package, 'ROOT', fixture), query:
                    self.assertEqual(package.contents(), expected)

    def test_offline_packages_include_operations_guide_in_verified_manifest(self):
        import hashlib
        root = Path(__file__).resolve().parents[4]
        spec = importlib.util.spec_from_file_location('package_offline', root/'scripts/package-app-offline.py')
        package = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(package)
        def inspect_fixture(command, **kwargs):
            import json
            return json.dumps([{'Id': 'sha256:' + ('a' if 'backend' in command[-1] else 'b') * 64, 'Os': 'linux', 'Architecture': 'amd64'}])
        def save_fixture(command, **kwargs):
            self.assertEqual(command[:3], ['docker', 'image', 'save'])
            self.assertTrue(all(':offline-amd64' in name and not name.startswith('sha256:') for name in command[5:]))
            Path(command[4]).write_bytes(b'isolated unit-test image archive')
        for frontend_only in (False, True):
            with self.subTest(frontend_only=frontend_only), tempfile.TemporaryDirectory() as temporary:
                output = Path(temporary)/'offline.zip'
                arguments = ['package-app-offline.py', '--output', str(output)]
                if frontend_only:
                    arguments.append('--frontend-only')
                with patch.object(sys, 'argv', arguments), \
                     patch.object(package.subprocess, 'check_output', side_effect=inspect_fixture), \
                     patch.object(package.subprocess, 'run', side_effect=save_fixture), \
                     patch('builtins.print'):
                    package.main()
                with zipfile.ZipFile(output) as bundle:
                    prefix = 'SingVerse-Frontend-Update/' if frontend_only else 'SingVerse-Business/'
                    self.assertEqual(bundle.read(prefix+'VERSION'), (root/'VERSION').read_bytes())
                    self.assertIn(prefix+'images.json', bundle.namelist())
                    if not frontend_only:
                        compose = bundle.read(prefix+'docker-compose.yaml').decode()
                        self.assertIn('image: singverse-go-backend:offline-amd64', compose)
                        self.assertNotIn('image: sha256:', compose)
                    self.assertEqual(bundle.read(prefix+'README.operations.md'),
                                     (root/'infra/docker/OPERATIONS.md').read_bytes())
                    for row in bundle.read(prefix+'SHA256SUMS').decode().splitlines():
                        digest, name = row.split('  ', 1)
                        self.assertEqual(hashlib.sha256(bundle.read(prefix+name)).hexdigest(), digest)

    def test_offline_export_rejects_image_tag_changes_before_writing_zip(self):
        import json
        root = Path(__file__).resolve().parents[4]
        spec = importlib.util.spec_from_file_location('package_offline_race', root/'scripts/package-app-offline.py')
        package = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(package)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)/'must-not-exist.zip'
            initial = json.dumps([{'Id': 'sha256:'+'a'*64, 'Os': 'linux', 'Architecture': 'amd64'}])
            changed = json.dumps([{'Id': 'sha256:'+'b'*64}])
            with patch.object(sys, 'argv', ['package-app-offline.py', '--frontend-only', '--output', str(output)]), \
                 patch.object(package.subprocess, 'check_output', side_effect=[initial, changed]), \
                 patch.object(package.subprocess, 'run'):
                with self.assertRaisesRegex(ValueError, 'tag changed'):
                    package.main()
            self.assertFalse(output.exists())

    def test_offline_compose_keeps_readonly_media_and_never_builds_or_pulls(self):
        root = Path(__file__).resolve().parents[4]
        spec = importlib.util.spec_from_file_location('package_offline', root/'scripts/package-app-offline.py')
        package = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(package)
        compose = package.offline_compose()
        self.assertNotIn('    build:', compose)
        self.assertNotIn(':remote', compose)
        self.assertEqual(compose.count('pull_policy: never'), 2)
        self.assertEqual(compose.count('platform: linux/amd64'), 2)
        self.assertIn('read_only: true', compose)
        self.assertIn('create_host_path: false', compose)
        self.assertIn('127.0.0.1:18080:80', compose)
        self.assertIn("SINGVERSE_CALLBACK_ORIGIN: ''", compose)

import importlib.util
from pathlib import Path
import stat
import tempfile
import unittest
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

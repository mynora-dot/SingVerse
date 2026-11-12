import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('release', ROOT / 'infra/docker/release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def env(self):
        return dict(RELEASE_TAG='v' + (ROOT / 'VERSION').read_text().strip().removesuffix('-SNAPSHOT'), GITHUB_REPOSITORY_OWNER='mynora-dot',
                    DOCKERHUB_NAMESPACE='custom-hub', ACR_REGISTRY='registry.example.com', ACR_NAMESPACE='custom-acr',
                    GHCR_TOKEN='test', DOCKERHUB_USERNAME='test', DOCKERHUB_TOKEN='test',
                    ACR_USERNAME='test', ACR_PASSWORD='test')

    def test_namespaces_are_independent_configuration(self):
        env = self.env()
        version, prefixes = release.config(env)
        self.assertEqual(version, (ROOT / 'VERSION').read_text().strip().removesuffix('-SNAPSHOT'))
        self.assertEqual(prefixes, ['ghcr.io/mynora-dot', 'docker.io/custom-hub', 'registry.example.com/custom-acr'])
        env['GITHUB_REPOSITORY_OWNER'] = 'MyNora-Dot'
        self.assertEqual(release.config(env)[1][0], 'ghcr.io/mynora-dot')
        env['GHCR_NAMESPACE'] = 'custom-ghcr'
        self.assertEqual(release.config(env)[1][0], 'ghcr.io/custom-ghcr')

    def test_missing_destination_or_secret_fails_before_publishing(self):
        for key in ('DOCKERHUB_NAMESPACE', 'ACR_REGISTRY', 'ACR_NAMESPACE', 'GHCR_TOKEN',
                    'DOCKERHUB_USERNAME', 'DOCKERHUB_TOKEN', 'ACR_USERNAME', 'ACR_PASSWORD'):
            env = self.env()
            del env[key]
            with self.subTest(key=key), self.assertRaises(ValueError):
                release.config(env)

    def test_release_tag_and_registry_validation(self):
        for key, value in [('RELEASE_TAG', 'v9.0.0'), ('ACR_REGISTRY', 'https://registry.example.com'),
                           ('DOCKERHUB_NAMESPACE', 'UpperCase'), ('ACR_NAMESPACE', 'a/b')]:
            env = self.env()
            env[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                release.config(env)

    def test_existing_equal_tag_is_retryable_and_different_tag_is_never_written(self):
        for existing in ('sha256:equal', 'sha256:different'):
            with patch.object(release, 'remote_digest', return_value=existing), patch.object(release, 'run') as publish:
                if existing.endswith('different'):
                    with self.assertRaises(ValueError):
                        release.ensure('registry/image:1.0.0', 'sha256:equal', publish)
                else:
                    release.ensure('registry/image:1.0.0', 'sha256:equal', publish)
                publish.assert_not_called()

    def test_absent_tag_is_verified_after_copy(self):
        with patch.object(release, 'remote_digest', side_effect=[None, 'sha256:expected']), patch.object(release, 'run') as copy:
            release.ensure('registry/image:1.0.0', 'sha256:expected', copy)
            copy.assert_called_once()

    def test_auth_and_network_errors_do_not_count_as_absent(self):
        for error in ('unauthorized', 'connection refused', 'timeout'):
            with patch.object(release.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', error)):
                with self.assertRaises(RuntimeError):
                    release.remote_digest('registry/image:1.0.0')

    def test_latest_is_not_touched_if_any_version_destination_fails(self):
        raw = json.dumps({'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.manifest.v1+json'}).encode()
        calls = []
        def ensure(ref, digest, publish):
            calls.append(ref)
            if ref.startswith('registry.example.com/'):
                raise RuntimeError('destination unavailable')
        with patch.dict(release.os.environ, self.env(), clear=True), patch.object(release, 'VARIANTS', {'app': ['amd64']}), \
             patch.object(release.subprocess, 'check_output', return_value=raw), patch.object(release, 'ensure', side_effect=ensure):
            with self.assertRaises(RuntimeError):
                release.publish(Path('/unused'))
        self.assertFalse(any(':latest' in ref for ref in calls))

    def test_latest_only_follows_every_successful_fixed_tag(self):
        raw = json.dumps({'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.manifest.v1+json'}).encode()
        events = []
        def ensure(ref, digest, publish):
            events.append(('fixed', ref, digest))
            publish()
        def copy(*args):
            if args[-1].endswith(':latest'):
                events.append(('latest', args[-1]))
        with patch.dict(release.os.environ, self.env(), clear=True), patch.object(release, 'VARIANTS', {'app': ['amd64']}), \
             patch.object(release.subprocess, 'check_output', return_value=raw), patch.object(release, 'ensure', side_effect=ensure), \
             patch.object(release, 'layout'), patch.object(release, 'run', side_effect=copy):
            release.publish(Path('/unused'))
        self.assertEqual([event[0] for event in events], ['fixed'] * 6 + ['latest'] * 3)
        self.assertEqual(len({event[2] for event in events[3:6]}), 1)
        self.assertTrue(all(event[1].startswith('docker://') for event in events[-3:]))
        self.assertEqual(len({event[1] for event in events[-3:]}), 3)

    def test_version_index_failure_does_not_update_latest(self):
        raw = json.dumps({'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.manifest.v1+json'}).encode()
        def ensure(ref, digest, publish):
            if not ref.endswith('-amd64'):
                raise RuntimeError('index publication failed')
        with patch.dict(release.os.environ, self.env(), clear=True), patch.object(release, 'VARIANTS', {'app': ['amd64']}), \
             patch.object(release.subprocess, 'check_output', return_value=raw), patch.object(release, 'ensure', side_effect=ensure), \
             patch.object(release, 'layout'), patch.object(release, 'run') as copy:
            with self.assertRaises(RuntimeError):
                release.publish(Path('/unused'))
            copy.assert_not_called()

    def test_oci_layout_preserves_blob_bytes_and_deterministic_index(self):
        raw = release.manifest([])
        data = b'checked image content'
        digest = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'app-amd64'
            source.mkdir()
            with tarfile.open(source / 'image.tar', 'w') as archive:
                member = tarfile.TarInfo('blobs/sha256/' + digest)
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
            with patch.object(release, 'VARIANTS', {'app': ['amd64']}):
                release.layout(root, 'app', raw, root / 'layout')
            self.assertEqual((root / 'layout/blobs/sha256' / digest).read_bytes(), data)
            index = json.loads((root / 'layout/index.json').read_text())
            descriptor = index['manifests'][0]
            self.assertEqual(descriptor['digest'], 'sha256:' + hashlib.sha256(raw).hexdigest())
            self.assertEqual((root / 'layout/blobs/sha256' / descriptor['digest'].split(':')[1]).read_bytes(), raw)


if __name__ == '__main__':
    unittest.main()

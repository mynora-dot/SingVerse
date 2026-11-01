import hashlib
import io
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

from singverse_compute.application.audio_catalog import MODELS, OPENVINO, model_digest
from singverse_compute.application.audio_models import AudioModels
from singverse_compute.application.audio_roles import AudioRoles
from singverse_compute.core.errors import ComputeError


class AudioBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        audio = SimpleNamespace(root=root/'tasks', repo=root/'htdemucs', digest=AudioRoles.digest,
                                dependencies_available=True, runtime_available=False,
                                settings=SimpleNamespace(minimum_free_bytes=0, audio_memory_bytes=1024))
        audio.transcoding = SimpleNamespace(backends=SimpleNamespace(cpu_fallback=True))
        audio.root.mkdir()
        self.manager = AudioModels(audio)
        self.data = {'htdemucs_fwd.xml': b'controlled xml', 'htdemucs_fwd.bin': b'controlled weights'}
        fixture = {**MODELS[OPENVINO], 'files': [
            {'name': name, 'size': len(value), 'sha256': hashlib.sha256(value).hexdigest(), 'url': 'https://example.invalid/' + name}
            for name, value in self.data.items()]}
        patcher = patch.dict(MODELS, {OPENVINO: fixture})
        patcher.start()
        self.addCleanup(patcher.stop)
        self.manager.environment = {'fingerprint': 'env', 'devices': {'openvino': {'id': 'GPU.0', 'name': 'fixture'}}, 'versions': {'openvino': '2025.4.0'}}

    def archive(self, members=None):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as archive:
            for name, value in (members or self.data).items():
                archive.writestr(name, value)
        buffer.seek(0)
        return buffer

    def install(self, archive):
        manager = self.manager
        manager.begin('IMPORTING', OPENVINO)
        with manager.stage() as stage:
            manager.import_bundle(archive, Path(stage), OPENVINO)

    def test_multifile_install_is_atomic_idempotent_and_independent_of_baseline(self):
        for _ in range(2):
            self.install(self.archive())
            self.assertTrue(self.manager.model_valid(OPENVINO))
            self.assertFalse(self.manager.weights_valid())
        self.manager.qualified['openvino'] = {'state': 'AVAILABLE', 'environment': 'env', 'digest': model_digest(OPENVINO)}
        self.assertEqual(self.manager.selected(), 'openvino')
        self.manager.environment['fingerprint'] = 'new-build'
        self.assertIsNone(self.manager.selected())

    def test_corrupt_archive_cannot_replace_existing_model(self):
        self.install(self.archive())
        wrong = {**self.data, 'htdemucs_fwd.bin': b'wrong size'}
        with self.assertRaises(ComputeError):
            self.install(self.archive(wrong))
        self.assertTrue(self.manager.model_valid(OPENVINO))
        self.assertEqual((self.manager.model_path(OPENVINO)/'htdemucs_fwd.bin').read_bytes(), self.data['htdemucs_fwd.bin'])

    def test_archive_rejects_paths_extra_members_and_wrong_hash(self):
        for members in ({**self.data, '../escaped': b'x'}, {'folder/'+k: v for k,v in self.data.items()},
                        {**self.data, 'htdemucs_fwd.bin': b'x'*len(self.data['htdemucs_fwd.bin'])}):
            with self.assertRaises(ComputeError):
                self.install(self.archive(members))
            self.manager.failed(ComputeError(422, 'invalid fixture'))
            self.assertFalse(self.manager.model_path(OPENVINO).exists())
        self.assertFalse((Path(self.temp.name)/'escaped').exists())

    def test_missing_image_cannot_reuse_a_previous_qualification(self):
        self.install(self.archive())
        self.manager.qualified['openvino'] = {'state': 'AVAILABLE', 'environment': 'env', 'digest': model_digest(OPENVINO)}
        with patch('singverse_compute.application.audio_models.includes_backend', return_value=False):
            entry = next(b for b in self.manager.entries() if b['id'] == 'openvino')
            self.assertEqual(entry['state'], 'IMAGE_MISSING')
            self.assertIsNone(self.manager.selected())

    def test_cancelled_install_preserves_existing_valid_model(self):
        self.install(self.archive())
        self.manager.begin('IMPORTING', OPENVINO)
        self.manager.stop()
        with self.manager.stage() as stage, self.assertRaises(ComputeError):
            self.manager.import_bundle(self.archive(), Path(stage), OPENVINO)
        self.assertTrue(self.manager.model_valid(OPENVINO))

    def test_fast_single_file_bundle_is_independent_of_separation_qualification(self):
        from singverse_compute.application.audio_catalog import FAST
        data = b'controlled classifier'
        file = {'name': 'Cnn6_mAP=0.343.pth', 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest(), 'url': 'https://example.invalid/classifier'}
        with patch.dict(MODELS, {FAST: {**MODELS[FAST], 'files': [file]}}):
            self.manager.begin('IMPORTING', FAST)
            with self.manager.stage() as stage:
                self.manager.import_bundle(self.archive({file['name']: data}), Path(stage), FAST)
            self.assertTrue(self.manager.model_valid(FAST))
            self.assertFalse(self.manager.weights_valid())
            self.assertFalse(self.manager.qualified)
            status = self.manager.status()
            self.assertEqual(status['fastClassifier']['id'], FAST)
            self.assertTrue(status['fastClassifier']['installed'])
            self.assertFalse(status['fastClassifier']['ready'])
            self.assertNotIn(FAST, [model['id'] for model in status['models']])

import importlib.util
from pathlib import Path
import plistlib
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('macos_launcher', Path(__file__).resolve().parents[4]/'scripts/macos-compute.py')
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


class MacosLauncherTest(unittest.TestCase):
    def test_pairing_defaults_to_enabled_without_saved_configuration(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(launcher.os.environ, {}, clear=True):
            value = launcher.pairing_environment(Path(directory) / 'compute.plist')
        self.assertEqual(value, {'COMPUTE_LAN_PAIRING_ENABLED': 'true'})

    def test_pairing_restart_preserves_saved_disabled_setting(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(launcher.os.environ, {}, clear=True):
            previous = Path(directory) / 'compute.plist'
            previous.write_bytes(plistlib.dumps({'EnvironmentVariables': {'COMPUTE_LAN_PAIRING_ENABLED': 'false'}}))
            self.assertEqual(launcher.pairing_environment(previous), {'COMPUTE_LAN_PAIRING_ENABLED': 'false'})

    def test_explicit_shell_pairing_setting_overrides_saved_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = Path(directory) / 'compute.plist'
            for saved, current in (('true', 'false'), ('false', 'TRUE')):
                with self.subTest(saved=saved, current=current), patch.dict(launcher.os.environ, {'COMPUTE_LAN_PAIRING_ENABLED': current}, clear=True):
                    previous.write_bytes(plistlib.dumps({'EnvironmentVariables': {'COMPUTE_LAN_PAIRING_ENABLED': saved}}))
                    self.assertEqual(launcher.pairing_environment(previous), {'COMPUTE_LAN_PAIRING_ENABLED': current.lower()})

    def test_pairing_defaults_for_older_plist_and_rejects_invalid_shell_or_saved_values(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = Path(directory) / 'compute.plist'
            previous.write_bytes(plistlib.dumps({'EnvironmentVariables': {'COMPUTE_AUDIO_THREADS': '2'}}))
            with patch.dict(launcher.os.environ, {}, clear=True):
                self.assertEqual(launcher.pairing_environment(previous), {'COMPUTE_LAN_PAIRING_ENABLED': 'true'})
            for value in ('', 'yes', '0', 'false '):
                with self.subTest(value=value), patch.dict(launcher.os.environ, {'COMPUTE_LAN_PAIRING_ENABLED': value}, clear=True), self.assertRaisesRegex(ValueError, 'must be true or false'):
                    launcher.pairing_environment(previous)
            previous.write_bytes(plistlib.dumps({'EnvironmentVariables': {'COMPUTE_LAN_PAIRING_ENABLED': 'invalid'}}))
            with patch.dict(launcher.os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, 'must be true or false'):
                launcher.pairing_environment(previous)

    def test_audio_configuration_uses_fixed_models_and_explicit_budget(self):
        value = launcher.audio_environment(Path('/models/htdemucs'), 2048)
        self.assertEqual(value['COMPUTE_AUDIO_MODEL_REPO'], '/models/htdemucs')
        self.assertEqual(value['COMPUTE_AUDIO_MEMORY_BYTES'], str(2*1024**3))
        self.assertEqual(value['PYTORCH_ENABLE_MPS_FALLBACK'], '0')
        for path, memory in ((Path('relative'), 2048), (Path('/models'), 511)):
            with self.assertRaises(ValueError):
                launcher.audio_environment(path, memory)

    def test_launchd_restart_retries_registration_removal_race(self):
        with patch.object(launcher.subprocess, 'run', side_effect=[SimpleNamespace(returncode=5), SimpleNamespace(returncode=0)]) as run, patch.object(launcher.time, 'sleep') as sleep:
            launcher.bootstrap('gui/501', Path('/runtime/node.plist'))
        self.assertEqual(run.call_count, 2)
        sleep.assert_called_once_with(.25)

    def test_other_launchd_errors_are_not_hidden(self):
        with patch.object(launcher.subprocess, 'run', return_value=SimpleNamespace(returncode=64)) as run, self.assertRaises(SystemExit):
            launcher.bootstrap('gui/501', Path('/runtime/node.plist'))
        self.assertEqual(run.call_count, 1)

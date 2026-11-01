import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('macos_launcher', Path(__file__).resolve().parents[4]/'scripts/macos-compute.py')
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


class MacosLauncherTest(unittest.TestCase):
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

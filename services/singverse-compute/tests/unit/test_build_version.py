import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from singverse_compute.core import build_info


class BuildVersionTest(unittest.TestCase):
    def test_native_version_follows_source_deployment_version_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'services/singverse-compute/singverse_compute/core/build_info.py'
            source.parent.mkdir(parents=True)
            source.touch()
            (root / 'VERSION').write_text('7.8.9-test\n')
            with patch.object(build_info, '__file__', str(source)):
                self.assertEqual(build_info.native_version(), '7.8.9-test')
                (root / 'VERSION').write_text('7.8.10\n')
                self.assertEqual(build_info.native_version(), '7.8.10')
                (root / 'VERSION').unlink()
                self.assertEqual(build_info.native_version(), 'development')

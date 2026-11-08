"""Regression for NVENC devices rejecting undersized qualification frames."""
import re
import unittest
from unittest.mock import patch

from singverse_compute.core.errors import ComputeError
from singverse_compute.infrastructure.transcoding.backends import Backends


class EncoderProbeTest(unittest.TestCase):
    @patch('singverse_compute.infrastructure.transcoding.backends.platform.system', return_value='Linux')
    def test_slow_nvenc_initialization_does_not_reject_working_hardware(self, _system):
        def slow_driver(command, *, timeout=15, **kwargs):
            if '-encoders' in command:
                return b'h264_nvenc libx264'
            if timeout < 4:
                raise ComputeError(422, 'Media execution cancelled or exceeded budget')
            return b''
        for manual in (True, False):
            with self.subTest(manual=manual), patch('singverse_compute.infrastructure.transcoding.backends.run', side_effect=slow_driver):
                backend = Backends('ffmpeg', preferred='nvenc')
                if manual:
                    self.assertTrue(backend.test('nvenc')['passed'])
                else:
                    self.assertEqual(backend.select('h264'), 'nvenc')

    def encoder(self, command, **kwargs):
        if '-encoders' in command:
            return b'h264_nvenc hevc_nvenc av1_nvenc'
        if '-c:v' not in command or not command[command.index('-c:v') + 1].endswith('_nvenc'):
            raise ComputeError(422, 'Other hardware unavailable')
        source = command[command.index('-i') + 1]
        width, height = map(int, re.search(r's=(\d+)x(\d+)', source).groups())
        if min(width, height) < 256:
            raise ComputeError(422, 'Frame Dimension less than the minimum supported value')
        if command[command.index('-c:v') + 1] == 'av1_nvenc':
            raise ComputeError(422, 'AV1 unavailable on this device')
        return b''

    @patch('singverse_compute.infrastructure.transcoding.backends.platform.system', return_value='Linux')
    def test_manual_and_inventory_accept_nvenc_without_claiming_av1(self, _system):
        with patch('singverse_compute.infrastructure.transcoding.backends.run', side_effect=self.encoder):
            backend = Backends('ffmpeg')
            self.assertTrue(backend.test('nvenc')['passed'])
            result = next(item for item in backend.inspect() if item['backend'] == 'nvenc')
            self.assertTrue(result['samplePassed'])
            self.assertTrue(result['codecs']['hevc']['samplePassed'])
            self.assertFalse(result['codecs']['av1']['samplePassed'])

    def test_encoder_failure_still_fails_qualification(self):
        with patch('singverse_compute.infrastructure.transcoding.backends.run',
                   side_effect=ComputeError(422, 'Driver unavailable')):
            self.assertFalse(Backends('ffmpeg').test('nvenc')['passed'])

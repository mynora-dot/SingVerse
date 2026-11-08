"""Board identity must also work when vendors use a product name as the model."""
from pathlib import Path
import unittest
from unittest.mock import patch

from singverse_compute.infrastructure.hardware import accelerators


class HardwareDetectionTest(unittest.TestCase):
    def tearDown(self):
        accelerators.cache_clear()

    def test_oec_compatible_identifies_rockchip_accelerators(self):
        self.assert_board('OneThingCloud OEC\0', 'onethingtech,onethingcloud-oec\0rockchip,rk3566\0',
                          {'audio': ['rknn'], 'video': ['rkmpp']})

    def test_compatible_works_when_model_is_unreadable(self):
        self.assert_board(None, 'rockchip,rk3568\0', {'audio': ['rknn'], 'video': ['rkmpp']})

    def test_unrelated_board_does_not_gain_accelerators(self):
        self.assert_board('Other ARM board\0', 'vendor,other-board\0', {'audio': [], 'video': []})

    def test_container_can_use_read_only_sysfs_device_tree(self):
        self.assert_board('OneThingCloud OEC\0', 'rockchip,rk3566\0',
                          {'audio': ['rknn'], 'video': ['rkmpp']}, readable_root='/sys/')

    def test_container_can_use_mount_outside_masked_firmware(self):
        self.assert_board('OneThingCloud OEC\0', 'rockchip,rk3566\0',
                          {'audio': ['rknn'], 'video': ['rkmpp']}, readable_root='/run/singverse-hardware/')

    def assert_board(self, model, compatible, expected, readable_root=None):
        def read(path, *args, **kwargs):
            if readable_root and not str(path).startswith(readable_root):
                raise FileNotFoundError(path)
            value = {'model': model, 'compatible': compatible}.get(path.name)
            if value is None:
                raise FileNotFoundError(path)
            return value

        accelerators.cache_clear()
        with patch('singverse_compute.infrastructure.hardware.platform.system', return_value='Linux'), \
                patch.object(Path, 'glob', return_value=[]), patch.object(Path, 'read_text', read):
            self.assertEqual(accelerators(), expected)

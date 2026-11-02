"""Callback origin and lifecycle regressions without external networking."""
import socket
import unittest
from unittest.mock import patch
from singverse_compute.core.errors import ComputeError
from singverse_compute.infrastructure.transcoding.input import validate_origin


class CallbackOriginTest(unittest.TestCase):
    def test_origin_only_and_malformed_addresses_are_rejected(self):
        for value in ('http://[invalid', 'file:///media/song.mp3', 'http://user:password@localhost',
                      'http://localhost/media', 'http://localhost?token=secret', 'http://localhost#media', 'http://localhost:0'):
            with self.subTest(value=value), self.assertRaises(ComputeError) as error:
                validate_origin(value)
            self.assertNotIn('password', error.exception.message)

    def test_private_http_and_verified_https_only(self):
        def answer(address):
            return [(socket.AF_INET, socket.SOCK_STREAM, 6, '', (address, 80))]
        for address, origin, allowed in (
            ('10.100.1.12', 'http://business:8080', True),
            ('127.0.0.1', 'http://business:8080', True),
            ('8.8.8.8', 'http://business:8080', False),
            ('8.8.8.8', 'https://business', True),
            ('169.254.169.254', 'https://business', False),
            ('0.0.0.0', 'http://business', False),
        ):
            with self.subTest(address=address, origin=origin), patch('socket.getaddrinfo', return_value=answer(address)):
                if allowed: self.assertEqual(validate_origin(origin), origin)
                else:
                    with self.assertRaises(ComputeError): validate_origin(origin)

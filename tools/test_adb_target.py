import unittest
from adb_target import active_devices, mdns_endpoints, matches


class TargetIdentityTest(unittest.TestCase):
    def test_same_model_is_not_enough(self):
        expected = dict(serial="A", device="m3q", fingerprint="build")
        self.assertFalse(
            matches(expected, dict(serial="B", device="m3q", fingerprint="build"))
        )
        self.assertTrue(matches(expected, dict(expected)))

    def test_missing_identity_is_rejected(self):
        self.assertFalse(matches({}, {}))

    def test_only_connect_service(self):
        text = "phone _adb-tls-pairing._tcp. 192.168.0.2:1234\nphone _adb-tls-connect._tcp. 192.168.0.2:4567"
        self.assertEqual(mdns_endpoints(text), ["192.168.0.2:4567"])

    def test_ignore_unauthorized_and_offline(self):
        self.assertEqual(
            active_devices(
                "List of devices attached\na offline\nb unauthorized\nc device\n"
            ),
            ["c"],
        )


if __name__ == "__main__":
    unittest.main()

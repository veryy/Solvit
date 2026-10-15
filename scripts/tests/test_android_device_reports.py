import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

spec = importlib.util.spec_from_file_location('reports', Path(__file__).resolve().parents[1] / 'verify-android-device-reports.py')
reports = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reports)

class DeviceReportGateTest(unittest.TestCase):
    def fixture(self, root):
        for module, count in reports.MINIMUM.items():
            path = root / module / 'build/outputs/androidTest-results/connected/debug/TEST-device.xml'
            path.parent.mkdir(parents=True)
            cases = ''.join(f'<testcase name="case-{i}"/>' for i in range(count))
            path.write_text(f'<testsuites tests="{count}" failures="0" errors="0" skipped="0"><testsuite>{cases}</testsuite></testsuites>')
        return root / 'app/build/outputs/androidTest-results/connected/debug/TEST-device.xml'
    def test_all_three_actual_report_shapes_are_required(self):
        with TemporaryDirectory() as directory:
            root = Path(directory); self.fixture(root)
            self.assertEqual(reports.MINIMUM, reports.verify(root))
            (root / 'web-android/build/outputs/androidTest-results/connected/debug/TEST-device.xml').unlink()
            with self.assertRaises(ValueError): reports.verify(root)
    def test_failure_counter_cannot_be_hidden_by_successful_later_command(self):
        with TemporaryDirectory() as directory:
            root = Path(directory); path = self.fixture(root)
            path.write_text(path.read_text().replace('failures="0"', 'failures="1"', 1))
            with self.assertRaises(ValueError): reports.verify(root)
    def test_nested_failure_is_rejected_even_with_false_zero_counter(self):
        with TemporaryDirectory() as directory:
            root = Path(directory); path = self.fixture(root)
            path.write_text(path.read_text().replace('<testcase name="case-0"/>', '<testcase name="case-0"><failure>failed</failure></testcase>'))
            with self.assertRaises(ValueError): reports.verify(root)
    def test_empty_skipped_and_inconsistent_reports_are_rejected(self):
        for xml in ['<testsuites tests="0"/>', '<testsuites tests="1" skipped="1"><testcase><skipped/></testcase></testsuites>', '<testsuites tests="68"><testcase/></testsuites>']:
            with TemporaryDirectory() as directory:
                root = Path(directory); path = self.fixture(root); path.write_text(xml)
                with self.assertRaises(ValueError): reports.verify(root)

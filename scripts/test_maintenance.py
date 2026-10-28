# @file test_maintenance.py
# @brief Preserve Android identity and prevent unresolved or stale upstream candidates from being promoted.
import argparse
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import maintain_upstream as maintenance


class MaintenanceTest(unittest.TestCase):
    def fixture(self, root):
        data = maintenance.release()
        (root/'base/app').mkdir(parents=True)
        (root/'release.json').write_text(json.dumps(data))
        (root/'base/app/build.gradle.kts').write_text('applicationId = "'+data['applicationId']+'"\nMYSPOTI_KEYSTORE\nMYSPOTI_KEY_ALIAS\nsigningConfig = signingConfigs.getByName')

    def test_same_package_and_signing_contract_is_accepted(self):
        with tempfile.TemporaryDirectory() as area:
            root = Path(area); self.fixture(root); maintenance.identity(root)

    def test_changed_package_is_rejected(self):
        with tempfile.TemporaryDirectory() as area:
            root = Path(area); self.fixture(root)
            path = root/'base/app/build.gradle.kts'; path.write_text(path.read_text().replace('app.myspoti.hangul', 'app.other'))
            with self.assertRaises(ValueError): maintenance.identity(root)

    def test_changed_update_repository_is_rejected(self):
        with tempfile.TemporaryDirectory() as area:
            root = Path(area); self.fixture(root)
            data = maintenance.release(root); data['updateRepository'] = 'other/repo'
            (root/'release.json').write_text(json.dumps(data))
            with self.assertRaises(ValueError): maintenance.identity(root)

    def test_removed_signing_contract_is_rejected(self):
        with tempfile.TemporaryDirectory() as area:
            root = Path(area); self.fixture(root)
            path = root/'base/app/build.gradle.kts'; path.write_text(path.read_text().replace('MYSPOTI_KEYSTORE', ''))
            with self.assertRaises(ValueError): maintenance.identity(root)

    def test_source_changes_invalidate_candidate_hash(self):
        with tempfile.TemporaryDirectory() as area:
            root = Path(area); path = root/'source.kt'; path.write_text('before')
            state = {'directory': str(root), 'files': ['source.kt']}
            before = maintenance.candidate_hash(state); path.write_text('after')
            self.assertNotEqual(before, maintenance.candidate_hash(state))

    def test_stable_behind_base_is_distinguished_from_new_beta(self):
        def get(path):
            if path.endswith('/releases/latest'): return {'tag_name': 'v1.0.0', 'html_url': 'stable'}
            if '/releases?' in path: return [{'tag_name': 'v2.0.0-beta.1', 'html_url': 'beta', 'draft': False, 'prerelease': True}]
            return {'status': 'behind' if path.endswith('v1.0.0') else 'ahead', 'ahead_by': 0 if path.endswith('v1.0.0') else 2}
        result = maintenance.detect(get)
        self.assertEqual(['behind', 'ahead'], [r['comparison'] for r in result['releases']])

    def test_conflict_markers_block_build(self):
        with tempfile.TemporaryDirectory() as area:
            root = Path(area); self.fixture(root)
            (root/'source.kt').write_text('<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> upstream\n')
            state = root/'state.json'; state.write_text(json.dumps({'directory': str(root), 'files': ['source.kt']}))
            with patch.object(maintenance, 'STATE', state), patch.object(maintenance.subprocess, 'run') as run:
                with self.assertRaises(ValueError): maintenance.verify(argparse.Namespace(java_home='jdk', android_sdk='sdk'))
                run.assert_not_called()


if __name__ == '__main__': unittest.main()

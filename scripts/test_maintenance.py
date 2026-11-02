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

    def test_change_report_lists_notes_conflicts_commits_and_asks_for_permission(self):
        def get(path):
            if '/compare/' in path:
                return {'ahead_by': 2, 'commits': [{'commit': {'message': 'old'}}, {'commit': {'message': 'new\n\nbody'}}],
                        'files': [{'filename': 'base/app/x.kt'}]}
            return {'body': 'Thanks @someone ```'}
        row = {'channel': 'beta', 'tag': 'v2.0.2-beta.8', 'url': 'https://github.com/kota-zo/myspoti/releases/tag/v2.0.2-beta.8'}
        prefix, title, text = maintenance.report(row, get, lambda tag: ('rev', 'head', 'tree', ['README.md', 'Settings.kt']))
        self.assertEqual(('원본 업데이트: Myspoti 베타 ', '원본 업데이트: Myspoti 베타 v2.0.2-beta.8'), (prefix, title))
        self.assertIn('2개 파일', text)
        self.assertIn('```text\nREADME.md\nSettings.kt\n```', text)
        self.assertIn('```text\nnew\nold\n```', text)
        # Upstream text with backticks stays inside a longer fence, so its @name cannot notify anyone.
        self.assertIn('````text\nThanks @someone ```\n````', text)
        self.assertIn('허가하면', text)

    def test_publish_creates_moves_or_skips(self):
        def publish(issues, title='원본 업데이트: Myspoti 정식 v2.3.0'):
            calls = []
            def run(*args):
                calls.append(args[:2])
                return json.dumps(issues) if args[:2] == ('issue', 'list') else ''
            return maintenance.publish('원본 업데이트: Myspoti 정식 ', title, 'body', run), calls
        self.assertEqual(('created', [('issue', 'list'), ('issue', 'create')]), publish([]))
        self.assertEqual(('updated', [('issue', 'list'), ('issue', 'edit'), ('issue', 'comment')]),
                         publish([{'number': 4, 'title': '원본 업데이트: Myspoti 정식 v2.2.0', 'state': 'OPEN'}]))
        self.assertEqual('known', publish([{'number': 5, 'title': '원본 업데이트: Myspoti 정식 v2.3.0', 'state': 'CLOSED'}])[0])
        # The beta channel's open issue is not taken over by a stable release.
        self.assertEqual('created', publish([{'number': 6, 'title': '원본 업데이트: Myspoti 베타 v2.0.2-beta.8', 'state': 'OPEN'}])[0])


if __name__ == '__main__': unittest.main()

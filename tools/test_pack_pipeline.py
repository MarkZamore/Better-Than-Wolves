"""Checks that preparation cannot silently accept drift or private inputs."""
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import pack_pipeline as pipeline

class SourceBoundaryTests(unittest.TestCase):
    def test_lock_json_uses_exact_utf8_lf_bytes_on_every_host(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/'lock.json'
            pipeline.write_json(path,{'name':'Тест'})
            self.assertEqual('{\n  "name": "Тест"\n}\n'.encode('utf8'),path.read_bytes())

    def test_exact_source_cache_reuses_bytes_without_network_or_changing_cache(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root);cache=root/'cache';cache.mkdir();raw=b'official pinned input';sha=hashlib.sha256(raw).hexdigest()
            pin={'sizeBytes':len(raw),'sha256':sha};cached=cache/sha;cached.write_bytes(raw)
            with patch.object(pipeline,'download') as download:
                pipeline.restore_input(pin,root/'restored/input.jar',cache);download.assert_not_called()
            self.assertEqual(raw,(root/'restored/input.jar').read_bytes());self.assertEqual(raw,cached.read_bytes())

    def test_corrupted_cache_is_not_used_as_an_author_input(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root);cache=root/'cache';cache.mkdir();raw=b'official';sha=hashlib.sha256(raw).hexdigest();(cache/sha).write_bytes(b'wrong')
            with patch.object(pipeline,'download',side_effect=ValueError('offline')):
                with self.assertRaisesRegex(ValueError,'offline'):pipeline.restore_input({'sizeBytes':len(raw),'sha256':sha},root/'input.jar',cache)
            self.assertFalse((root/'input.jar').exists())

    def test_unsafe_paths_cannot_enter_distribution(self):
        for path in ('../outside', '/absolute', 'C:/private', 'mods/../options.txt', 'mods/CON.jar', 'mods/ends. '):
            with self.subTest(path=path), self.assertRaises(ValueError): pipeline.relative(path)

    def test_duplicate_case_insensitive_paths_are_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            p = Path(root) / 'file.jar'; p.write_bytes(b'author')
            row = {'path': p.name, **pipeline.fingerprint(p)}
            with self.assertRaisesRegex(ValueError, 'Duplicate'):
                pipeline.check_rows(root, [row, {**row, 'path': 'FILE.JAR'}])

    def test_changed_author_bytes_fail_before_replacement(self):
        with tempfile.TemporaryDirectory() as root:
            p = Path(root) / 'mods/author.jar'; p.parent.mkdir(); p.write_bytes(b'changed')
            row = {'path': 'mods/author.jar', 'sizeBytes': 6, 'sha256': hashlib.sha256(b'author').hexdigest()}
            with patch.object(pipeline, 'read_json', return_value={'files': [row]}), patch.object(pipeline, 'download') as download:
                with self.assertRaisesRegex(ValueError, 'Refusing changed'): pipeline.prepare(root)
                download.assert_not_called()
            self.assertEqual(b'changed', p.read_bytes())

    def test_inventory_excludes_personal_worlds_and_options(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root); (root/'mods').mkdir(); (root/'mods/a.jar').write_bytes(b'author')
            (root/'saves').mkdir(); (root/'saves/world.dat').write_bytes(b'private'); (root/'options.txt').write_bytes(b'private')
            self.assertEqual(['mods/a.jar'], [row['path'] for row in pipeline.inventory(root)])

    def test_restoration_never_refreshes_pins(self):
        with self.assertRaisesRegex(ValueError, 'never refreshes'): pipeline.prepare('unused', refresh_pins=True)

    def test_official_download_coordinates_are_required(self):
        row = {'sourceUrl': 'https://cdn.modrinth.com.attacker.invalid/mod.jar'}
        with self.assertRaisesRegex(ValueError, 'Nonofficial'): pipeline.download(row, 'unused')

if __name__ == '__main__': unittest.main()

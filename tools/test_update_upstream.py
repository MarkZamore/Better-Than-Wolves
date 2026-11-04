"""Synthetic preparation fixtures; these tests do not claim Minecraft runtime QA."""
import io, json
from pathlib import Path
import tempfile, unittest, zipfile
from pack_pipeline import fingerprint, read_json, write_json, freeze, verify
from update_upstream import prepare_update, finalize_reviewed_overlay, verify_receipt, write_receipt

def mod(path, mod_id, version):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, 'w') as jar:
        jar.writestr('fabric.mod.json', json.dumps({'schemaVersion': 1, 'id': mod_id, 'version': version, 'name': mod_id}))

class MandatoryOverlayUpdateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = Path(self.temp.name); self.source = self.root/'source'; self.source.mkdir()
        s = self.source; (s/'tools/legacy-bridge').mkdir(parents=True); (s/'tools/legacy-bridge/build_bridge.py').write_text('# synthetic source recipe\n')
        (s/'library').mkdir(); (s/'launcher').mkdir()
        write_json(s/'portable-pack.json', {'schemaVersion': 1, 'minecraftVersion': '1.6.4', 'loader': {'type':'legacyfabric','version':'0.18.2'}, 'clientJar':'minecraft-1.6.4-client.jar','javaMajorVersion':17})
        (s/'launcher/pack-owned.txt').write_text('# explicit ownership stays unchanged\n')
        mod(s/'mods/btwce-3.1.1.jar','btw','3.1.1'); mod(s/'mods/Nightmare-Mode-1.1.6.jar','nightmare','1.1.6'); mod(s/'mods/btw-lan-bridge-1.0.0.jar','btw_lan_bridge','1.0.0')
        (s/'minecraft-1.6.4-client.jar').write_bytes(b'synthetic-client')
        (s/'resources').mkdir(); (s/'resources/author-note.txt').write_bytes(b'original author content')
        names = ['mods/btwce-3.1.1.jar','mods/Nightmare-Mode-1.1.6.jar','minecraft-1.6.4-client.jar','resources/author-note.txt']
        upstream = {'schemaVersion':1,'packId':'better-than-wolves','files':[{'path': rel, **fingerprint(s/rel), 'sourceUrl':'https://cdn.modrinth.com/data/fixture/'+Path(rel).name} for rel in names]}
        write_json(s/'tools/upstream-lock.json', upstream)
        write_json(s/'library/content.json',{'id':'better-than-wolves','relativePath':'Better Than Wolves','name':'Better Than Wolves','summary':'fixture','description':'fixture','libraryIds':['btw_lan_bridge']})
        write_json(s/'library/mod-pages.json',{mid:'https://example.org/project/'+mid for mid in ('btw','nightmare','btw_lan_bridge')}); (s/'library/README.md').write_text('Synthetic fixture provenance'); (s/'library/preview.jpg').write_bytes(b'synthetic-preview')
        write_json(s/'tools/catalog-entry.json', {'id':'better-than-wolves','relativePath':'Better Than Wolves','source':{'owner':'MarkZamore','repo':'Better-Than-Wolves','tag':'pack-latest'}})
        overlay = {'schemaVersion':1,'packId':'better-than-wolves','reviewedBaseFiles':[{k:row[k] for k in ('path','sizeBytes','sha256')} for row in upstream['files'] if row['path'].startswith('mods/')],
            'sourceFiles':[{'path':rel,**fingerprint(s/rel)} for rel in ('portable-pack.json','launcher/pack-owned.txt')],
            'bridge':{'path':'mods/btw-lan-bridge-1.0.0.jar','modId':'btw_lan_bridge','builder':'tools/legacy-bridge/build_bridge.py','status':'pending-final-runtime-review'}}
        write_json(s/'tools/overlay.json',overlay); finalize_reviewed_overlay(s); freeze(s)
        self.original = {path.relative_to(s).as_posix():path.read_bytes() for path in s.rglob('*') if path.is_file()}

    def tearDown(self): self.temp.cleanup()

    def test_unrelated_author_change_preserves_bridge_descriptor_and_active_source(self):
        incoming = self.root/'incoming'; incoming.mkdir(); (incoming/'resources').mkdir(); (incoming/'resources/author-note.txt').write_bytes(b'reviewed new unrelated author content')
        upstream = read_json(self.source/'tools/upstream-lock.json')
        upstream['files'][-1].update(fingerprint(incoming/'resources/author-note.txt'))
        lock = self.root/'new-lock.json'; write_json(lock,upstream)
        # Unchanged author downloads are served from the exact source by the test input folder.
        import shutil
        for row in upstream['files'][:-1]:
            target=incoming/row['path']; target.parent.mkdir(parents=True,exist_ok=True); shutil.copyfile(self.source/row['path'],target)
        out = self.root/'artifacts/candidate'; result = prepare_update(self.source,lock,out,incoming)
        self.assertTrue(result['actualRuntimeNotPerformed']); self.assertEqual(3,result['mandatoryOverlayOperations'])
        for rel in ('mods/btw-lan-bridge-1.0.0.jar','portable-pack.json','launcher/pack-owned.txt','mods/btwce-3.1.1.jar','mods/Nightmare-Mode-1.1.6.jar'):
            self.assertEqual(self.original[rel],(out/rel).read_bytes())
        self.assertEqual(b'reviewed new unrelated author content',(out/'resources/author-note.txt').read_bytes())
        self.assertEqual(self.original,{p.relative_to(self.source).as_posix():p.read_bytes() for p in self.source.rglob('*') if p.is_file()})
        verify_receipt(out)

    def test_changed_guarded_author_jar_fails_before_allocating_candidate(self):
        upstream = read_json(self.source/'tools/upstream-lock.json'); upstream['files'][0]['sha256']='1'*64
        lock=self.root/'changed-lock.json';write_json(lock,upstream);out=self.root/'artifacts/rejected'
        with self.assertRaisesRegex(ValueError,'Guarded author/patch-site'): prepare_update(self.source,lock,out)
        self.assertFalse(out.exists())

    def test_missing_bridge_cannot_silently_remove_mandatory_layer(self):
        (self.source/'mods/btw-lan-bridge-1.0.0.jar').unlink();out=self.root/'artifacts/rejected'
        with self.assertRaisesRegex(ValueError,'bridge output'): prepare_update(self.source,self.source/'tools/upstream-lock.json',out)
        self.assertFalse(out.exists())

    def test_upstream_cannot_replace_pack_owned_descriptor(self):
        upstream=read_json(self.source/'tools/upstream-lock.json');upstream['files'].append({'path':'portable-pack.json',**fingerprint(self.source/'portable-pack.json'),'sourceUrl':'https://cdn.modrinth.com/data/fixture/descriptor.json'})
        lock=self.root/'conflict.json';write_json(lock,upstream)
        with self.assertRaisesRegex(ValueError,'replace the mandatory overlay'): prepare_update(self.source,lock,self.root/'artifacts/rejected')

    def test_recipe_or_receipt_drift_is_rejected(self):
        (self.source/'tools/legacy-bridge/build_bridge.py').write_text('# unreviewed source drift\n')
        with self.assertRaisesRegex(ValueError,'recipe differs'): verify_receipt(self.source)

    def test_changed_current_author_cannot_be_adopted_by_refreshing_only_upstream_and_receipt(self):
        rel='mods/Nightmare-Mode-1.1.6.jar'; path=self.source/rel
        path.write_bytes(path.read_bytes()+b'unreviewed trailing bytes')
        upstream=read_json(self.source/'tools/upstream-lock.json')
        next(row for row in upstream['files'] if row['path']==rel).update(fingerprint(path))
        write_json(self.source/'tools/upstream-lock.json',upstream)
        with self.assertRaisesRegex(ValueError,'Current guarded author/patch-site'):write_receipt(self.source)
        with self.assertRaisesRegex(ValueError,'Current guarded author/patch-site'):freeze(self.source)

    def test_removing_bridge_file_and_lock_row_does_not_disable_ordinary_verification(self):
        rel='mods/btw-lan-bridge-1.0.0.jar';(self.source/rel).unlink()
        lock=read_json(self.source/'tools/distribution-lock.json');lock['files']=[row for row in lock['files'] if row['path']!=rel]
        write_json(self.source/'tools/distribution-lock.json',lock)
        with self.assertRaisesRegex(ValueError,'bridge output'):verify(self.source)

    def test_missing_overlay_definition_or_receipt_cannot_disable_mandatory_layer(self):
        for rel in ('tools/overlay.json','tools/overlay-receipt.json'):
            path=self.source/rel; original=path.read_bytes();path.unlink()
            with self.subTest(rel=rel),self.assertRaisesRegex(ValueError,'Mandatory overlay definition/receipt missing'):verify(self.source)
            with self.subTest(rel=rel),self.assertRaisesRegex(ValueError,'Mandatory overlay definition/receipt missing'):freeze(self.source)
            path.write_bytes(original)

if __name__ == '__main__': unittest.main()

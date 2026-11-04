import hashlib,json
from pathlib import Path
import tempfile,unittest,zipfile
from pack_pipeline import write_json
from collect_public_proof import collect
from verify_published import VerificationError

class SmallPublicProofTests(unittest.TestCase):
    def fixture(self,root,extra_member=False):
        launcher=root/'launcher';pack=root/'pack';launcher.mkdir();(pack/'assets').mkdir(parents=True);revision='a'*40
        def pin(name,raw):return {'name':name,'sizeBytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),'serverDigestVerified':True}
        executable=pin('LANMinecraft.exe',b'exe')
        write_json(launcher/'update.json',{'schemaVersion':2,'releaseNumber':445,'version':'445','commitSha':'b'*40,'assetName':'LANMinecraft.exe','sizeBytes':executable['sizeBytes'],'sha256':executable['sha256'],'deltaPatches':[]})
        launcher_pins=[executable,pin('update.json',(launcher/'update.json').read_bytes())]
        write_json(launcher/'verification-report.json',{'status':'PASS_PUBLIC_DELIVERY_ONLY','repository':'MarkZamore/LANMinecraft','tag':'latest','allPublishedExeAndDeltasFullyDownloaded':True,'allSnapshotAssetIdsAndReleaseUnchanged':True,'releaseNumber':445,'commitSha':'b'*40,'downloadedAssets':launcher_pins,'downloadedBytes':sum(row['sizeBytes'] for row in launcher_pins),'runtimeAcceptanceClaimed':False,'antivirusVerdictClaimed':False})
        archive=pack/'assets/library.zip'
        metadata={'schemaVersion':1,'id':'better-than-wolves','name':'Better Than Wolves','relativePath':'Better Than Wolves','revision':revision,'mods':[{'id':'fixture','name':'Fixture','version':'1','isLoadedMod':True}]}
        with zipfile.ZipFile(archive,'w') as z:
            z.writestr('library.json',json.dumps(metadata));z.writestr('preview.jpg',b'public-preview')
            if extra_member:z.writestr('../private.txt',b'secret')
        payload=pin('payload.jar',b'mod');(pack/'assets/payload.jar').write_bytes(b'mod');library=pin('library.zip',archive.read_bytes())
        write_json(pack/'pack-manifest.json',{'schemaVersion':2,'packId':'better-than-wolves','name':'Better Than Wolves','relativePath':'Better Than Wolves','revision':revision,'modCount':1,
            'files':[{'path':'mods/base.jar','sizeBytes':payload['sizeBytes'],'sha256':payload['sha256']}],
            'assets':[{'name':payload['name'],'kind':'jar','sizeBytes':payload['sizeBytes'],'sha256':payload['sha256'],'covers':['mods/base.jar']}],
            'library':{'name':library['name'],'sizeBytes':library['sizeBytes'],'sha256':library['sha256'],'revision':revision}})
        manifest=pin('pack-manifest.json',(pack/'pack-manifest.json').read_bytes());pack_pins=[payload,library,manifest]
        write_json(pack/'verification-report.json',{'status':'PASS','repository':'MarkZamore/Better-Than-Wolves','tag':'pack-latest','name':'Better Than Wolves','revision':revision,'allUploadedAssetsVerified':3,'activeFiles':1,'payloadAssets':1,'modCount':1,'libraryMetadataRows':1,'downloadedBytes':sum(row['sizeBytes'] for row in pack_pins),'manifestSha256':manifest['sha256'],'downloadedAssetPins':pack_pins})
        write_json(pack/'public-release.json',{'repository':'MarkZamore/Better-Than-Wolves','tag':'pack-latest','revision':revision,'assets':[{'name':row['name'],'id':index+1,'size':row['sizeBytes'],'state':'uploaded','digest':'sha256:'+row['sha256']} for index,row in enumerate(pack_pins)]})
        return launcher,pack,revision

    def test_bundle_contains_only_public_small_metadata_and_preview(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);launcher,pack,revision=self.fixture(root);out=root/'artifacts/proof';report=collect(launcher,pack,out,revision)
            self.assertEqual({'launcher-report.json','launcher-update.json','pack-report.json','pack-manifest.json','pack-public-release.json','library.json','preview.jpg','summary.json'},{p.name for p in out.iterdir()})
            self.assertFalse(report['runtimeAcceptanceClaimed']);self.assertFalse(report['antivirusVerdictClaimed'])
            self.assertLess(sum(p.stat().st_size for p in out.iterdir()),16384)

    def test_unexpected_library_member_is_never_extracted_or_uploaded(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);launcher,pack,revision=self.fixture(root,True);out=root/'artifacts/proof'
            with self.assertRaisesRegex(VerificationError,'Only the verified'):collect(launcher,pack,out,revision)
            self.assertFalse(out.exists());self.assertFalse((root/'private.txt').exists())

    def test_incomplete_launcher_pointer_cannot_be_turned_into_pass_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);launcher,pack,revision=self.fixture(root);write_json(launcher/'update.json',{'releaseNumber':445})
            with self.assertRaises(VerificationError):collect(launcher,pack,root/'artifacts/proof',revision)

    def test_copied_library_count_is_bound_to_manifest_and_download_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);launcher,pack,revision=self.fixture(root);report=json.loads((pack/'verification-report.json').read_text());report['modCount']=99;write_json(pack/'verification-report.json',report)
            with self.assertRaisesRegex(VerificationError,'library identity/counter'):collect(launcher,pack,root/'artifacts/proof',revision)

if __name__=='__main__':unittest.main()

import hashlib, io, json
from pathlib import Path
import tempfile, unittest
from unittest.mock import patch
import verify_launcher_delivery as delivery

class Response(io.BytesIO):
    status=200
    def __init__(self,raw,length=None):
        super().__init__(raw);self.headers={'Content-Length':str(len(raw) if length is None else length)}

class PublicLauncherDeliveryTests(unittest.TestCase):
    def manifest(self,number=445):
        return {'schemaVersion':2,'releaseNumber':number,'version':str(number),'commitSha':'a'*40,'assetName':'LANMinecraft.exe','sizeBytes':3,'sha256':hashlib.sha256(b'exe').hexdigest(),
            'deltaPatches':[{'algorithm':'bsdiff','algorithmVersion':1,'baseReleaseNumber':444,'baseCommitSha':'b'*40,'baseSha256':'c'*64,'assetName':'LANMinecraft.bsdiff','sizeBytes':5,'sha256':hashlib.sha256(b'patch').hexdigest()}]}

    def asset(self,name,raw,asset_id):
        return {'name':name,'id':asset_id,'size':len(raw),'state':'uploaded','digest':'sha256:'+hashlib.sha256(raw).hexdigest(),'browser_download_url':'https://github.com/MarkZamore/LANMinecraft/releases/download/latest/'+name}

    def fixture(self):
        manifest=self.manifest();pointer=json.dumps(manifest).encode();blobs={'update.json':pointer,'LANMinecraft.exe':b'exe','LANMinecraft.bsdiff':b'patch'}
        assets=[self.asset(name,raw,index+1) for index,(name,raw) in enumerate(blobs.items())]
        release={'id':10,'tag_name':'latest','draft':False,'prerelease':False,'target_commitish':manifest['commitSha']}
        return blobs,assets,release

    def test_current_and_future_compatible_release_numbers_are_accepted(self):
        for number in (445,446,1000):
            with self.subTest(number=number):self.assertEqual({'LANMinecraft.exe','LANMinecraft.bsdiff'},set(delivery.validate_manifest(self.manifest(number))))

    def test_stale_release_and_lowered_gate_are_rejected(self):
        with self.assertRaisesRegex(delivery.VerificationError,'below'):delivery.validate_manifest(self.manifest(444))
        with self.assertRaisesRegex(delivery.VerificationError,'cannot be lowered'):delivery.validate_manifest(self.manifest(),444)

    def test_unsafe_or_inconsistent_delta_names_are_rejected(self):
        for name in ('../private','LANMinecraft.from-443.bsdiff','other.exe'):
            manifest=self.manifest();manifest['deltaPatches'][0]['assetName']=name
            with self.subTest(name=name),self.assertRaises(delivery.VerificationError):delivery.validate_manifest(manifest)

    def test_bad_hashes_unbounded_sizes_and_unknown_algorithms_are_rejected(self):
        for key,value in (('schemaVersion',2.0),('sha256','not-a-hash'),('sizeBytes',True),('sizeBytes',delivery.MAX_ASSET_BYTES+1)):
            manifest=self.manifest();manifest[key]=value
            with self.subTest(key=key),self.assertRaises(delivery.VerificationError):delivery.validate_manifest(manifest)
        manifest=self.manifest();manifest['deltaPatches'][0]['algorithm']='unknown'
        with self.assertRaisesRegex(delivery.VerificationError,'algorithm'):delivery.validate_manifest(manifest)

    def test_stream_requires_all_declared_bytes_and_the_expected_digest(self):
        asset=self.asset('LANMinecraft.exe',b'exe',1)
        with patch.object(delivery,'open_public',return_value=Response(b'ex',3)):
            with self.assertRaisesRegex(delivery.VerificationError,'ended before'):delivery.streamed_download(asset,{'sizeBytes':3,'sha256':hashlib.sha256(b'exe').hexdigest()})
        with patch.object(delivery,'open_public',return_value=Response(b'bad')):
            with self.assertRaisesRegex(delivery.VerificationError,'SHA256'):delivery.streamed_download(asset,{'sizeBytes':3,'sha256':hashlib.sha256(b'exe').hexdigest()})

    def test_delta_target_commit_and_duplicate_base_hash_match_launcher_rejection_rules(self):
        manifest=self.manifest();manifest['deltaPatches'][0]['baseCommitSha']=manifest['commitSha']
        with self.assertRaisesRegex(delivery.VerificationError,'target/base'):delivery.validate_manifest(manifest)
        manifest=self.manifest();second=dict(manifest['deltaPatches'][0]);second.update(assetName='LANMinecraft.from-443.bsdiff',baseReleaseNumber=443,baseCommitSha='d'*40);manifest['deltaPatches'].append(second)
        with self.assertRaisesRegex(delivery.VerificationError,'duplicate base hash'):delivery.validate_manifest(manifest)

    def test_full_stream_verification_retains_no_executable_body(self):
        asset=self.asset('LANMinecraft.exe',b'exe',1)
        with patch.object(delivery,'open_public',return_value=Response(b'exe')):
            result,raw=delivery.streamed_download(asset,{'sizeBytes':3,'sha256':hashlib.sha256(b'exe').hexdigest()})
        self.assertIsNone(raw);self.assertTrue(result['serverDigestVerified']);self.assertEqual(3,result['sizeBytes'])

    def test_every_public_payload_and_pointer_is_redownloaded_and_reported(self):
        blobs,assets,release=self.fixture()
        with tempfile.TemporaryDirectory() as tmp:
            out=Path(tmp)/'artifacts/proof'
            with patch.object(delivery,'public_json',side_effect=[release,release]),patch.object(delivery,'public_asset_rows',side_effect=[assets,assets]),patch.object(delivery,'open_public',side_effect=lambda url:Response(blobs[url.rsplit('/',1)[1]])):
                report=delivery.verify_delivery(out)
            self.assertEqual(3,len(report['downloadedAssets']));self.assertEqual(sum(map(len,blobs.values())),report['downloadedBytes'])
            self.assertEqual({'update.json','verification-report.json'},{p.name for p in out.iterdir()});self.assertFalse(report['runtimeAcceptanceClaimed']);self.assertFalse(report['antivirusVerdictClaimed'])

    def test_asset_replacement_during_download_cannot_produce_pass_proof(self):
        blobs,assets,release=self.fixture();changed=[dict(row) for row in assets];changed[0]['id']=99
        with tempfile.TemporaryDirectory() as tmp:
            out=Path(tmp)/'artifacts/proof'
            with patch.object(delivery,'public_json',side_effect=[release,release]),patch.object(delivery,'public_asset_rows',side_effect=[assets,changed]),patch.object(delivery,'open_public',side_effect=lambda url:Response(blobs[url.rsplit('/',1)[1]])):
                with self.assertRaisesRegex(delivery.VerificationError,'replaced'):delivery.verify_delivery(out)
            self.assertFalse(out.exists())

if __name__=='__main__':unittest.main()

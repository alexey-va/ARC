"""Local publication contract: version set, retry, and notification ordering."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile


SCRIPTS = Path(__file__).resolve().parents[2] / "main/resources/scripts"


class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.scripts = self.root / "scripts"
        shutil.copytree(SCRIPTS, self.scripts)
        self.catalog = self.scripts / "resourcepack_clients.json"
        self.catalog.write_text(json.dumps({"clients": [
            {"version": "26.3", "protocol": 777, "format": [97, 1], "textures": []}
        ]}))
        source = self.root / "source.zip"
        with zipfile.ZipFile(source, "w") as z:
            z.writestr("pack.mcmeta", json.dumps({"pack": {
                "pack_format": 75, "min_format": 75, "max_format": 9999
            }}))
            z.writestr("assets/example/atlases/blocks.json", json.dumps({"sources": [
                {"type": "directory", "source": "items", "prefix": "items/"}
            ]}))
            z.writestr("assets/example/textures/items/coin.png", b"fixture bytes")
        self.aws = self.root / "aws"
        self.aws.write_text('''#!/usr/bin/env python3
import os, pathlib, shutil, sys
root = pathlib.Path(os.environ['FAKE_ROOT'])
if sys.argv[1:3] == ['s3api', 'head-object']:
    path = root / 'bucket' / sys.argv[sys.argv.index('--key') + 1]
    if not path.exists(): sys.exit(1)
    print(str(path.stat().st_size) + '\\t' + path.with_suffix('.metadata').read_text())
    sys.exit(0)
source, target = sys.argv[3:5]
if target == '-':
    path = root / 'bucket' / source.rsplit('/', 1)[-1]
    if not path.exists(): sys.exit(1)
    sys.stdout.buffer.write(path.read_bytes()); sys.exit(0)
name = target.rsplit('/', 1)[-1]
with (root / 'actions').open('a') as f: f.write('upload:' + name + '\\n')
if name == os.environ.get('FAIL_OBJECT'): sys.exit(2)
dest = root / 'bucket' / name
dest.parent.mkdir(exist_ok=True)
if source == '-': dest.write_bytes(sys.stdin.buffer.read())
else: shutil.copyfile(source, dest)
if '--metadata' in sys.argv:
    dest.with_suffix('.metadata').write_text(sys.argv[sys.argv.index('--metadata') + 1].split('=', 1)[1])
''')
        self.redis = self.root / "redis"
        self.redis.write_text('''#!/usr/bin/env python3
import os, pathlib, sys
root = pathlib.Path(os.environ['FAKE_ROOT'])
if 'PUBLISH' in sys.argv:
    value = sys.argv[sys.argv.index('PUBLISH') + 2].split('v1:', 1)[1]
    sha, request = value.split(':')
    (root / 'ack').write_text('v1:' + request + ':' + sha)
    with (root / 'actions').open('a') as f: f.write('notify\\n')
    print(1)
elif 'HGET' in sys.argv: print((root / 'ack').read_text())
else: sys.exit(2)
''')
        self.aws.chmod(0o700)
        self.redis.chmod(0o700)
        self.env = dict(os.environ, AWS_ACCESS_KEY_ID="test", AWS_SECRET_ACCESS_KEY="test",
                        RP_SOURCE=str(source), AWS_CLI=str(self.aws), FAKE_ROOT=str(self.root),
                        RP_VARIANTS_ENABLED="1", IA_MIRROR_ENABLED="0", RP_NOTIFY_ENABLED="1",
                        REDIS_CLI=str(self.redis), REDIS_HOST="unused", REDIS_PORT="6379",
                        REDIS_SERVER_NAME="spawn", REDIS_WIRE_DELIMITER="<>#<>#<>",
                        RP_PUBLISHED_CHANNEL="arc.resourcepack.published", RP_PUBLISHED_ACK_KEY="test")

    def publish(self, ok=True):
        result = subprocess.run(["bash", str(self.scripts / "resourcepack_sync.sh")],
                                env=self.env, text=True, capture_output=True, timeout=60)
        self.assertEqual(result.returncode == 0, ok, result.stdout + result.stderr)

    def test_complete_set_then_notify_then_manifest_and_retry_variant_only_change(self):
        self.publish()
        actions = (self.root / "actions").read_text().splitlines()
        self.assertEqual(actions, ["upload:RusCraftingResource.zip", "upload:RusCraftingResource-26.3.zip",
                                   "notify", "upload:RusCraftingResource.zip.sha256"])
        manifest = self.root / "bucket/RusCraftingResource.zip.sha256"
        original_manifest = manifest.read_text()
        self.assertEqual(len(original_manifest.splitlines()), 2)
        self.publish()
        self.assertEqual((self.root / "actions").read_text().splitlines(), actions)
        catalog = json.loads(self.catalog.read_text())
        catalog["clients"][0]["textures"].append("minecraft:items/new_coin")
        self.catalog.write_text(json.dumps(catalog))
        self.publish()
        changed_manifest = manifest.read_text()
        self.assertEqual(original_manifest.splitlines()[0], changed_manifest.splitlines()[0])
        self.assertNotEqual(original_manifest.splitlines()[1], changed_manifest.splitlines()[1])
        self.assertEqual((self.root / "actions").read_text().splitlines(), actions * 2)

    def test_failed_variant_upload_never_notifies_or_commits_manifest(self):
        self.env["FAIL_OBJECT"] = "RusCraftingResource-26.3.zip"
        self.publish(ok=False)
        self.assertNotIn("notify", (self.root / "actions").read_text())
        self.assertFalse((self.root / "bucket/RusCraftingResource.zip.sha256").exists())
        del self.env["FAIL_OBJECT"]
        self.publish()
        self.assertTrue((self.root / "bucket/RusCraftingResource.zip.sha256").exists())

    def test_unchanged_manifest_repairs_missing_or_replaced_objects(self):
        self.publish()
        variant = self.root / "bucket/RusCraftingResource-26.3.zip"
        expected = variant.read_bytes()
        variant.unlink()
        self.publish()
        self.assertEqual(variant.read_bytes(), expected)
        variant.write_bytes(b"different bytes")
        variant.with_suffix('.metadata').write_text('different-sha')
        self.publish()
        self.assertEqual(variant.read_bytes(), expected)


if __name__ == "__main__":
    unittest.main()

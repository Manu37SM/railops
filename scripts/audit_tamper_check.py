"""After tampering, log in as the bootstrap admin (decrypting its TOTP secret with the LOCAL-ONLY dev key)
and call the audit verification endpoint."""
import base64, hashlib, hmac, json, struct, subprocess, time, urllib.request
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

LOCAL_KEY = base64.b64decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
hexblob = subprocess.run(["psql", "-h", "localhost", "-U", "railops", "-d", "railops", "-tAc",
                          "SELECT encode(mfa_secret_enc,'hex') FROM identity.app_user WHERE email='root.admin@railops.local'"],
                         capture_output=True, text=True, env={"PGPASSWORD": "railops", "PATH": "/usr/bin"}).stdout.strip()
blob = bytes.fromhex(hexblob)
secret = AESGCM(LOCAL_KEY).decrypt(blob[1:13], blob[13:], b"identity.app_user.mfa_secret").decode()

def totp(s):
    key = base64.b32decode(s + "=" * (-len(s) % 8)); step = int(time.time()) // 30
    h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest(); o = h[-1] & 15
    return "%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 1000000)

time.sleep(31 - int(time.time()) % 30)  # fresh step: earlier steps were consumed
req = urllib.request.Request("http://localhost:8082/api/v1/auth/login", method="POST",
      headers={"Content-Type": "application/json"},
      data=json.dumps({"email": "root.admin@railops.local", "password": "Local-Only-Bootstrap-Passw0rd", "otp": totp(secret)}).encode())
token = json.loads(urllib.request.urlopen(req).read())["accessToken"]
req = urllib.request.Request("http://localhost:8082/api/v1/admin/audit/verify", headers={"Authorization": "Bearer " + token})
print("verify after tampering:", urllib.request.urlopen(req).read().decode())

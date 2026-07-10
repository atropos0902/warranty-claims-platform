"""Prints a local token with the evaluation scope. In AWS the job asks Cognito for one instead."""
import base64, hashlib, hmac, json, os, time

SECRET = os.environ.get("LOCAL_SECRET", "local-only-secret-not-for-any-real-use-01").encode()


def part(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=")


now = int(time.time())
body = part(json.dumps({"alg": "HS256"}).encode()) + b"." + part(json.dumps(
    {"sub": "evaluation", "scope": "assistant/evaluate", "iat": now, "exp": now + 3600}).encode())
print((body + b"." + part(hmac.new(SECRET, body, hashlib.sha256).digest())).decode())

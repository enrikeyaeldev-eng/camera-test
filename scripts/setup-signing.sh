#!/usr/bin/env bash
# Creates the release signing keystore ONCE and uploads it as GitHub Actions secrets.
#
#   bash scripts/setup-signing.sh            create keystore (if missing), then try to upload secrets
#   bash scripts/setup-signing.sh --upload   upload secrets from an existing keystore again
#
# Everything is written to ./signing/, which .gitignore keeps out of git.
# Nothing here is ever printed to the terminal; never paste these values into a chat.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
DIR="$ROOT/signing"
KEYSTORE="$DIR/a56cam-release.jks"
VALUES="$DIR/secrets-to-add.txt"
ALIAS="a56cam"

mkdir -p "$DIR"
chmod 700 "$DIR"

if ! git -C "$ROOT" check-ignore -q "$KEYSTORE"; then
  echo "Refusing to continue: $KEYSTORE would not be ignored by git." >&2
  exit 1
fi

if [ "${1:-}" != "--upload" ]; then
  if [ -f "$KEYSTORE" ]; then
    echo "Keystore already exists at $KEYSTORE. Not overwriting it (a new key would break app updates)."
  else
    # PKCS12 keystores use one password for the store and the key.
    PASS="$(openssl rand -base64 32 | tr -dc 'A-Za-z0-9' | head -c 32)"
    keytool -genkeypair -noprompt \
      -keystore "$KEYSTORE" -storetype PKCS12 \
      -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
      -storepass "$PASS" -keypass "$PASS" \
      -dname "CN=A56Cam, O=A56Cam Owner, C=US" > /dev/null
    chmod 600 "$KEYSTORE"
    umask 077
    {
      echo "Add these four repository secrets in GitHub:"
      echo "Settings > Secrets and variables > Actions > New repository secret"
      echo
      echo "Name: KEYSTORE_PASSWORD"
      echo "Secret: $PASS"
      echo
      echo "Name: KEY_ALIAS"
      echo "Secret: $ALIAS"
      echo
      echo "Name: KEY_PASSWORD"
      echo "Secret: $PASS"
      echo
      echo "Name: KEYSTORE_BASE64"
      echo "Secret (one long line, copy all of it):"
      base64 -w0 "$KEYSTORE"
      echo
    } > "$VALUES"
    echo "Created $KEYSTORE"
  fi
fi

if [ ! -f "$VALUES" ]; then
  echo "Missing $VALUES; cannot upload." >&2
  exit 1
fi
PASS="$(sed -n '/^Name: KEYSTORE_PASSWORD$/{n;s/^Secret: //p}' "$VALUES")"

echo "Trying to upload the secrets with gh..."
if base64 -w0 "$KEYSTORE" | gh secret set KEYSTORE_BASE64 2>/dev/null \
  && printf '%s' "$PASS" | gh secret set KEYSTORE_PASSWORD \
  && printf '%s' "$ALIAS" | gh secret set KEY_ALIAS \
  && printf '%s' "$PASS" | gh secret set KEY_PASSWORD; then
  echo "Done: all four secrets are set. Check them under Settings > Secrets and variables > Actions."
else
  echo
  echo "gh could not set secrets (the Codespace token usually is not allowed to)."
  echo "Add them by hand instead: open signing/secrets-to-add.txt in the editor and follow it."
fi

echo
echo "IMPORTANT: back up the signing/ folder privately (right-click it in the Explorer > Download)."
echo "Losing a56cam-release.jks means uninstalling the app before the next install."

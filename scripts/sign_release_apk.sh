#!/usr/bin/env bash
# Aligns, signs and checks RavenEmu's Release APK (RELEASING.md):
#
#   sign_release_apk.sh UNSIGNED.apk SIGNED.apk VERSION
#
# The key comes from the environment only, and is never printed:
# ANDROID_KEYSTORE_BASE64, a PKCS12 keystore in Base64 holding one private
# key, and ANDROID_KEYSTORE_PASSWORD, the password of the keystore and of
# its key. The Android build tools come from ANDROID_HOME (or
# ANDROID_SDK_ROOT). The signed APK must be com.ravenemu, named "RavenEmu",
# at VERSION (x.y.z) and its version code, not debuggable, with RavenEmu's
# ARM64 libraries, each named lib*.so: Android installs no other library
# from a Release APK. Any failure leaves no signed APK behind.
set -euo pipefail

fail() {
    echo "::error::$*" >&2
    exit 1
}

[[ $# -eq 3 ]] || fail "Usage : sign_release_apk.sh UNSIGNED.apk SIGNED.apk VERSION"
unsigned=$1
signed=$2
version=$3
[[ "$version" =~ ^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]] \
    || fail "Version invalide : $version (x.y.z attendu)"
code=$((BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3]))

if [[ -z "${ANDROID_KEYSTORE_BASE64:-}" || -z "${ANDROID_KEYSTORE_PASSWORD:-}" ]]; then
    fail "Configurer ANDROID_KEYSTORE_BASE64 et ANDROID_KEYSTORE_PASSWORD dans les secrets du depot."
fi
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk" && -d "$sdk/build-tools" ]] || fail "Android SDK Build Tools absents (ANDROID_HOME)."
bt="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
for tool in zipalign apksigner aapt; do
    [[ -x "$bt/$tool" ]] || fail "$tool introuvable dans $bt."
done
[[ -s "$unsigned" ]] || fail "APK Release non signe introuvable : $unsigned"

umask 077
work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ravenemu-sign-XXXXXX")"
trap 'rm -rf "$work"' EXIT
rm -f "$signed"
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$work/keystore.p12" \
    || fail "ANDROID_KEYSTORE_BASE64 n'est pas du Base64 valide."
[[ -s "$work/keystore.p12" ]] || fail "Le keystore decode est vide."

"$bt/zipalign" -P 16 -f 4 "$unsigned" "$work/aligned.apk"
"$bt/zipalign" -c -P 16 4 "$work/aligned.apk"
# A PKCS12 keystore with a single private key needs no alias.
"$bt/apksigner" sign \
    --ks "$work/keystore.p12" \
    --ks-type PKCS12 \
    --ks-pass env:ANDROID_KEYSTORE_PASSWORD \
    --key-pass env:ANDROID_KEYSTORE_PASSWORD \
    --out "$work/signed.apk" "$work/aligned.apk" \
    || fail "Signature refusee : keystore, mot de passe ou nombre de cles invalide."
"$bt/apksigner" verify --verbose --print-certs --min-sdk-version 26 "$work/signed.apk"
"$bt/zipalign" -c -P 16 4 "$work/signed.apk"

"$bt/aapt" dump badging "$work/signed.apk" > "$work/badging.txt"
grep -Fq "package: name='com.ravenemu' versionCode='$code' versionName='$version' " "$work/badging.txt" \
    || fail "Identifiant ou version inattendus : com.ravenemu $version ($code) attendu."
grep -Fxq "application-label:'RavenEmu'" "$work/badging.txt" || fail "Nom d'application inattendu : RavenEmu attendu."
if grep -Fxq "application-debuggable" "$work/badging.txt"; then
    fail "L'APK Release est debogable."
fi

unzip -Z1 "$work/signed.apk" > "$work/entries.txt"
for lib in libraven_android.so libravenemu_gbc_libretro.so libravenemu_gba_libretro.so; do
    grep -Fxq "lib/arm64-v8a/$lib" "$work/entries.txt" || fail "Bibliotheque ARM64 absente : $lib"
done
if grep -E '^lib/[^/]+/[^/]+\.so$' "$work/entries.txt" | grep -Ev '/lib[^/]*\.so$'; then
    fail "Bibliotheque sans prefixe lib : Android ne l'installera pas depuis le Release."
fi

mkdir -p "$(dirname "$signed")"
mv "$work/signed.apk" "$signed"
echo "APK signe : $signed (com.ravenemu $version, code $code)"

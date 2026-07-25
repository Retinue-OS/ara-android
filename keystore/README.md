# Nightly test signing key

`nightly-test.jks` is a self-signed, **non-secret** test certificate used only to
sign nightly APK builds (see `.github/workflows/nightly.yml`) so that each
nightly build has a consistent signature and can be installed over the
previous one without uninstalling.

It is **not** a production/Play Store signing key. Its password is
intentionally checked in alongside it in `nightly-test.properties` — treat
both files as public. Never reuse this key for a real release build.

Regenerate it (if it's ever lost or needs rotating) with:

```bash
keytool -genkeypair -v \
  -keystore keystore/nightly-test.jks \
  -storetype PKCS12 \
  -alias ara-nightly \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass ara-nightly-test -keypass ara-nightly-test \
  -dname "CN=Ara Nightly Test, OU=Retinue OS, O=Retinue OS, L=Unknown, ST=Unknown, C=US"
```

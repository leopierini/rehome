# Making a release

The installer downloads `releases/latest/download/rehome.apk` and `rehome-wifi.apk` from this repo, so a release
must carry those two files, signed with the release key. An APK signed with any other key can't update an installed
rehome: every user would have to uninstall first.

You need the release key (`~/.rehome/release.jks`) and the four `REHOME_*` properties in
`~/.gradle/gradle.properties` or the environment (see the top of [`build.gradle`](../build.gradle)). If only some
of them are set, or the key file is missing, the build stops instead of signing with the debug key. Keep a copy of
the key and its passwords somewhere safe: without them no update can ever reach the people who installed rehome.

1. **Bump the version.** In `app/build.gradle`, raise `versionCode` by one and set `versionName` (for example
   `1.0.1`). Do the same in `wifi/build.gradle` when the Wi-Fi helper changed. Every published build gets a new
   `versionCode`, so two builds are never confused.
2. **Commit** the bump (and everything else in the release). The script refuses a working tree with changes.
3. **Build and check:**

   ```sh
   tools/make-release.sh            # or: tools/make-release.sh /some/folder/outside/the/repo
   ```

   It runs a clean `assembleRelease` with the unit tests, lint and the installer's tests, checks with `apksigner`
   that both APKs have exactly one signer, `CN=rehome`, with the same certificate, and writes to `~/.rehome/dist/`:
   `rehome.apk`, `rehome-wifi.apk`, the same two named after their version (`rehome-1.0.1.apk`, …), `SHA256SUMS`
   and `BUILD.txt` (commit, versions, certificate digest). It fails, and writes nothing, if any check fails.
4. **Tag and publish** on GitHub, with the tag `v<versionName>` on the commit you built:

   ```sh
   git tag v1.0.1 && git push origin main v1.0.1
   gh release create v1.0.1 ~/.rehome/dist/rehome.apk ~/.rehome/dist/rehome-wifi.apk \
     --title "rehome 1.0.1" --notes "What changed. SHA-256: (paste from ~/.rehome/dist/SHA256SUMS)"
   ```

   Upload the two plain-named files: those are the names the installer asks for. The versioned copies stay on your
   computer, to tell builds apart.
5. **Check the download** the installer will get:

   ```sh
   curl -sL -o /tmp/rehome.apk https://github.com/leopierini/rehome/releases/latest/download/rehome.apk
   shasum -a 256 /tmp/rehome.apk    # the same as rehome.apk in SHA256SUMS
   ```

   Then install it on a TV once with `./install.sh` (a `--dry-run` stops before the download, so it doesn't test
   this).

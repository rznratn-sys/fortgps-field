# FortGPS Field 2.0.0-alpha2 — test report

Date: 31 October 2026. This is a prerelease implementation, **not a declaration
of verified operation on Samsung/Android**. No physical Android device was
connected (`adb devices` returned no devices).

## Evidence and status

- **Implemented:** MediaStore discovery, EXIF uncertainty intervals, stable
  FortGPS XMP merge, direct same-URI edit with durable recovery backup, Room
  operation states, independent JPEG readback, explicit consent/retry/restore UI,
  additive revision export, non-destructive Room 1→2 migration.
- **Successfully tested:** 34 Kotlin/JVM/Robolectric tests, including 9 XMP/time
  tests and one migration test; 11 Python contract tests; Android lint. Final
  release build result and certificate are recorded in the release manifest.
- **Verified on real JPEG:** isolated copies of all seven Test 3 photographs;
  only 20261031_185149.jpg changed. Full original EXIF bytes, all non-XMP
  segments, the scan-to-EOF stream and decoded pixels were preserved.
- **Independent reader:** ExifTool successfully read the actual APP1 XMP,
  final object_survey UUID/coordinates/accuracy, moved file and ZIP roundtrip.
- **Verified on actual Android:** **NO**. No live consent, real process-kill
  recovery, target-volume faults, or live Drive sync from alpha2 was exercised.
- **Published:** see GitHub branch/release and release manifest; an APK build
  alone is not publication.

## Test 3 temporal limitation

Five EXIF timestamps fall inside the session and two after it **when explicitly
interpreting offset-less EXIF in Europe/Prague**. Six input JPG have no
OffsetTimeOriginal and historical alpha1 did not persist the session timezone.
The production selector therefore returns ambiguity on the unaugmented legacy
record; manual confirmation is required. It does not silently invent the zone.
The controlled seven-file pipeline test declares Europe/Prague as a test input.
The selected last-in-session file itself has explicit +01:00 and milliseconds.
No original Drive ZIP or input JPG was modified.

Original ZIP SHA-256:
`e1c4dd3f09790814fb1df49f716fcfb24c0d6601a4ab0bf3397075e35173ed10`

Selected original SHA-256:
`9a283f7df05370c45eba26b90abd3e973fc7b46309f5568e0c4a7941cf265efe`

Selected XMP result SHA-256:
`f15834e06142e5a3503ca803e12d98ae3f746faf6547cfc18c6835b7c59fd1ea`

Protected JPEG SHA-256, equal before/after:
`3e33e76f8e5682c749a3970e9b886c835d4a5514b27a8dc5ef0b9fbc71caa14a`

Photographs and precise field data are private test inputs and are not committed
to the public repository. Supply FIELD_PILOT_DIR with session.json and input/
to reproduce the private JPEG test; otherwise that one test is explicitly skipped.
The normal CI does not falsely claim to have run private inputs.

## Required checks — exact scope

| # | Check | Evidence / remaining limit |
|---|---|---|
|1|Last photograph|Unit tests and seven-file pilot with declared zone|
|2|UTC / EXIF / DST|Offsets, stored IANA zone, DST fold/gap and malformed dates tested|
|3|Equal timestamps|Ambiguous intervals tested|
|4|No EXIF time|Automatic selection refused, unit test|
|5|Outside interval|After-end and second-precision boundary tests|
|6|No photographs|no_photo unit test|
|7|Incomplete/cancelled|Automatic processing refused, unit tests|
|8|EXIF GPS preservation|Entire real EXIF payload identical via independent ExifTool extraction|
|9|No recompression|Protected bytes identical; independently decoded pixels identical|
|10|Independent XMP reader|ExifTool on real result|
|11|Idempotence|Second preparation returns byte-identical result|
|12|Conflicting FortGPS|Conflict throws before output is written|
|13|Foreign XMP|CreatorTool preserved through merge; malformed XML refused|
|14|Insufficient space|Space guard tested; real disk-full interruption NOT tested|
|15|Android denial|Code and durable permission_required state; live dialog/denial NOT tested|
|16|Restart mid-operation|Durable journal implemented; database reopen tested; kill during write NOT tested|
|17|Alpha1 migration|Database built from original exported v1 schema, payload/outbox/remote ID preserved through Room upgrade/reopen; phone upgrade NOT tested|
|18|New Drive revision|Existing mocked Drive tests plus additive JSON schema contract; live alpha2 upload NOT tested|
|19|Move JPG|Host filesystem move + independent XMP read; Samsung gallery move NOT tested|
|20|ZIP/export|Host ZIP roundtrip byte identity + independent XMP read|

## Reproduction

```
cd android
FIELD_PILOT_DIR=/private/test3 ./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
cd ..
python -m pip install -r integration/requirements.txt
python -m unittest discover -s tests -v
```

Use scripts/build-alpha2.sh with the original private signing environment for
release builds. Never publish the environment, keystore or private test inputs.

## Required phone pilot before operational use

1. Export and open current alpha1 backup. Update without uninstalling. Verify
   existing UUIDs, open session, queue and Drive setup.
2. New photo session: start → 3 ordinary Samsung photos → close. Confirm system
   write permission, explicit filename success, exactly one changed JPG.
3. Deny consent; verify GPS completion and history; grant and retry. Repeat after
   process restart and permission revocation.
4. On disposable copies only, kill during writing and verify restoration/readback
   against known originals. Verify retained backup when access is revoked.
5. Check a no-photo session, tied EXIF seconds, clock/timezone change and Motion
   Photo refusal. Check the real low-space behaviour without risking originals.
6. Independently read final XMP and original EXIF GPS after Samsung folder move
   and ZIP transfer. Confirm immutable new JSON revision actually arrives in Drive.

## Known blocking limits

An atomic MediaStore replacement is unavailable here. The visible JPEG can be
partly written until recovery after a crash. The durable backup prevents the
app from intentionally discarding the original, but cannot survive uninstall,
app-data deletion, device loss or all storage failures. Concurrent external
editing cannot be fully excluded. These limits must not be reported as a
transactional guarantee. Motion Photo/MPF/extended-XMP and >16 MiB JPEG are refused.
A stale or incomplete MediaStore index cannot prove absence of a later image.

# Field 2.0.0-alpha2 — implementation and boundaries

Base: `field-2-native`, e816e6acd43c2dbfd15e8e8381d62b4fd0a2756b.
Package `cz.fortgps.field`, versionCode 20001. No web 1.1, Spatial adapter,
photographic archive, production registry, or source test archive changes.

## Flow

GPS commit/outbox → separate durable XMP operation → MediaStore EXIF search →
system write consent where required → durable original and prepared temporary
files → verified backup SHA-256 + fsync → durable journal → same-URI write →
fsync + independent reopen → entire JPEG preservation check and XMP readback →
verified state/new immutable JSON revision → remove temporary files.

The app neither adds a MediaStore item nor creates a second permanent photograph.
Room migration 1→2 adds xmp_operations; sessions/outbox remain intact. Operation
status is also included in an additive `xmp_result` JSON field and new revisions.
Private content URIs and backup paths stay in Room and are not uploaded to Drive.
Old alpha1 records remain readable with missing optional fields.

## Safety contract

MediaStore descriptor writes are **not atomic replacements**. A killed process
can leave the visible JPEG partly written until recovery; this cannot be
eliminated by calling createWriteRequest. Durable backup in noBackupFilesDir is
kept until independent verification. Recovery runs at startup, worker retry, or
explicit retry. A known completed hash is verified; otherwise the original is
restored when the current file is missing/corrupt. An unexpected valid JPEG is
not overwritten automatically. History offers explicit original restoration.
Permission revocation may delay restoration. Do not uninstall/clear app data
while recovery is outstanding. Hardware failure, user deletion of all app data,
or another editor racing during a write cannot be made transaction-safe here.

All original reads require MediaStore.setRequireOriginal and ACCESS_MEDIA_LOCATION
on API 29+, preventing EXIF location redaction from becoming the new original.
WRITE_EXTERNAL_STORAGE is limited to API 28; READ_EXTERNAL_STORAGE to API 32;
READ_MEDIA_IMAGES thereafter. Android 14 selected-only access is insufficient
for automatic discovery. No MANAGE_EXTERNAL_STORAGE or MANAGE_MEDIA is requested.
API 30+ uses createWriteRequest, API 29 RecoverableSecurityException. Workers
never launch activities. UI requests system consent once per target; denial
remains visible and manually retryable.

Before write: regular seekable file, original/prepared hashes, private-storage
reserve and target-volume free space. Original/prepared files are fsynced, as
is their directory. Max JPEG size 16 MiB limits memory use; larger items fail
without modification. Output over 60 kB XMP is rejected. Malformed or multiple
XMP packets, extended XMP, MPF and Motion Photo are rejected. Only a validated,
contiguous Samsung SEF trailer containing the four known simple metadata entries
is supported; its EOF-relative offsets and every byte remain unchanged.

JPEG writing: Apache Commons Imaging 1.0.0-alpha5 JpegXmpRewriter.updateXmpXml.
Adobe xmpcore 6.1.11 parses/merges RDF, preserving foreign properties. A read-only
JPEG guard compares every non-XMP segment and whole SOS-to-EOF bytes. Production
uses no Commons Imaging AWT decode/encode API. Android BitmapFactory performs a
sampled input decode check; byte preservation, not decoder tolerance, proves no
recompression. Full arbitrary JPEG corruption detection is not claimed.

## Temporal evidence

DATE_TAKEN is a broad candidate index (session ±26 h, also null/zero values),
never the selected timestamp. DATE_MODIFIED is unused. Every candidate is opened
and DateTimeOriginal, OffsetTimeOriginal, SubsecTimeOriginal are read. Two scans
1.5 seconds apart must agree; hidden/not-yet-indexed camera media still cannot
be proven absent. Limit 2000 candidates; unknown/read-failed candidate times
block automation instead of quietly skipping possible last shots.

New records capture IANA timezone at both ends and a clock-change/boot epoch.
No-offset EXIF uses only a stable stored zone; DST overlaps/gaps are ambiguous.
Explicit offset is respected. An EXIF second is a one-second uncertainty range;
subsecond digits define its smaller range. Entire range must be inside session.
Overlapping latest ranges require manual choice. Missing/invalid session bounds,
mock locations, significant fix/device clock conflict or wall/monotonic duration
conflict block automation. Android provider time is not guaranteed independent
GNSS UTC, especially with network provider. No false independent-clock claim.

Manual confirmation uses the same MediaStore originals and never writes a
known out-of-interval timestamp. Unknown EXIF timezone requires explicit user
confirmation; it does not become automatic EXIF proof. Completed/verified target
cannot be reassigned. Existing different FortGPS metadata is a conflict; keep it
or choose a different original. Destructive conflict replacement is not provided.

Historical alpha1 did not persist timezone. Test 3's six no-offset EXIF records
therefore require manual confirmation in production. A controlled pipeline test
may explicitly supply Europe/Prague; this assumption must be reported separately
from observed metadata.

## References verified during implementation

- Android MediaStore permissions, original bytes, write consent:
  https://developer.android.com/training/data-storage/shared/media
- Exact Apache writer source for pinned release:
  https://github.com/apache/commons-imaging/blob/rel/commons-imaging-1.0.0-alpha5/src/main/java/org/apache/commons/imaging/formats/jpeg/xmp/JpegXmpRewriter.java
- Samsung backwards SEF offsets, upstream ExifTool Samsung.pm ProcessSamsung:
  https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool/Samsung.pm

Device compatibility and process-death behaviour still require a real Samsung.

# FortGPS XMP 1.0

Stable namespace: `https://github.com/rznratn-sys/fortgps-field/ns/fortgps/1.0/`
Preferred prefix: `fortgps`. The URI identifies this project's schema, not a network service.
Stored as standard Adobe XMP RDF/XML in one JPEG APP1 segment.

| Property | Meaning |
| --- | --- |
| SchemaVersion | `1.0` |
| SessionID | Original session UUID |
| SessionStartUTC, SessionEndUTC | Original UTC photographic interval |
| ObjectMeasurementID | UUID of the final `object_survey` |
| ObjectLatitude, ObjectLongitude | WGS84 device location at the final survey; Double round-trip decimal |
| ObjectAccuracyM | Provider's reported horizontal accuracy in metres; omitted if unknown |
| ObjectPositionRelation | `on_roof`, `at_entrance`, `safe_nearby` |
| ObjectType | Existing Field object type |
| VerificationStatus | Existing Field verification status, not upgraded by metadata writing |
| MeasurementSource | Field session source |
| ObjectFixUTC | Final location measurement timestamp |
| GPSProvider | Provider name if available |
| ObjectAltitudeM, AltitudeReference | Original altitude and reference, both omitted if unknown |
| AppVersion | Writer version |

The object anchor is not the building centroid. Original EXIF GPS remains camera
position. XMP writing is not a new GPS measurement and does not improve accuracy.
Missing values are omitted, never replaced with zero.

The whole-file SHA-256 changes after insertion. The protected JPEG hash covers
all original non-XMP pre-scan segments in order and the entire byte stream from
first SOS to EOF (including later scans, EOI, supported Samsung SEF). This is a
strict preservation fingerprint, **not** a universal image deduplication hash.
The writer additionally compares protected byte arrays directly.

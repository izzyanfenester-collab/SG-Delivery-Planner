# Singapore postal search (Build #809 Testing)

The application bundles a searchable **offline Singapore postcode index** derived from
[`dhanyyudi/onemap-building-sg`](https://github.com/dhanyyudi/onemap-building-sg),
which attributes its records to OneMap and the Singapore Open Data Licence.
The reproducible seed uses the **15 February 2026** public snapshot and includes
approximately **123,874 unique six-digit Singapore postal codes**.

On a network connection, Android WorkManager checks the same repository once
every seven days. It downloads a newer CSV only if the public repository has
published a complete, sufficiently large snapshot. It verifies that the
replacement contains at least 100,000 valid postal records before atomically
replacing the old index. If a check fails or the public source is unavailable,
the local index remains available. This does **not** guarantee that the upstream
publisher releases new data every week, or that every newly issued postcode is
present. In particular, `410316` is absent from the February 2026 snapshot;
users should verify the address before selecting a map point.

The postal search requires no API account, user token, or subscription.
OSM map tiles and OSRM road routing are separate online services governed by
their own availability and usage policies. Attribution to OpenStreetMap and
the OneMap-derived data source must be retained when distributing the app.

## Build #810: independently refreshed postal-code sources

The weekly GitHub Actions pipeline (`scripts/build_weekly_postals.py`) adds:

1. **HDB Existing Building**, Singapore Government `data.gov.sg`, dataset
   `d_16b157c52ed637edd6ba1232e026258d`, under the Singapore Open Data Licence.
   The source includes `POSTAL_COD` and building footprints and is typically
   refreshed approximately every two months. Building center points are
   derived from the supplied polygons; they are not guaranteed vehicle entrances.
2. **OpenStreetMap**, downloaded as the latest Malaysia/Singapore/Brunei PBF
   extract from Geofabrik, under the ODbL. Only features with six-digit
   `addr:postcode`, address metadata and coordinates inside Singapore are used.

The original OneMap-derived community snapshot remains the primary full
coverage baseline. The two extra sources add previously unknown codes but do
not overwrite existing resolved coordinates. OSM codes with contradictory
locations in its own features are rejected. This does not guarantee complete
coverage of newly issued Singapore postcodes, nor does it replace address
verification at the destination.

The `postal-data-live` branch publicly publishes a compressed verified combined
index and a version/checksum manifest. The Android app downloads the compressed
index only when a newer complete version is available; a bad checksum or
incomplete dataset never overwrites the existing local database. No delivery,
customer, payment or GPS data is uploaded by this refresh feature. Combined
redistributed data incorporates OpenStreetMap data and must preserve the ODbL
and OSM attribution requirements as well as attribution to the Singapore
government source.

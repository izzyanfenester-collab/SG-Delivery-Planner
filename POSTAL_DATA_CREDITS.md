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

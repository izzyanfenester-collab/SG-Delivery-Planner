# SG Delivery PWA V1.0

Installable Safari/Android PWA with postal-code extraction (maximum 50 unique stops per import), delivery status timestamps, local history and JSON backup/restore.

**Important:** This is not a feature-equivalent port of Android #222. In particular route optimization, ETA, OneMap and Excel exports are not implemented. This build has not been device-tested.

To deploy, merge the pull request and configure GitHub Pages **Deploy from a branch**: main branch /docs folder (or configure GitHub Actions Pages to publish the /pwa directory). Preferred published URL after enabled: https://izzyanfenester-collab.github.io/SG-Delivery-Planner/pwa/

iPhone: open published HTTPS link in Safari, Share > Add to Home Screen. Web data is stored in the browser origin's local storage. Back up periodically, especially before clearing browser data.
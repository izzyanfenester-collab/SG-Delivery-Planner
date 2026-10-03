#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
swift scripts/GenerateIcon.swift IZZDelivery/Assets.xcassets/BrandIcon.imageset/izzyan_icon.png IZZDelivery/Assets.xcassets/AppIcon.appiconset/AppIcon.png
xcodegen generate --spec project.yml

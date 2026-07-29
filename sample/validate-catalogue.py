#!/usr/bin/env python3
"""Validate sample/catalogue.json before it is published.

The catalogue is fetched at runtime by already-distributed sample apps, so a malformed or
schema-breaking file cannot be caught by their build — only here. Run it locally before pushing:

    python3 sample/validate-catalogue.py
"""
import json
import pathlib
import sys

CATALOGUE = pathlib.Path(__file__).with_name("catalogue.json")
MIME_TYPES = {"HLS", "DASH", "MP4", "WEBM"}
DRM_SCHEMES = {"WIDEVINE", "PLAYREADY", "FAIRPLAY", "CLEARKEY"}
REQUIRED_ITEM_FIELDS = ("id", "title", "label", "url", "mimeType")


def main() -> int:
    errors: list[str] = []

    try:
        data = json.loads(CATALOGUE.read_text())
    except FileNotFoundError:
        print(f"missing {CATALOGUE}")
        return 1
    except json.JSONDecodeError as e:
        print(f"invalid JSON: line {e.lineno} column {e.colno}: {e.msg}")
        return 1

    if data.get("version") != 1:
        errors.append(f"version must be 1, found {data.get('version')!r}")

    posters = data.get("posters", {})
    seen_ids: set[str] = set()
    items = 0

    for rail in data.get("rails", []):
        where = f"rail {rail.get('id', '?')!r}"
        for key in ("id", "title", "items"):
            if key not in rail:
                errors.append(f"{where}: missing {key!r}")
        for item in rail.get("items", []):
            items += 1
            iid = item.get("id", "?")
            at = f"{where} item {iid!r}"

            for key in REQUIRED_ITEM_FIELDS:
                if not item.get(key):
                    errors.append(f"{at}: missing {key!r}")

            if iid in seen_ids:
                errors.append(f"{at}: duplicate id")
            seen_ids.add(iid)

            if item.get("mimeType") not in MIME_TYPES:
                errors.append(f"{at}: mimeType must be one of {sorted(MIME_TYPES)}")

            if not str(item.get("url", "")).startswith("https://"):
                errors.append(f"{at}: url must be https")

            if "poster" in item and item["poster"] not in posters:
                errors.append(f"{at}: poster {item['poster']!r} is not declared in posters")

            if "poster" in item and "tint" in item:
                errors.append(f"{at}: poster and tint are mutually exclusive")

            drm = item.get("drm")
            if drm is not None:
                scheme = drm.get("scheme")
                if scheme not in DRM_SCHEMES:
                    errors.append(f"{at}: drm.scheme must be one of {sorted(DRM_SCHEMES)}")
                if scheme == "CLEARKEY":
                    # Clear Key carries its keys inline (hex keyId -> hex key); there is no license server.
                    if not drm.get("keys"):
                        errors.append(f"{at}: ClearKey requires drm.keys")
                elif not drm.get("licenseServerUrl"):
                    errors.append(f"{at}: drm.licenseServerUrl is required")
                # FairPlay cannot start a key session without the application certificate.
                if scheme == "FAIRPLAY" and not drm.get("certificateUrl"):
                    errors.append(f"{at}: FairPlay requires drm.certificateUrl")

            ads = item.get("ads")
            if ads is not None:
                kind = ads.get("type")
                if kind == "clientSide":
                    if not ads.get("adTagUri"):
                        errors.append(f"{at}: clientSide ads require adTagUri")
                elif kind == "serverSide":
                    if not ads.get("breaks"):
                        errors.append(f"{at}: serverSide ads require breaks")
                    for brk in ads.get("breaks", []):
                        if "startMs" not in brk:
                            errors.append(f"{at}: ad break {brk.get('id', '?')!r} missing startMs")
                        for ad in brk.get("ads", []):
                            if not ad.get("durationMs"):
                                errors.append(f"{at}: ad {ad.get('id', '?')!r} missing durationMs")
                else:
                    errors.append(f"{at}: ads.type must be 'clientSide' or 'serverSide'")

    if errors:
        print(f"{len(errors)} problem(s) in {CATALOGUE.name}:")
        for e in errors:
            print(f"  {e}")
        return 1

    print(f"catalogue.json valid — schema v{data['version']}, "
          f"{len(data.get('rails', []))} rails, {items} items")
    return 0


if __name__ == "__main__":
    sys.exit(main())

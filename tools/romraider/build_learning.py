#!/usr/bin/env python3
"""
Builds app/src/main/assets/learning_axes.json for the «Обучение» table (what RomRaider's Learning Table Values
shows): for each ECU id, where its ROM keeps the axes of the learned values, read over SSM like any address:
  af   - A/F Learning #1 Airflow Ranges (g/s): the MAF limits between the stored trims A, B, C, D
  rpm  - Fine Correction Rows (RPM): row limits of the fine learning knock correction table
  load - Fine Correction Columns (Load) (g/rev): its column limits
Each axis is [address, count, storage, multiplier]. The table itself sits in RAM next to IAM (see LearnedValues.kt).

Source: the SubaruDefs ECUFlash metric definitions (Stable branch, https://github.com/RomRaider/SubaruDefs).

    git clone --depth 1 https://github.com/RomRaider/SubaruDefs.git
    python3 tools/romraider/build_learning.py "SubaruDefs/ECUFlash/subaru metric" app/src/main/assets/learning_axes.json
"""
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

AXES = {
    "af": ["A/F Learning #1 Airflow Ranges"],
    "rpm": ["Fine Correction Rows (RPM)", "Fine Correction Rows (RPM)_"],
    "load": ["Fine Correction Columns (Load)", "Fine Correction Columns (Load)_"],
}

# ROMs the metric definitions above don't have, added by hand. 2A04446305 (A8AK210A, JDM Forester XT, 32-bit): the
# addresses and 7 + 7 elements are from TD-D's SubaruDefs (Alpha, ECUFlash/subaru standard/A8AK210A.xml), no A/F axis there.
MANUAL = {
    "2A04446305": {"bits": 32, "cal": "A8AK210A", "load": [0x6A4EC, 7, "float", 1.0], "rpm": [0x6A508, 7, "float", 1.0]},
}


def load(path):
    try:
        return ET.parse(path).getroot()
    except ET.ParseError:
        # a few files carry stray characters; the parts needed here are plain
        text = open(path, encoding="utf-8", errors="replace").read()
        text = re.sub(r"&(?!amp;|lt;|gt;|quot;|apos;|#)", "&amp;", text)
        return ET.fromstring(text)


def main(src, out):
    roms = {}
    for path in glob.glob(os.path.join(src, "**", "*.xml"), recursive=True):
        root = load(path)
        rom = root if root.tag == "rom" else root.find("rom")
        if rom is None:
            continue
        rid = rom.find("romid")
        xmlid = (rid.findtext("xmlid") if rid is not None else None) or os.path.splitext(os.path.basename(path))[0]
        tables, scalings = {}, {}
        for s in rom.findall("scaling"):
            scalings[s.get("name")] = (s.get("storagetype"), s.get("toexpr"))
        for t in rom.findall("table"):
            name = t.get("name")
            elements = None
            for c in t.findall("table"):
                if c.get("elements"):
                    elements = int(c.get("elements"))
            tables[name] = {
                "address": t.get("address"), "scaling": t.get("scaling"),
                "elements": int(t.get("elements")) if t.get("elements") else elements,
            }
        roms[xmlid.strip()] = {
            "ecuid": (rid.findtext("ecuid") or "").strip().upper() if rid is not None else "",
            "include": (rom.findtext("include") or "").strip(),
            "tables": tables, "scalings": scalings,
        }

    def chain(xmlid):
        seen = []
        while xmlid and xmlid in roms and xmlid not in seen:
            seen.append(xmlid)
            xmlid = roms[xmlid]["include"]
        return seen

    def lookup(xmlid, name):
        """The table merged down the include chain: the ROM gives the address, a base the size and scaling."""
        merged = {}
        for x in reversed(chain(xmlid)):
            t = roms[x]["tables"].get(name)
            if t:
                merged.update({k: v for k, v in t.items() if v is not None})
        return merged

    def scaling(xmlid, name):
        for x in chain(xmlid):
            if name in roms[x]["scalings"]:
                return roms[x]["scalings"][name]
        return None

    result, skipped = {}, 0
    for xmlid, r in sorted(roms.items()):
        ecuid = r["ecuid"]
        if not re.fullmatch(r"[0-9A-F]{10}", ecuid):
            continue
        bases = chain(xmlid)
        bits = 32 if "32BITBASE" in bases else 16 if "16BITBASE" in bases else 0
        entry = {"cal": xmlid, "bits": bits}
        for key, names in AXES.items():
            for name in names:
                t = lookup(xmlid, name)
                if not t.get("address") or not t.get("elements") or not t.get("scaling"):
                    continue
                sc = scaling(xmlid, t["scaling"])
                if not sc:
                    continue
                storage, expr = sc
                m = re.fullmatch(r"\s*x\s*(?:\*\s*([0-9.]+))?\s*", expr or "")
                if not m or storage not in ("float", "uint8", "uint16"):
                    print(f"{xmlid}: {name}: unsupported scaling {storage} {expr}", file=sys.stderr)
                    continue
                entry[key] = [int(t["address"], 16), t["elements"], storage, float(m.group(1) or 1)]
                break
        if len(entry) > 2:
            result[ecuid] = entry
        else:
            skipped += 1

    for ecuid, entry in MANUAL.items():
        result.setdefault(ecuid, entry)

    with open(out, "w") as f:
        json.dump({"source": "SubaruDefs ECUFlash metric (Stable), https://github.com/RomRaider/SubaruDefs",
                   "roms": result}, f, separators=(",", ":"), sort_keys=True)
    full = sum(1 for e in result.values() if all(k in e for k in AXES))
    print(f"{len(result)} ECU ids ({full} with all three axes), {skipped} without any", file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])

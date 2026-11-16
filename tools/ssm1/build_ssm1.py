#!/usr/bin/env python3
"""
Builds the base of the pre-1999 Subaru engine units that speak SSM1 (the old Select Monitor protocol, 1953 baud):

  app/src/main/assets/ssm1_engine.json

  {"source": "...",
   "mbs":  {"MB_VB": {"en": "Battery voltage", "unit": "V", "f": "*8/100", "p": 1}, ...},
   "dtcs": {"DTC_11": {"code": "11", "en": "Crank angle sensor"}, ...},
   "roms": [{"b1": 114, "b2": 37, "from": 16, "to": 31, "name": "...", "src": "FreeSSM",
             "mb": {"MB_VB": 4149, ...},
             "dc": [{"cur": 2950, "mem": 2946, "bits": {"8": "DTC_11", ...}}],
             "clear": [4246, 0]}, ...]}

A ROM id is three bytes. A unit matches a "roms" entry when its first two bytes are b1 b2 and the third is within
from..to. Formulas ("f") are FreeSSM's: operators applied strictly left to right, operands may be signed ("*-64").
Bit numbers in "bits" are FreeSSM's, 1..8, bit n = mask 1 << (n - 1).

Sources (facts only: addresses, scalings, code numbers; credited on the screen):
  - FreeSSM, definitions/SSM1defs_Engine.xml (GPL-3.0), pinned to FREESSM_COMMIT: L-series/XT, SVX, early Legacy
    (EJ20G/EJ22) units with their measuring blocks, trouble code bits and the clear-memory address.
  - SelectMonitor.ini 1.1.0 (2008, JECSScan / SelectMonitor, the values contributed and tested by owners), as kept in
    silver13/Subaru_SSM1_arduino_display at SILVER13_COMMIT: WRX/STi 1994-96 (7440xx), Impreza GT 1997-98 and JDM
    GC8 STi 1997-98 (A30113), Forester 1999 EDM (A30117), early Legacy and SVX. The A301xx map was confirmed on a JDM
    GC8 STi 1997 by P1kachu/ssm1-gc8. The ini has no scalings: the FreeSSM ones of the same values are used, and
    pressures (their scaling is not known) are left out. The ini has no trouble codes either.

Run with `python3 -I`:

  python3 -I tools/ssm1/build_ssm1.py --freessm /path/to/SSM1defs_Engine.xml --ini /path/to/SelectMonitor.ini
"""

import argparse
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

FREESSM_COMMIT = "1a0fa0934581b3383adfd2722050503695ca9dab"
SILVER13_COMMIT = "dd493d9a14ac63fc8ff830e4c26dc27a37c0e4cf"

OUT = Path(__file__).resolve().parents[2] / "app/src/main/assets/ssm1_engine.json"

# SelectMonitor.ini parameter -> FreeSSM measuring block (the scaling comes from that block)
INI_MB = {
    "BatteryVoltage": "MB_VB",
    "VehicleSpeed": "MB_VSP",
    "EngineSpeed": "MB_EREV",
    "CoolantTemp": "MB_TW",
    "IgnitionAdvance": "MB_ADVS",
    "AirflowSensor": "MB_QA",
    "EngineLoad": "MB_LDATA",
    "ThrottlePosition": "MB_THV",
    "InjectorPulseWidth": "MB_TIM",
    "ISUDutyValve": "MB_ISC",
    "O2Average": "MB_O2_R",
    "O2Minimum": "MB_O2_Rmin",
    "O2Maximum": "MB_O2_Rmax",
    "KnockCorrection": "MB_RTRD",
    "AFCorrection": "MB_ALPHA_R",
    "BoostSolenoidDutyCycle": "MB_WGC",
}

# the ini's ROM ids that FreeSSM already covers with its own (fuller) data: FreeSSM wins
INI_SKIP_PREFIX = {"7225"}


def hexint(s):
    return int(s.strip(), 16)


def text(el, tag, lang=None):
    for c in el.findall(tag):
        if lang is None or c.get("lang") == lang:
            return (c.text or "").strip()
    return ""


def freessm(path):
    root = ET.parse(path).getroot()
    common = root.find("DATA_COMMON")
    mbs = {}
    for mb in common.findall("MB"):
        f = text(mb, "FORMULA")
        mbs[mb.get("id")] = {"en": text(mb, "TITLE", "en"), "unit": text(mb, "UNIT"), "f": f,
                             "p": int(text(mb, "PRECISION") or "0")}
    dtcs = {}
    for dc in common.findall("DC"):
        dtcs[dc.get("id")] = {"code": text(dc, "CODE"), "en": text(dc, "TITLE", "en")}

    roms = []

    def collect(el, inherited):
        """MBs, DC blocks and the clear address of [el], over what its parents gave."""
        d = {"mb": dict(inherited["mb"]), "dc": list(inherited["dc"]), "clear": inherited["clear"],
             "name": inherited["name"]}
        for mb in el.findall("MB"):
            a = text(mb, "ADDRESS")
            if a and re.fullmatch(r"0x[0-9A-Fa-f]+", a):
                d["mb"][mb.get("id")] = hexint(a)
        for blk in el.findall("DCBLOCK"):
            cur = mem = None
            for a in blk.findall("ADDRESS"):
                v = (a.text or "").strip()
                if not re.fullmatch(r"0x[0-9A-Fa-f]+", v):
                    continue
                if a.get("type") == "currentOrTempOrLatest":
                    cur = hexint(v)
                elif a.get("type") == "historicOrMemorized":
                    mem = hexint(v)
            bits = {}
            bf = blk.find("BITFIELD")
            if bf is not None:
                for dc in bf.findall("DC"):
                    b = text(dc, "BIT")
                    if b.isdigit() and 1 <= int(b) <= 8:
                        bits[b] = dc.get("id")
            if bits and (cur is not None or mem is not None):
                d["dc"].append({"cur": cur, "mem": mem, "bits": bits})
        cm = el.find("CLEARMEMORY")
        if cm is not None:
            a, v = text(cm, "ADDRESS"), text(cm, "VALUE")
            if re.fullmatch(r"0x[0-9A-Fa-f]+", a) and re.fullmatch(r"0x[0-9A-Fa-f]+", v):
                d["clear"] = [hexint(a), hexint(v) & 0xFF]
        parts = [text(el, "YEAR"), text(el, "MODELNAME"), text(el, "SYSTEMDESCRIPTION")]
        nm = " ".join(p for p in parts if p and p not in ("None",))
        if nm:
            d["name"] = nm
        return d

    base = {"mb": {}, "dc": [], "clear": None, "name": ""}
    defs = root.find("DEFINITIONS")
    for b1 in defs.findall("ID_BYTE1"):
        d1 = collect(b1, base)
        for b2 in b1.findall("ID_BYTE2"):
            d2 = collect(b2, d1)
            for b3 in b2.findall("ID_BYTE3"):
                d3 = collect(b3, d2)
                if not d3["mb"]:
                    continue
                roms.append({
                    "b1": hexint(b1.get("value")), "b2": hexint(b2.get("value")),
                    "from": hexint(b3.get("value_start")), "to": hexint(b3.get("value_end")),
                    "name": d3["name"], "src": "FreeSSM",
                    "mb": d3["mb"], "dc": d3["dc"], "clear": d3["clear"],
                })
    return mbs, dtcs, roms


def ini(path, mbs):
    roms = []
    sec = None
    vals = {}
    note = ""

    def flush():
        if sec is None:
            return
        rid = sec
        if "-" in rid or not re.fullmatch(r"[0-9A-Fa-f]{4}([0-9A-Fa-f]{2})?", rid) or rid[:4] in INI_SKIP_PREFIX:
            return
        mb = {}
        for k, key in INI_MB.items():
            a = vals.get(k + "Address", "").strip()
            if not a or not re.fullmatch(r"[0-9A-Fa-f]{1,4}", a):
                continue
            if int(a, 16) == 0 and k not in ("BatteryVoltage",):
                continue
            if vals.get(k + "Mode", "1").strip() in ("0", "-1"):
                continue
            if key in mbs:
                mb[key] = int(a, 16)
        if not mb:
            return
        b1, b2 = int(rid[0:2], 16), int(rid[2:4], 16)
        lo, hi = (int(rid[4:6], 16),) * 2 if len(rid) == 6 else (0x00, 0xFF)
        roms.append({"b1": b1, "b2": b2, "from": lo, "to": hi,
                     "name": vals.get("Name", "").strip() or note, "src": "SelectMonitor.ini",
                     "mb": mb, "dc": [], "clear": None})

    for raw in Path(path).read_text(encoding="latin-1").splitlines():
        line = raw.strip()
        m = re.fullmatch(r"\[([^\]]+)\]", line)
        if m:
            flush()
            sec, vals = m.group(1).strip(), {}
            continue
        if line.startswith(";"):
            mm = re.match(r";\s*ROM ID 0x[0-9A-Fa-f]+X*:\s*(.+)", line)
            if mm:
                note = mm.group(1).strip()
            continue
        if "=" in line and sec is not None:
            k, v = line.split("=", 1)
            vals[k.strip()] = v.split(";")[0].strip()
    flush()
    return [r for r in roms if r["b1"] not in (0,) and not (r["b1"] == 0 and r["b2"] == 0)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--freessm", required=True)
    ap.add_argument("--ini", required=True)
    a = ap.parse_args()
    mbs, dtcs, roms = freessm(a.freessm)
    extra = ini(a.ini, mbs)
    # a ROM range FreeSSM has stays FreeSSM's
    def covered(r):
        return any(f["b1"] == r["b1"] and f["b2"] == r["b2"] and not (r["to"] < f["from"] or r["from"] > f["to"]) for f in roms)
    roms += [r for r in extra if not covered(r)]
    used = {k for r in roms for k in r["mb"]}
    out = {
        "source": "FreeSSM SSM1defs_Engine.xml @%s (GPL-3.0); SelectMonitor.ini 1.1.0 @silver13/Subaru_SSM1_arduino_display %s"
                  % (FREESSM_COMMIT[:12], SILVER13_COMMIT[:12]),
        "mbs": {k: v for k, v in mbs.items() if k in used},
        "dtcs": {k: v for k, v in dtcs.items() if any(k in blk["bits"].values() for r in roms for blk in r["dc"])},
        "roms": roms,
    }
    OUT.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print("roms %d (FreeSSM %d, ini %d), mbs %d, dtcs %d -> %s (%d bytes)" % (
        len(roms), sum(r["src"] == "FreeSSM" for r in roms), sum(r["src"] != "FreeSSM" for r in roms),
        len(out["mbs"]), len(out["dtcs"]), OUT, OUT.stat().st_size))


if __name__ == "__main__":
    main()

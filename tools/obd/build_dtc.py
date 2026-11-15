#!/usr/bin/env python3
"""
Builds the offline base of generic OBD-II trouble codes (SAE J2012) with Russian descriptions:

  app/src/main/assets/obd_dtc_ru.json   {"source": "...", "codes": {"P0100": ["<English>", "<Russian>"], ...}}

The DTC screen looks a code up here when the Subaru base (ssm_dtc.json) has none: P0xxx, the generic part of P2xxx,
P34xx (cylinder deactivation), U0xxx (network) and a few C0xxx. Manufacturer codes (P1xxx, most of P3xxx, B1/C1/U1…)
are not here on purpose: their meaning differs from make to make.

Sources and licenses:
  - fabiovila/OBDIICodes, codes.json (MIT), pinned to FABIO_COMMIT: the English descriptions of P0/P2/P3.
    The list has typos and some descriptions lost their first word(s) when it was made; FIXES below corrects them
    after the wording of SAE J2012 (what the code means is not changed, only the text is completed). What could not
    be completed with confidence is dropped (DROP).
  - mytrile/obd-trouble-codes, obd-trouble-codes.csv (MIT), pinned to MYTRILE_COMMIT: an older P0 list, used only
    to cross-check P0 codes and their wording (a code missing in codes.json is taken from it).
  - U0xxx and C0xxx: hand-made list (U_CODES, C_CODES) of the well-known assignments of SAE J2012 only.
  - Russian: written here, rule-based (component and fault glossaries plus bank/sensor/cylinder qualifiers) with
    MANUAL entries for the irregular descriptions, in the terminology of the app's Subaru base (ssm_dtc.json). For a
    few codes the Subaru base's own Russian is reused where the meaning is the same (SSM_REUSE).

Downloads go to a separate cache dir (--dl) and are treated as data only. Run with `python3 -I`:

  python3 -I tools/obd/build_dtc.py --dl /path/to/dtc-dl [--offline]
"""

import argparse
import csv
import json
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "obd_dtc_ru.json")
SSM = os.path.join(ROOT, "app", "src", "main", "assets", "ssm_dtc.json")

FABIO_COMMIT = "a146d475305727f83cd64d0db97c39d6ca7ad534"
MYTRILE_COMMIT = "24e3ca8b9e66ab9c5e3d171bf8480e4509c38563"
FABIO_URL = f"https://raw.githubusercontent.com/fabiovila/OBDIICodes/{FABIO_COMMIT}/codes.json"
MYTRILE_URL = f"https://raw.githubusercontent.com/mytrile/obd-trouble-codes/{MYTRILE_COMMIT}/obd-trouble-codes.csv"

SOURCE_NOTE = ("SAE J2012 generic codes. English: fabiovila/OBDIICodes and mytrile/obd-trouble-codes (MIT), "
               "corrected; U0/C0 hand-made. Russian: tools/obd/build_dtc.py")

# ---------------------------------------------------------------------------------------------------------------
# English: corrections of the source (typos, lost first words) per SAE J2012

FIXES = {
    "P0008": "Engine Position System Performance Bank 1",
    "P0066": "Air Assisted Injector Control Circuit Low",
    "P0257": "Injection Pump Fuel Metering Control B Range/Performance (Cam/Rotor/Injector)",
    "P0296": "Cylinder 12 Contribution/Balance Fault",
    "P032A": "Knock Sensor 3 Circuit Bank 1",
    "P032B": "Knock Sensor 3 Circuit Range/Performance Bank 1",
    "P032C": "Knock Sensor 3 Circuit Low Bank 1",
    "P032E": "Knock Sensor 3 Circuit Intermittent Bank 1",
    "P033A": "Knock Sensor 4 Circuit Bank 2",
    "P033B": "Knock Sensor 4 Circuit Range/Performance Bank 2",
    "P033C": "Knock Sensor 4 Circuit Low Bank 2",
    "P033D": "Knock Sensor 4 Circuit High Bank 2",
    "P033E": "Knock Sensor 4 Circuit Intermittent Bank 2",
    "P0340": "Camshaft Position Sensor A Circuit Malfunction (Bank 1)",
    "P0341": "Camshaft Position Sensor A Circuit Range/Performance (Bank 1)",
    "P042A": "Catalyst Temperature Sensor Circuit (Bank 1 Sensor 2)",
    "P042B": "Catalyst Temperature Sensor Circuit Range/Performance (Bank 1 Sensor 2)",
    "P042C": "Catalyst Temperature Sensor Circuit Low (Bank 1 Sensor 2)",
    "P042D": "Catalyst Temperature Sensor Circuit High (Bank 1 Sensor 2)",
    "P043A": "Catalyst Temperature Sensor Circuit (Bank 2 Sensor 2)",
    "P043B": "Catalyst Temperature Sensor Circuit Range/Performance (Bank 2 Sensor 2)",
    "P043C": "Catalyst Temperature Sensor Circuit Low (Bank 2 Sensor 2)",
    "P044F": "Secondary Air Injection System Switching Valve A Circuit High",
    "P046A": "Catalyst Temperature Sensor 1/2 Correlation (Bank 1)",
    "P046B": "Catalyst Temperature Sensor 1/2 Correlation (Bank 2)",
    "P047A": "Exhaust Pressure Sensor B Circuit",
    "P047B": "Exhaust Pressure Sensor B Circuit Range/Performance",
    "P047C": "Exhaust Pressure Sensor B Circuit Low",
    "P047D": "Exhaust Pressure Sensor B Circuit High",
    "P047E": "Exhaust Pressure Sensor B Circuit Intermittent/Erratic",
    "P048A": "Exhaust Pressure Control Valve A Stuck Closed",
    "P048B": "Exhaust Pressure Control Valve Position Sensor/Switch Circuit",
    "P048E": "Exhaust Pressure Control Valve Position Sensor/Switch Circuit High",
    "P0503": "Vehicle Speed Sensor A Intermittent/Erratic/High",
    "P056A": "Cruise Control Increase Distance Signal",
    "P056B": "Cruise Control Decrease Distance Signal",
    "P0621": "Generator Lamp L Control Circuit Malfunction",
    "P0622": "Generator Field F Control Circuit Malfunction",
    "P062A": "Fuel Pump A Control Circuit Range/Performance",
    "P064A": "Fuel Pump Control Module",
    "P06A3": "Sensor Reference Voltage D Circuit Open",
    "P06A5": "Sensor Reference Voltage D Circuit High",
    "P06A7": "Sensor Reference Voltage B Circuit Range/Performance",
    "P06A8": "Sensor Reference Voltage C Circuit Range/Performance",
    "P06A9": "Sensor Reference Voltage D Circuit Range/Performance",
    "P06AF": "Torque Management System - Forced Engine Shutdown",
    "P06B0": "Sensor Power Supply A Circuit/Open",
    "P06B2": "Sensor Power Supply A Circuit High",
    "P0742": "Torque Converter Clutch Circuit Stuck On",
    "P0756": "Shift Solenoid B Performance or Stuck Off",
    "P081A": "Starter Disable Circuit Low",
    "P081B": "Starter Disable Circuit High",
    "P081C": "Park Input Circuit",
    "P0900": "Clutch Actuator Circuit/Open",
    "P0901": "Clutch Actuator Circuit Range/Performance",
    "P0902": "Clutch Actuator Circuit Low",
    "P0962": "Pressure Control Solenoid A Control Circuit Low",
    "P0963": "Pressure Control Solenoid A Control Circuit High",
    "P0964": "Pressure Control Solenoid B Control Circuit/Open",
    "P0965": "Pressure Control Solenoid B Control Circuit Range/Performance",
    "P0966": "Pressure Control Solenoid B Control Circuit Low",
    "P0967": "Pressure Control Solenoid B Control Circuit High",
    "P0969": "Pressure Control Solenoid C Control Circuit Range/Performance",
    "P0993": "Transmission Fluid Pressure Sensor/Switch F Circuit Range/Performance",
    "P0995": "Transmission Fluid Pressure Sensor/Switch F Circuit High",
    "P2029": "Fuel Fired Heater Disabled",
    "P2030": "Fuel Fired Heater Performance",
    "P201A": "Reductant Injection Valve Circuit Range/Performance Bank 2 Unit 1",
    "P202A": "Reductant Tank Heater Control Circuit/Open",
    "P202B": "Reductant Tank Heater Control Circuit Low",
    "P202C": "Reductant Tank Heater Control Circuit High",
    "P202D": "Reductant Leakage",
    "P202E": "Reductant Injection Valve Circuit Range/Performance Bank 1 Unit 1",
    "P2037": "Reductant Injection Air Pressure Sensor A Circuit",
    "P2038": "Reductant Injection Air Pressure Sensor A Circuit Range/Performance",
    "P2039": "Reductant Injection Air Pressure Sensor A Circuit Low",
    "P2040": "Reductant Injection Air Pressure Sensor A Circuit High",
    "P2041": "Reductant Injection Air Pressure Sensor A Circuit Intermittent",
    "P2042": "Reductant Temperature Sensor Circuit",
    "P2043": "Reductant Temperature Sensor Circuit Range/Performance",
    "P2044": "Reductant Temperature Sensor Circuit Low",
    "P2045": "Reductant Temperature Sensor Circuit High",
    "P2046": "Reductant Temperature Sensor Circuit Intermittent",
    "P204A": "Reductant Pressure Sensor Circuit",
    "P204B": "Reductant Pressure Sensor Circuit Range/Performance",
    "P204C": "Reductant Pressure Sensor Circuit Low",
    "P204D": "Reductant Pressure Sensor Circuit High",
    "P204E": "Reductant Pressure Sensor Circuit Intermittent/Erratic",
    "P204F": "Reductant System Performance Bank 1",
    "P2059": "Reductant Injection Air Pump Control Circuit/Open",
    "P205A": "Reductant Tank Temperature Sensor Circuit",
    "P205B": "Reductant Tank Temperature Sensor Circuit Range/Performance",
    "P205C": "Reductant Tank Temperature Sensor Circuit Low",
    "P205D": "Reductant Tank Temperature Sensor Circuit High",
    "P205E": "Reductant Tank Temperature Sensor Circuit Intermittent/Erratic",
    "P205F": "Reductant System Performance Bank 2",
    "P2060": "Reductant Injection Air Pump Control Circuit Low",
    "P2061": "Reductant Injection Air Pump Control Circuit High",
    "P2062": "Reductant Supply Control Circuit/Open",
    "P2063": "Reductant Supply Control Circuit Low",
    "P2064": "Reductant Supply Control Circuit High",
    "P206E": "Intake Manifold Tuning (IMT) Valve Stuck Open Bank 2",
    "P206F": "Intake Manifold Tuning (IMT) Valve Stuck Closed Bank 2",
    "P2070": "Intake Manifold Tuning (IMT) Valve Stuck Open Bank 1",
    "P2071": "Intake Manifold Tuning (IMT) Valve Stuck Closed Bank 1",
    "P209A": "Reductant Injection Air Pressure Sensor B Circuit",
    "P209B": "Reductant Injection Air Pressure Sensor B Circuit Range/Performance",
    "P209E": "Reductant Injection Air Pressure Sensor A/B Correlation",
    "P20E6": "Reductant Injection Air Pressure Too Low",
    "P20E7": "Reductant Injection Air Pressure Too High",
    "P20EF": "SCR NOx Pre-Catalyst Efficiency Below Threshold Bank 1",
    "P20F3": "SCR NOx Pre-Catalyst Efficiency Below Threshold Bank 2",
    "P20F6": "Reductant Injection Valve Stuck Open Bank 1 Unit 1",
    "P20F7": "Reductant Injection Valve Stuck Open Bank 2 Unit 1",
    "P2100": "Throttle Actuator A Control Motor Circuit/Open",
    "P2101": "Throttle Actuator A Control Motor Circuit Range/Performance",
    "P2102": "Throttle Actuator A Control Motor Circuit Low",
    "P2103": "Throttle Actuator A Control Motor Circuit High",
    "P2104": "Throttle Actuator Control System - Forced Idle",
    "P2105": "Throttle Actuator Control System - Forced Engine Shutdown",
    "P2107": "Throttle Actuator Control Module Processor",
    "P2108": "Throttle Actuator Control Module Performance",
    "P210A": "Throttle Actuator B Control Motor Circuit/Open",
    "P210C": "Throttle Actuator B Control Motor Circuit Low",
    "P210D": "Throttle Actuator B Control Motor Circuit High",
    "P210E": "Throttle/Pedal Position Sensor/Switch C/F Voltage Correlation",
    "P2110": "Throttle Actuator Control System - Forced Limited RPM",
    "P2111": "Throttle Actuator Control System - Stuck Open",
    "P2112": "Throttle Actuator Control System - Stuck Closed",
    "P2113": "Throttle/Pedal Position Sensor B Minimum Stop Performance",
    "P2114": "Throttle/Pedal Position Sensor C Minimum Stop Performance",
    "P2115": "Throttle/Pedal Position Sensor D Minimum Stop Performance",
    "P2116": "Throttle/Pedal Position Sensor E Minimum Stop Performance",
    "P2118": "Throttle Actuator Control Motor Current Range/Performance",
    "P2120": "Throttle/Pedal Position Sensor/Switch D Circuit",
    "P2126": "Throttle/Pedal Position Sensor/Switch E Circuit Range/Performance",
    "P212A": "Throttle/Pedal Position Sensor/Switch G Circuit",
    "P212D": "Throttle/Pedal Position Sensor/Switch G Circuit High",
    "P2130": "Throttle/Pedal Position Sensor/Switch F Circuit",
    "P2132": "Throttle/Pedal Position Sensor/Switch F Circuit Low",
    "P2147": "Fuel Injector Group A Supply Voltage Circuit Low",
    "P2149": "Fuel Injector Group B Supply Voltage Circuit/Open",
    "P2150": "Fuel Injector Group B Supply Voltage Circuit Low",
    "P2151": "Fuel Injector Group B Supply Voltage Circuit High",
    "P2153": "Fuel Injector Group C Supply Voltage Circuit Low",
    "P2154": "Fuel Injector Group C Supply Voltage Circuit High",
    "P2159": "Vehicle Speed Sensor B Range/Performance",
    "P215B": "Vehicle Speed - Output Shaft Speed Correlation",
    "P2160": "Vehicle Speed Sensor B Circuit Low",
    "P2163": "Throttle/Pedal Position Sensor A Maximum Stop Performance",
    "P2164": "Throttle/Pedal Position Sensor B Maximum Stop Performance",
    "P2165": "Throttle/Pedal Position Sensor C Maximum Stop Performance",
    "P2167": "Throttle/Pedal Position Sensor E Maximum Stop Performance",
    "P216C": "Fuel Injector Group E Supply Voltage Circuit High",
    "P216E": "Fuel Injector Group F Supply Voltage Circuit Low",
    "P216F": "Fuel Injector Group F Supply Voltage Circuit High",
    "P2172": "Throttle Actuator Control System - Sudden High Air Flow Detected",
    "P2173": "Throttle Actuator Control System - High Air Flow Detected",
    "P2174": "Throttle Actuator Control System - Sudden Low Air Flow Detected",
    "P2175": "Throttle Actuator Control System - Low Air Flow Detected",
    "P217B": "Fuel Injector Group G Supply Voltage Circuit Low",
    "P217C": "Fuel Injector Group G Supply Voltage Circuit High",
    "P217D": "Fuel Injector Group H Supply Voltage Circuit/Open",
    "P217E": "Fuel Injector Group H Supply Voltage Circuit Low",
    "P217F": "Fuel Injector Group H Supply Voltage Circuit High",
    "P2186": "Engine Coolant Temperature Sensor 2 Circuit Intermittent/Erratic",
    "P2199": "Intake Air Temperature Sensor 1/2 Correlation",
    "P219C": "Cylinder 1 Air/Fuel Ratio Imbalance",
    "P219D": "Cylinder 2 Air/Fuel Ratio Imbalance",
    "P219E": "Cylinder 3 Air/Fuel Ratio Imbalance",
    "P219F": "Cylinder 4 Air/Fuel Ratio Imbalance",
    "P2200": "NOx Sensor Circuit Bank 1",
    "P2201": "NOx Sensor Circuit Range/Performance Bank 1",
    "P2202": "NOx Sensor Circuit Low Bank 1",
    "P2203": "NOx Sensor Circuit High Bank 1",
    "P2204": "NOx Sensor Circuit Intermittent Bank 1",
    "P2205": "NOx Sensor Heater Control Circuit/Open Bank 1",
    "P2206": "NOx Sensor Heater Control Circuit Low Bank 1",
    "P2209": "NOx Sensor Heater Sense Circuit Range/Performance Bank 1",
    "P2211": "NOx Sensor Heater Sense Circuit High Bank 1",
    "P2212": "NOx Sensor Heater Sense Circuit Intermittent Bank 1",
    "P2213": "NOx Sensor Circuit Bank 2",
    "P2214": "NOx Sensor Circuit Range/Performance Bank 2",
    "P2219": "NOx Sensor Heater Control Circuit Low Bank 2",
    "P2222": "NOx Sensor Heater Sense Circuit Range/Performance Bank 2",
    "P2225": "NOx Sensor Heater Sense Circuit Intermittent Bank 2",
    "P2226": "Barometric Pressure Sensor A Circuit",
    "P2227": "Barometric Pressure Sensor A Circuit Range/Performance",
    "P222A": "Barometric Pressure Sensor B Circuit",
    "P222E": "Barometric Pressure Sensor B Circuit Intermittent/Erratic",
    "P222F": "Barometric Pressure Sensor A/B Correlation",
    "P2230": "Barometric Pressure Sensor A Circuit Intermittent/Erratic",
    "P2231": "O2 Sensor Signal Circuit Shorted to Heater Circuit Bank 1 Sensor 1",
    "P2234": "O2 Sensor Signal Circuit Shorted to Heater Circuit Bank 2 Sensor 1",
    "P2235": "O2 Sensor Signal Circuit Shorted to Heater Circuit Bank 2 Sensor 2",
    "P2237": "O2 Sensor Positive Current Control Circuit/Open Bank 1 Sensor 1",
    "P2241": "O2 Sensor Positive Current Control Circuit Low Bank 2 Sensor 1",
    "P2242": "O2 Sensor Positive Current Control Circuit High Bank 2 Sensor 1",
    "P2243": "O2 Sensor Reference Voltage Circuit/Open Bank 1 Sensor 1",
    "P2244": "O2 Sensor Reference Voltage Performance Bank 1 Sensor 1",
    "P2245": "O2 Sensor Reference Voltage Circuit Low Bank 1 Sensor 1",
    "P2246": "O2 Sensor Reference Voltage Circuit High Bank 1 Sensor 1",
    "P2247": "O2 Sensor Reference Voltage Circuit/Open Bank 2 Sensor 1",
    "P2248": "O2 Sensor Reference Voltage Performance Bank 2 Sensor 1",
    "P2249": "O2 Sensor Reference Voltage Circuit Low Bank 2 Sensor 1",
    "P2250": "O2 Sensor Reference Voltage Circuit High Bank 2 Sensor 1",
    "P2251": "O2 Sensor Negative Current Control Circuit/Open Bank 1 Sensor 1",
    "P2252": "O2 Sensor Negative Current Control Circuit Low Bank 1 Sensor 1",
    "P2253": "O2 Sensor Negative Current Control Circuit High Bank 1 Sensor 1",
    "P2254": "O2 Sensor Negative Current Control Circuit/Open Bank 2 Sensor 1",
    "P2255": "O2 Sensor Negative Current Control Circuit Low Bank 2 Sensor 1",
    "P2256": "O2 Sensor Negative Current Control Circuit High Bank 2 Sensor 1",
    "P2257": "Secondary Air Injection System Control A Circuit Low",
    "P2258": "Secondary Air Injection System Control A Circuit High",
    "P2259": "Secondary Air Injection System Control B Circuit Low",
    "P2260": "Secondary Air Injection System Control B Circuit High",
    "P2263": "Turbocharger/Supercharger Boost System Performance",
    "P2265": "Water in Fuel Sensor Circuit Range/Performance",
    "P2266": "Water in Fuel Sensor Circuit Low",
    "P2267": "Water in Fuel Sensor Circuit High",
    "P2268": "Water in Fuel Sensor Circuit Intermittent",
    "P226A": "Water in Fuel Lamp Control Circuit",
    "P2271": "O2 Sensor Signal Biased/Stuck Rich Bank 1 Sensor 2",
    "P2273": "O2 Sensor Signal Biased/Stuck Rich Bank 2 Sensor 2",
    "P2274": "O2 Sensor Signal Biased/Stuck Lean Bank 1 Sensor 3",
    "P2283": "Injector Control Pressure Sensor Circuit",
    "P2284": "Injector Control Pressure Sensor Circuit Range/Performance",
    "P2287": "Injector Control Pressure Sensor Circuit Intermittent",
    "P2288": "Injector Control Pressure Too High",
    "P2289": "Injector Control Pressure Too High - Engine Off",
    "P228C": "Fuel Pressure Regulator 1 Exceeded Control Limits - Pressure Too Low",
    "P228F": "Fuel Pressure Regulator 1 Exceeded Learning Limits - Too High",
    "P2290": "Injector Control Pressure Too Low",
    "P2291": "Injector Control Pressure Too Low - Engine Cranking",
    "P229A": "Fuel Pressure Regulator 2 Exceeded Control Limits - Pressure Too Low",
    "P229C": "Fuel Pressure Regulator 2 Exceeded Learning Limits - Too Low",
    "P2425": "Exhaust Gas Recirculation Cooling Valve Control Circuit/Open",
    "P2426": "Exhaust Gas Recirculation Cooling Valve Control Circuit Low",
    "P2427": "Exhaust Gas Recirculation Cooling Valve Control Circuit High",
    "P2430": "Secondary Air Injection System Air Flow/Pressure Sensor Circuit Bank 1",
    "P2431": "Secondary Air Injection System Air Flow/Pressure Sensor Circuit Range/Performance Bank 1",
    "P2432": "Secondary Air Injection System Air Flow/Pressure Sensor Circuit Low Bank 1",
    "P2433": "Secondary Air Injection System Air Flow/Pressure Sensor Circuit High Bank 1",
    "P2435": "Secondary Air Injection System Air Flow/Pressure Sensor Circuit Bank 2",
    "P2437": "Secondary Air Injection System Air Flow/Pressure Sensor Circuit Low Bank 2",
    "P2452": "Diesel Particulate Filter Pressure Sensor A Circuit",
    "P2453": "Diesel Particulate Filter Pressure Sensor A Circuit Range/Performance",
    "P2454": "Diesel Particulate Filter Pressure Sensor A Circuit Low",
    "P2455": "Diesel Particulate Filter Pressure Sensor A Circuit High",
    "P2456": "Diesel Particulate Filter Pressure Sensor A Circuit Intermittent/Erratic",
    "P2457": "Exhaust Gas Recirculation Cooling System Performance",
    "P2458": "Diesel Particulate Filter Regeneration Duration",
    "P2459": "Diesel Particulate Filter Regeneration Frequency",
    "P245A": "Exhaust Gas Recirculation Cooler Bypass Control Circuit/Open",
    "P245B": "Exhaust Gas Recirculation Cooler Bypass Control Circuit Range/Performance",
    "P245C": "Exhaust Gas Recirculation Cooler Bypass Control Circuit Low",
    "P245E": "Diesel Particulate Filter Pressure Sensor B Circuit",
    "P245F": "Diesel Particulate Filter Pressure Sensor B Circuit Range/Performance",
    "P2461": "Diesel Particulate Filter Pressure Sensor B Circuit High",
    "P2462": "Diesel Particulate Filter Pressure Sensor B Circuit Intermittent/Erratic",
    "P2463": "Diesel Particulate Filter Restriction - Soot Accumulation",
    "P2478": "Exhaust Gas Temperature Out of Range Bank 1 Sensor 1",
    "P2479": "Exhaust Gas Temperature Out of Range Bank 1 Sensor 2",
    "P247A": "Exhaust Gas Temperature Out of Range Bank 1 Sensor 3",
    "P247B": "Exhaust Gas Temperature Out of Range Bank 1 Sensor 4",
    "P247C": "Exhaust Gas Temperature Out of Range Bank 2 Sensor 1",
    "P247D": "Exhaust Gas Temperature Out of Range Bank 2 Sensor 2",
    "P247E": "Exhaust Gas Temperature Out of Range Bank 2 Sensor 3",
    "P247F": "Exhaust Gas Temperature Out of Range Bank 2 Sensor 4",
    "P2500": "Generator Lamp/L-Terminal Circuit Low",
    "P2501": "Generator Lamp/L-Terminal Circuit High",
    "P2509": "ECM/PCM Power Input Signal Intermittent",
    "P250A": "Engine Oil Level Sensor Circuit",
    "P250B": "Engine Oil Level Sensor Circuit Range/Performance",
    "P250C": "Engine Oil Level Sensor Circuit Low",
    "P250E": "Engine Oil Level Sensor Circuit Intermittent/Erratic",
    "P250F": "Engine Oil Level Too Low",
    "P2515": "A/C Refrigerant Pressure Sensor B Circuit",
    "P252F": "Engine Oil Level Too High",
    "P253F": "Engine Oil Deteriorated",
    "P2540": "Low Pressure Fuel System Sensor Circuit Range/Performance",
    "P2542": "Low Pressure Fuel System Sensor Circuit High",
    "P2543": "Low Pressure Fuel System Sensor Circuit Intermittent",
    "P2556": "Engine Coolant Level Sensor/Switch Circuit",
    "P2558": "Engine Coolant Level Sensor/Switch Circuit Low",
    "P2560": "Engine Coolant Level Low",
    "P2563": "Turbocharger Boost Control Position Sensor A Circuit Range/Performance",
    "P2564": "Turbocharger Boost Control Position Sensor A Circuit Low",
    "P2566": "Turbocharger Boost Control Position Sensor A Circuit Intermittent",
    "P2586": "Turbocharger Boost Control Position Sensor B Circuit",
    "P2588": "Turbocharger Boost Control Position Sensor B Circuit Low",
    "P2590": "Turbocharger Boost Control Position Sensor B Circuit Intermittent/Erratic",
    "P2600": "Coolant Pump A Control Circuit/Open",
    "P2601": "Coolant Pump A Control Circuit Range/Performance",
    "P2602": "Coolant Pump A Control Circuit Low",
    "P2603": "Coolant Pump A Control Circuit High",
    "P2604": "Intake Air Heater A Circuit Range/Performance",
    "P2605": "Intake Air Heater B Circuit/Open",
    "P2606": "Intake Air Heater B Circuit Range/Performance",
    "P2607": "Intake Air Heater B Circuit Low",
    "P2609": "Intake Air Heater System Performance",
    "P2610": "ECM/PCM Internal Engine Off Timer Performance",
    "P261A": "Coolant Pump B Control Circuit/Open",
    "P261B": "Coolant Pump B Control Circuit Range/Performance",
    "P261C": "Coolant Pump B Control Circuit Low",
    "P261D": "Coolant Pump B Control Circuit High",
    "P2626": "O2 Sensor Pumping Current Trim Circuit/Open Bank 1 Sensor 1",
    "P2627": "O2 Sensor Pumping Current Trim Circuit Low Bank 1 Sensor 1",
    "P2628": "O2 Sensor Pumping Current Trim Circuit High Bank 1 Sensor 1",
    "P2630": "O2 Sensor Pumping Current Trim Circuit Low Bank 2 Sensor 1",
    "P2631": "O2 Sensor Pumping Current Trim Circuit High Bank 2 Sensor 1",
    "P2632": "Fuel Pump B Control Circuit/Open",
    "P2633": "Fuel Pump B Control Circuit Low",
    "P2634": "Fuel Pump B Control Circuit High",
    "P2669": "Actuator Supply Voltage B Circuit/Open",
    "P2671": "Actuator Supply Voltage B Circuit High",
    "P2684": "Actuator Supply Voltage C Circuit/Open",
    "P2685": "Actuator Supply Voltage C Circuit Low",
    "P2686": "Actuator Supply Voltage C Circuit High",
    "P2700": "Transmission Friction Element A Apply Time Range/Performance",
    "P2701": "Transmission Friction Element B Apply Time Range/Performance",
    "P2703": "Transmission Friction Element D Apply Time Range/Performance",
    "P2704": "Transmission Friction Element E Apply Time Range/Performance",
    "P2705": "Transmission Friction Element F Apply Time Range/Performance",
    "P2706": "Shift Solenoid F Malfunction",
    "P2707": "Shift Solenoid F Performance/Stuck Off",
    "P2708": "Shift Solenoid F Stuck On",
    "P2709": "Shift Solenoid F Electrical",
    "P2740": "Transmission Fluid Temperature Sensor B Circuit",
    "P2741": "Transmission Fluid Temperature Sensor B Circuit Range/Performance",
    "P2743": "Transmission Fluid Temperature Sensor B Circuit High",
    "P2744": "Transmission Fluid Temperature Sensor B Circuit Intermittent",
    "P2745": "Intermediate Shaft Speed Sensor B Circuit",
    "P2748": "Intermediate Shaft Speed Sensor B Circuit Intermittent",
    "P2749": "Intermediate Shaft Speed Sensor C Circuit",
    "P2750": "Intermediate Shaft Speed Sensor C Circuit Range/Performance",
    "P2751": "Intermediate Shaft Speed Sensor C Circuit No Signal",
    "P2752": "Intermediate Shaft Speed Sensor C Circuit Intermittent",
    "P2765": "Input/Turbine Speed Sensor B Circuit",
    "P2766": "Input/Turbine Speed Sensor B Circuit Range/Performance",
    "P2768": "Input/Turbine Speed Sensor B Circuit Intermittent",
    "P2769": "Torque Converter Clutch Circuit Low",
    "P2770": "Torque Converter Clutch Circuit High",
    "P2800": "Transmission Range Sensor B Circuit (PRNDL Input)",
    "P2801": "Transmission Range Sensor B Circuit Range/Performance",
    "P2802": "Transmission Range Sensor B Circuit Low",
    "P2803": "Transmission Range Sensor B Circuit High",
    "P2804": "Transmission Range Sensor B Circuit Intermittent",
    "P2A02": "O2 Sensor Circuit Range/Performance Bank 1 Sensor 3",
    "P2A03": "O2 Sensor Circuit Range/Performance Bank 2 Sensor 1",
    "P2A04": "O2 Sensor Circuit Range/Performance Bank 2 Sensor 2",
    "P2A05": "O2 Sensor Circuit Range/Performance Bank 2 Sensor 3",
}

DROP = {
    # still truncated or not certain enough to complete
    "P037E", "P037F", "P064B", "P065A", "P065C", "P202F", "P253A", "P253C", "P253D", "P253E", "P254A", "P254B",
    "P254C", "P255D", "P255E", "P252E", "P260A", "P260B", "P260D", "P2BAC", "P0C2F",
}


def fix_text(code, t):
    """The source's text made whole: spaces around the letter designators, typos, lost first words."""
    t = t.replace("\n", " ").replace("\u00a0", " ").strip()
    t = re.sub(r"\s*/\s*", "/", t)
    t = re.sub(r"([a-z])([A-H])(?=/(?:[A-H]|Open))", r"\1 \2", t)
    t = re.sub(r"/([A-H])([A-Z][a-z])", r"/\1 \2", t)
    t = re.sub(r"([a-z\)])([A-L])([A-Z][a-z])", r"\1 \2 \3", t)
    t = re.sub(r"^\s*([A-H])([A-Z][a-z])", r"\1 \2", t)
    t = re.sub(r"([a-z])([A-L])$", r"\1 \2", t)
    t = re.sub(r"Coil([I-L])([A-Z])", r"Coil \1 \2", t)
    t = re.sub(r"Coil([I-L])$", r"Coil \1", t)
    t = re.sub(r"Solenoid([J-K])([A-Z])", r"Solenoid \1 \2", t)
    t = re.sub(r"Solenoid([J-K])$", r"Solenoid \1", t)
    t = re.sub(r"\b02 Sensor", "O2 Sensor", t)
    t = re.sub(r"\bBank I\b", "Bank 1", t)
    t = re.sub(r"\bSensor I\b", "Sensor 1", t)
    t = re.sub(r"\bGear I\b", "Gear 1", t)
    t = re.sub(r"\bFan I\b", "Fan 1", t)
    t = re.sub(r"\bStock (On|Off)\b", r"Stuck \1", t)
    t = re.sub(r" /([A-H])Correlation", r"/\1 Correlation", t)
    t = re.sub(r" ([A-H]) /([A-H]) ", r" \1/\2 ", t)
    t = re.sub(r"(?<!Exhaust )\bGas Recirculation", "Exhaust Gas Recirculation", t)
    t = re.sub(r"^(\d{1,2}) Glow Plug", r"Cylinder \1 Glow Plug", t)
    t = re.sub(r"^(\d{1,2}) Above Knock Threshold", r"Cylinder \1 Above Knock Threshold", t)
    t = re.sub(r"^Cylinder #(\d+)", r"Cylinder \1", t)
    t = t.replace("Throttle/Pedal Pos Sensor", "Throttle/Pedal Position Sensor")
    t = re.sub(r"Camshaft Position ?- ?Actuator", "Camshaft Position Actuator", t)
    t = t.replace("Interm/Erratic", "Intermittent/Erratic")
    t = t.replace("Check Sum", "Checksum").replace("Turbo Charger", "Turbocharger").replace("Turbo/Super Charger", "Turbocharger/Supercharger")
    t = t.replace("–", "-")
    t = re.sub(r"\s*/\s*", "/", t)
    t = re.sub(r"\s+-\s*|\s*-\s+", " - ", t)
    t = re.sub(r"\s+", " ", t).strip()
    # the pressure control solenoids and the fluid sensors of the transmission without their first word
    if (code.startswith("P27") or code.startswith("P28")) and re.match(r"^Control Solenoid [D-K]\b", t):
        t = re.sub(r"^Control Solenoid", "Pressure Control Solenoid", t)
    if "P049F" <= code <= "P04AA" and t.startswith("Pressure Control Valve"):
        t = "Exhaust " + t
    if code.startswith("P274") and t.startswith("Fluid Temperature"):
        t = "Transmission " + t
    if code.startswith("P274") and t.startswith("Shaft Speed"):
        t = "Intermediate " + t
    return t


# ---------------------------------------------------------------------------------------------------------------
# Hand-made: U0 (network) and C0 codes of SAE J2012 whose assignment is well known

U_MODULES = {
    0x100: ("ECM/PCM A", "блоком управления двигателем (ECM/PCM) A"),
    0x101: ("TCM", "блоком управления АКПП (TCM)"),
    0x102: ("Transfer Case Control Module", "блоком управления раздаточной коробкой"),
    0x103: ("Gear Shift Control Module A", "блоком управления переключением передач A"),
    0x104: ("Cruise Control Module", "блоком круиз-контроля"),
    0x105: ("Fuel Injector Control Module", "блоком управления форсунками"),
    0x106: ("Glow Plug Control Module", "блоком управления свечами накаливания"),
    0x107: ("Throttle Actuator Control Module", "блоком управления приводом дроссельной заслонки"),
    0x108: ("Alternative Fuel Control Module", "блоком управления альтернативным топливом (ГБО)"),
    0x109: ("Fuel Pump Control Module", "блоком управления топливным насосом"),
    0x110: ("Drive Motor Control Module A", "блоком управления тяговым электродвигателем A"),
    0x111: ("Battery Energy Control Module A", "блоком управления тяговой батареей A"),
    0x112: ("Battery Energy Control Module B", "блоком управления тяговой батареей B"),
    0x114: ("Four-Wheel Drive Clutch Control Module", "блоком управления муфтой полного привода"),
    0x115: ("ECM/PCM B", "блоком управления двигателем (ECM/PCM) B"),
    0x121: ("Anti-Lock Brake System (ABS) Control Module", "блоком ABS"),
    0x122: ("Vehicle Dynamics Control Module", "блоком системы курсовой устойчивости (VDC/ESP)"),
    0x123: ("Yaw Rate Sensor Module", "датчиком скорости рыскания"),
    0x124: ("Lateral Acceleration Sensor Module", "датчиком поперечного ускорения"),
    0x125: ("Multi-axis Acceleration Sensor Module", "многоосевым датчиком ускорения"),
    0x126: ("Steering Angle Sensor Module", "датчиком угла поворота руля"),
    0x127: ("Tire Pressure Monitor Module", "блоком контроля давления в шинах"),
    0x128: ("Park Brake Control Module", "блоком управления стояночным тормозом"),
    0x129: ("Brake System Control Module", "блоком управления тормозной системой"),
    0x131: ("Power Steering Control Module", "блоком управления усилителем руля"),
    0x140: ("Body Control Module", "блоком управления кузовной электроникой (BCM)"),
    0x141: ("Body Control Module A", "блоком управления кузовной электроникой (BCM) A"),
    0x142: ("Body Control Module B", "блоком управления кузовной электроникой (BCM) B"),
    0x146: ("Gateway A", "шлюзом (Gateway) A"),
    0x151: ("Restraints Control Module", "блоком подушек безопасности (SRS)"),
    0x155: ("Instrument Panel Cluster (IPC) Control Module", "комбинацией приборов"),
    0x156: ("Information Center A", "информационным центром A"),
    0x159: ("Parking Assist Control Module A", "блоком парковочного ассистента A"),
    0x164: ("HVAC Control Module", "блоком управления климатической установкой"),
    0x167: ("Vehicle Immobilizer Control Module", "блоком иммобилайзера"),
    0x168: ("Vehicle Security Control Module", "блоком охранной системы"),
    0x184: ("Radio", "магнитолой"),
    0x198: ("Telematic Control Module A", "телематическим блоком A"),
    0x199: ("Door Control Module A", "блоком управления двери A"),
    0x200: ("Door Control Module B", "блоком управления двери B"),
}
U_INVALID = {
    0x401: ("ECM/PCM A", "блока управления двигателем (ECM/PCM) A"),
    0x402: ("TCM", "блока управления АКПП (TCM)"),
    0x415: ("Anti-Lock Brake System (ABS) Control Module", "блока ABS"),
    0x416: ("Vehicle Dynamics Control Module", "блока системы курсовой устойчивости (VDC/ESP)"),
    0x422: ("Body Control Module", "блока управления кузовной электроникой (BCM)"),
    0x423: ("Instrument Panel Cluster Control Module", "комбинации приборов"),
    0x428: ("Steering Angle Sensor Module", "датчика угла поворота руля"),
}
U_CODES = {
    "U0001": ("High Speed CAN Communication Bus", "Высокоскоростная шина CAN: неисправность"),
    "U0002": ("High Speed CAN Communication Bus Performance", "Высокоскоростная шина CAN: нарушение работы"),
    "U0003": ("High Speed CAN Communication Bus (+) Open", "Высокоскоростная шина CAN, провод CAN+ (High): обрыв"),
    "U0004": ("High Speed CAN Communication Bus (+) Low", "Высокоскоростная шина CAN, провод CAN+ (High): низкий уровень сигнала"),
    "U0005": ("High Speed CAN Communication Bus (+) High", "Высокоскоростная шина CAN, провод CAN+ (High): высокий уровень сигнала"),
    "U0006": ("High Speed CAN Communication Bus (-) Open", "Высокоскоростная шина CAN, провод CAN- (Low): обрыв"),
    "U0007": ("High Speed CAN Communication Bus (-) Low", "Высокоскоростная шина CAN, провод CAN- (Low): низкий уровень сигнала"),
    "U0008": ("High Speed CAN Communication Bus (-) High", "Высокоскоростная шина CAN, провод CAN- (Low): высокий уровень сигнала"),
    "U0009": ("High Speed CAN Communication Bus (-) Shorted to Bus (+)", "Высокоскоростная шина CAN: провода CAN- и CAN+ замкнуты между собой"),
    "U0010": ("Medium Speed CAN Communication Bus", "Среднескоростная шина CAN: неисправность"),
    "U0011": ("Medium Speed CAN Communication Bus Performance", "Среднескоростная шина CAN: нарушение работы"),
    "U0019": ("Low Speed CAN Communication Bus", "Низкоскоростная шина CAN: неисправность"),
    "U0020": ("Low Speed CAN Communication Bus Performance", "Низкоскоростная шина CAN: нарушение работы"),
    "U0028": ("Vehicle Communication Bus A", "Шина обмена данными автомобиля A: неисправность"),
    "U0029": ("Vehicle Communication Bus A Performance", "Шина обмена данными автомобиля A: нарушение работы"),
    "U0073": ("Control Module Communication Bus A Off", "Шина обмена данными A отключена (bus off)"),
    "U0074": ("Control Module Communication Bus B Off", "Шина обмена данными B отключена (bus off)"),
    "U0300": ("Internal Control Module Software Incompatibility", "Блок управления: несовместимость программного обеспечения"),
    "U0301": ("Software Incompatibility with ECM/PCM", "Несовместимость программного обеспечения с блоком управления двигателем (ECM/PCM)"),
    "U0302": ("Software Incompatibility with Transmission Control Module", "Несовместимость программного обеспечения с блоком управления АКПП (TCM)"),
}
for _n, (_en, _ru) in U_MODULES.items():
    U_CODES["U%04X" % _n] = (f"Lost Communication With {_en}", f"Потеря связи {'со' if _ru.startswith('шл') else 'с'} {_ru}")
for _n, (_en, _ru) in U_INVALID.items():
    U_CODES["U%04X" % _n] = (f"Invalid Data Received From {_en}", f"Некорректные данные от {_ru}")

C_CODES = {
    "C0035": ("Left Front Wheel Speed Sensor Circuit", "Датчик скорости переднего левого колеса, цепь: неисправность"),
    "C0040": ("Right Front Wheel Speed Sensor Circuit", "Датчик скорости переднего правого колеса, цепь: неисправность"),
    "C0045": ("Left Rear Wheel Speed Sensor Circuit", "Датчик скорости заднего левого колеса, цепь: неисправность"),
    "C0050": ("Right Rear Wheel Speed Sensor Circuit", "Датчик скорости заднего правого колеса, цепь: неисправность"),
}

# ---------------------------------------------------------------------------------------------------------------
# Russian: glossaries

# Faults at the end of a description, longest first
FAULTS = {
    "malfunction": "неисправность",
    "range/performance problem": "выход из диапазона/характеристики",
    "range/performance": "выход из диапазона/характеристики",
    "performance problem": "нарушение работы",
    "performance": "нарушение работы",
    "low input": "низкий уровень сигнала",
    "high input": "высокий уровень сигнала",
    "low voltage": "низкое напряжение",
    "high voltage": "высокое напряжение",
    "low": "низкий уровень сигнала",
    "high": "высокий уровень сигнала",
    "/open": "обрыв",
    "open": "обрыв",
    "intermittent/erratic": "перемежающийся/нестабильный сигнал",
    "intermittent/erratic/high": "перемежающийся/нестабильный/высокий сигнал",
    "intermittent": "перемежающийся сигнал",
    "erratic": "нестабильный сигнал",
    "shorted": "короткое замыкание",
    "no signal": "нет сигнала",
    "no activity detected": "нет активности",
    "slow response": "медленный отклик",
    "stuck open": "заклинивание в открытом положении",
    "stuck closed": "заклинивание в закрытом положении",
    "stuck on": "заклинивание во включённом состоянии",
    "stuck off": "заклинивание в выключенном состоянии",
    "performance/stuck off": "нарушение работы или заклинивание в выключенном состоянии",
    "performance or stuck off": "нарушение работы или заклинивание в выключенном состоянии",
    "stuck": "заклинивание",
    "electrical": "электрическая неисправность",
    "exceeded learning limit": "превышен предел адаптации",
    "correlation": "рассогласование сигналов",
    "voltage correlation": "рассогласование напряжений",
    "over temperature": "перегрев",
    "over temperature condition": "перегрев",
    "too low": "ниже нормы",
    "too high": "выше нормы",
    "efficiency below threshold": "эффективность ниже порога",
    "temperature below threshold": "температура ниже порога",
    "incorrect ratio": "неверное передаточное отношение",
    "incorrect gear ratio": "неверное передаточное отношение",
    "slip detected": "обнаружено проскальзывание",
    "minimum stop performance": "нарушение работы в положении минимального упора",
    "maximum stop performance": "нарушение работы в положении максимального упора",
    "minimum/maximum stop performance": "нарушение работы в положениях упоров",
    "not learned": "адаптация не выполнена",
    "insufficient flow": "недостаточный расход",
    "flow insufficient detected": "недостаточный расход",
    "flow excessive detected": "избыточный расход",
    "incorrect flow detected": "некорректный расход",
    "incorrect purge flow": "некорректный расход продувки",
    "leak detected": "обнаружена утечка",
    "too many pulses": "слишком много импульсов",
    "too few pulses": "слишком мало импульсов",
    "intermittent/erratic pulses": "перемежающиеся/нестабильные импульсы",
    "no pulses": "нет импульсов",
    "over current": "превышение тока",
    "apply time range/performance": "время включения вне допустимого диапазона",
    "error": "ошибка",
    "out of range": "вне допустимого диапазона",
    "rationality check malfunction": "ошибка проверки достоверности",
    "unstable": "нестабильность",
    "not present": "отсутствует",
    "signal stuck lean": "сигнал постоянно показывает бедную смесь",
    "signal stuck rich": "сигнал постоянно показывает богатую смесь",
    "signal biased/stuck rich": "сигнал смещён или постоянно показывает богатую смесь",
    "signal biased/stuck lean": "сигнал смещён или постоянно показывает бедную смесь",
    "slow response rich to lean": "медленный отклик (богатая смесь → бедная)",
    "slow response lean to rich": "медленный отклик (бедная смесь → богатая)",
    "delayed response rich to lean": "задержка отклика (богатая смесь → бедная)",
    "delayed response lean to rich": "задержка отклика (бедная смесь → богатая)",
    "performance/low": "нарушение работы, низкий уровень сигнала",
    "performance high": "нарушение работы, высокий уровень сигнала",
    "fault": "неисправность",
    "processor": "ошибка процессора",
    "overspeed": "превышение оборотов",
    "speed low": "низкая частота вращения",
    "speed high": "высокая частота вращения",
    "flow low/performance": "низкий расход или нарушение работы",
    "contribution/balance fault": "нарушение баланса работы цилиндров",
    "flow": "неверный расход",
    "resistance": "сопротивление вне нормы",
    "current range/performance": "ток вне допустимого диапазона",
    "deterioration": "износ (деградация)",
    "restriction": "засорение",
    "regeneration duration": "превышена длительность регенерации",
    "regeneration frequency": "слишком частая регенерация",
    "efficiency": "эффективность ниже нормы",
    "signal circuit shorted to heater circuit": "цепь сигнала замкнута на цепь подогрева",
}

# What follows the component: which part of its wiring, longest first
CIRCUITS = {
    "circuit": "цепь",
    "control circuit": "цепь управления",
    "control motor circuit": "цепь электродвигателя",
    "control motor current": "ток электродвигателя",
    "motor current": "ток электродвигателя",
    "supply voltage circuit": "цепь питания",
    "supply voltage": "напряжение питания",
    "sense circuit": "цепь контроля",
    "signal circuit": "цепь сигнала",
    "input circuit": "входная цепь",
    "output circuit": "выходная цепь",
    "feedback circuit": "цепь обратной связи",
    "primary/secondary circuit": "первичная/вторичная цепь",
    "primary control circuit": "первичная цепь управления",
    "primary circuit": "первичная цепь",
    "secondary circuit": "вторичная цепь",
    "power/ground circuit": "цепь питания/массы",
    "control": "управление",
    "signal": "сигнал",
    "heater control circuit": "цепь управления подогревом",
    "heater sense circuit": "цепь контроля подогрева",
    "heater circuit": "цепь подогрева",
    "terminal circuit": "цепь вывода",
    "communication circuit": "цепь связи",
    "relay control circuit": "цепь управления реле",
    "relay sense circuit": "цепь контроля реле",
    "relay circuit": "цепь реле",
    "power relay control circuit": "цепь управления реле питания",
    "power relay sense circuit": "цепь контроля реле питания",
    "position sensor circuit": "цепь датчика положения",
    "position sensor/switch circuit": "цепь датчика/выключателя положения",
    "control position": "положение регулятора",
    "position": "положение",
    "signal stuck": "сигнал",
    "circuit 1": "цепь 1",
    "positive current control circuit": "цепь управления положительным током",
    "negative current control circuit": "цепь управления отрицательным током",
    "pumping current trim circuit": "цепь калибровки тока накачки",
    "reference voltage circuit": "цепь опорного напряжения",
    "reference voltage": "опорное напряжение",
}

# Components (nominative), matched as a whole after the fault, circuit, designator and qualifiers are taken off
COMP = {
    # fuel
    "fuel volume regulator": "Регулятор объёма топлива",
    "fuel volume regulator control": "Управление регулятором объёма топлива",
    "fuel shutoff valve": "Отсечной топливный клапан",
    "fuel system over pressure relief valve": "Предохранительный клапан избыточного давления топлива",
    "fuel pressure regulator": "Регулятор давления топлива",
    "fuel pressure relief control": "Управление сбросом давления топлива",
    "fuel pressure relief": "Сброс давления топлива",
    "fuel rail/system pressure": "Давление в топливной рампе/системе",
    "low pressure fuel system pressure": "Давление в контуре низкого давления топлива",
    "low pressure fuel system sensor": "Датчик давления в контуре низкого давления топлива",
    "fuel cooler pump": "Насос охладителя топлива",
    "fuel composition sensor": "Датчик состава топлива",
    "fuel temperature sensor": "Датчик температуры топлива",
    "fuel pressure sensor": "Датчик давления топлива",
    "fuel rail pressure sensor": "Датчик давления в топливной рампе",
    "fuel pump": "Топливный насос",
    "fuel pump primary": "Топливный насос",
    "fuel pump secondary": "Топливный насос, вторичная цепь",
    "fuel pump module": "Модуль топливного насоса",
    "fuel pump control module": "Блок управления топливным насосом",
    "fuel level sensor": "Датчик уровня топлива",
    "fuel injector control module": "Блок управления форсунками",
    "fuel injector control module relay": "Реле блока управления форсунками",
    "fuel injector driver": "Драйвер форсунок",
    "fuel injector group": "Группа форсунок",
    "fuel trim": "Топливная коррекция",
    "fuel cap lamp": "Индикатор крышки топливного бака",
    "fuel level output": "Выход сигнала уровня топлива",
    "fuel fired heater": "Топливный подогреватель",
    "injection pump fuel metering control": "Дозатор топлива ТНВД",
    "injection timing control": "Управление моментом впрыска",
    "injector": "Форсунка",
    "injector control pressure sensor": "Датчик управляющего давления форсунок",
    "injector control pressure": "Управляющее давление форсунок",
    "cold start injector": "Пусковая форсунка",
    "air assisted injector control": "Управление форсунками с подачей воздуха",
    "air assisted injector": "Форсунки с подачей воздуха",
    "water in fuel sensor": "Датчик воды в топливе",
    "water in fuel lamp": "Индикатор воды в топливе",
    # air, pressure, temperature
    "mass or volume air flow": "Датчик массового расхода воздуха (ДМРВ)",
    "mass or volume air flow sensor": "Датчик массового расхода воздуха (ДМРВ)",
    "manifold absolute pressure/barometric pressure": "Датчик абсолютного давления во впускном коллекторе (ДАД)/атмосферного давления",
    "manifold absolute pressure": "Абсолютное давление во впускном коллекторе (ДАД)",
    "map": "Абсолютное давление во впускном коллекторе (ДАД)",
    "map/maf": "ДАД/ДМРВ",
    "barometric pressure": "Атмосферное давление",
    "barometric pressure sensor": "Датчик атмосферного давления",
    "intake air temperature": "Датчик температуры впускного воздуха",
    "intake air temperature sensor": "Датчик температуры впускного воздуха",
    "ambient air temperature sensor": "Датчик температуры наружного воздуха",
    "ambient air temperature": "Температура наружного воздуха",
    "air temperature sensor": "Датчик температуры воздуха",
    "charge air cooler temperature sensor": "Датчик температуры охладителя наддувочного воздуха",
    "charge air temperature": "Температура наддувочного воздуха",
    "charge air cooler bypass control": "Управление байпасом охладителя наддувочного воздуха",
    "charge air cooler bypass position sensor": "Датчик положения байпаса охладителя наддувочного воздуха",
    "charge air cooler coolant pump": "Насос охлаждающей жидкости охладителя наддувочного воздуха",
    "engine coolant temperature sensor": "Датчик температуры охлаждающей жидкости (ДТОЖ)",
    "engine coolant temperature": "Температура охлаждающей жидкости",
    "coolant temperature": "Температура охлаждающей жидкости",
    "radiator coolant temperature sensor": "Датчик температуры ОЖ в радиаторе",
    "radiator coolant temperature": "Температура ОЖ в радиаторе",
    "fuel temperature": "Температура топлива",
    "engine oil temperature sensor": "Датчик температуры моторного масла",
    "engine oil pressure sensor/switch": "Датчик/выключатель давления моторного масла",
    "engine oil pressure": "Давление моторного масла",
    "engine oil level sensor": "Датчик уровня моторного масла",
    "engine oil level": "Уровень моторного масла",
    "engine coolant level sensor/switch": "Датчик/выключатель уровня охлаждающей жидкости",
    "engine coolant level": "Уровень охлаждающей жидкости",
    "throttle position": "Положение дроссельной заслонки",
    "throttle position sensor/switch": "Датчик положения дроссельной заслонки (ДПДЗ)",
    "throttle/pedal position sensor/switch": "Датчик положения дросселя/педали акселератора",
    "throttle/pedal position sensor": "Датчик положения дросселя/педали акселератора",
    "pedal position sensor/switch": "Датчик положения педали акселератора",
    "pedal position sensor": "Датчик положения педали акселератора",
    "throttle actuator control": "Управление приводом дроссельной заслонки",
    "throttle actuator": "Привод дроссельной заслонки",
    "throttle actuator control system": "Система управления приводом дроссельной заслонки",
    "throttle actuator control module": "Блок управления приводом дроссельной заслонки",
    "turbocharger/supercharger inlet pressure sensor": "Датчик давления на входе турбокомпрессора/нагнетателя",
    "turbocharger/supercharger inlet pressure": "Давление на входе турбокомпрессора/нагнетателя",
    "turbocharger/supercharger boost control": "Регулятор давления наддува",
    "turbocharger/supercharger boost control module": "Модуль управления давлением наддува",
    "turbocharger/supercharger boost sensor": "Датчик давления наддува",
    "turbocharger/supercharger boost system": "Система наддува",
    "turbocharger/supercharger": "Турбокомпрессор/нагнетатель",
    "turbocharger bypass valve": "Перепускной клапан турбокомпрессора",
    "turbocharger/supercharger bypass valve": "Перепускной клапан турбокомпрессора/нагнетателя",
    "turbocharger boost sensor": "Датчик давления наддува",
    "turbocharger wastegate solenoid": "Соленоид вестгейта турбокомпрессора",
    "turbocharger boost control position sensor": "Датчик положения регулятора наддува",
    "boost control position sensor": "Датчик положения регулятора наддува",
    "exhaust pressure": "Давление отработавших газов",
    "exhaust pressure sensor": "Датчик давления отработавших газов",
    "exhaust pressure control valve": "Клапан управления давлением отработавших газов",
    "exhaust pressure control valve position sensor/switch": "Датчик/выключатель положения клапана давления отработавших газов",
    "exhaust gas temperature sensor": "Датчик температуры отработавших газов",
    "exhaust gas temperature": "Температура отработавших газов",
    "diesel intake air flow control": "Заслонка управления впуском воздуха (дизель)",
    "diesel intake air flow position sensor": "Датчик положения заслонки впуска воздуха (дизель)",
    "diesel intake air flow control system": "Система управления впуском воздуха (дизель)",
    "diesel intake air flow control motor": "Электродвигатель заслонки впуска воздуха (дизель)",
    "intake air heater": "Подогреватель впускного воздуха",
    "intake air heater system": "Система подогрева впускного воздуха",
    "crankcase pressure sensor": "Датчик давления в картере",
    # camshaft, crankshaft, valves
    "camshaft position": "Положение распредвала",
    "camshaft position actuator": "Привод изменения фаз распредвала",
    "camshaft position actuator control": "Управление приводом изменения фаз распредвала",
    "camshaft profile control": "Управление профилем распредвала",
    "camshaft position sensor": "Датчик положения распредвала (ДПРВ)",
    "crankshaft position sensor": "Датчик положения коленвала (ДПКВ)",
    "crankshaft position system variation": "Компенсация погрешности ДПКВ",
    "engine position system": "Система определения положения двигателя",
    "intake valve control solenoid": "Соленоид управления впускными клапанами",
    "exhaust valve control solenoid": "Соленоид управления выпускными клапанами",
    "intake manifold runner control": "Заслонки впускного коллектора",
    "intake manifold runner": "Заслонки впускного коллектора",
    "intake manifold runner position sensor/switch": "Датчик/выключатель положения заслонок впускного коллектора",
    "intake manifold tuning valve": "Клапан изменения геометрии впускного коллектора",
    "intake manifold tuning (imt) valve": "Клапан изменения геометрии впускного коллектора (IMT)",
    # O2
    "ho2s heater": "Подогрев кислородного датчика",
    "ho2s heater control": "Управление подогревом кислородного датчика",
    "o2 sensor": "Кислородный датчик (лямбда-зонд)",
    "ho2s": "Кислородный датчик (лямбда-зонд)",
    "o2 sensor heater": "Подогрев кислородного датчика",
    "nox sensor": "Датчик NOx",
    "nox sensor heater": "Подогрев датчика NOx",
    "upstream oxygen sensors swapped from bank to bank": "Передние кислородные датчики перепутаны местами между банками",
    "downstream oxygen sensors swapped from bank to bank": "Задние кислородные датчики перепутаны местами между банками",
    # ignition, knock
    "ignition coil": "Катушка зажигания",
    "knock sensor": "Датчик детонации",
    "ignition/distributor engine speed input": "Вход сигнала оборотов от системы зажигания/распределителя",
    "timing reference high resolution signal": "Сигнал синхронизации высокого разрешения",
    "glow plug/heater": "Свеча накаливания/подогреватель",
    "glow plug/heater indicator": "Индикатор свечей накаливания",
    "glow plug control module": "Блок управления свечами накаливания",
    "glow plug module": "Блок управления свечами накаливания",
    "glow plug": "Свеча накаливания",
    "rough road sensor": "Датчик неровной дороги",
    # EGR, emissions
    "exhaust gas recirculation": "Система рециркуляции отработавших газов (EGR)",
    "exhaust gas recirculation flow": "Расход рециркуляции отработавших газов (EGR)",
    "exhaust gas recirculation sensor": "Датчик EGR",
    "exhaust gas recirculation temperature sensor": "Датчик температуры EGR",
    "exhaust gas recirculation throttle control": "Управление дросселем EGR",
    "exhaust gas recirculation vent control": "Управление вентиляционным клапаном EGR",
    "exhaust gas recirculation cooling valve": "Клапан охлаждения EGR",
    "exhaust gas recirculation cooling system": "Система охлаждения EGR",
    "exhaust gas recirculation cooler bypass": "Байпас охладителя EGR",
    "exhaust gas recirculation cooler bypass control": "Управление байпасом охладителя EGR",
    "secondary air injection system": "Система подачи вторичного воздуха",
    "secondary air injection system control": "Управление системой вторичного воздуха",
    "secondary air injection system switching valve": "Переключающий клапан системы вторичного воздуха",
    "secondary air injection system relay": "Реле системы вторичного воздуха",
    "secondary air injection system air flow/pressure sensor": "Датчик расхода/давления вторичного воздуха",
    "catalyst system": "Каталитический нейтрализатор",
    "warm up catalyst": "Предварительный каталитический нейтрализатор",
    "main catalyst": "Основной каталитический нейтрализатор",
    "heated catalyst": "Каталитический нейтрализатор с подогревом",
    "catalyst temperature sensor": "Датчик температуры катализатора",
    "catalyst heater": "Подогрев катализатора",
    "evaporative emission control system": "Система EVAP (улавливания паров топлива)",
    "evaporative emission system": "Система EVAP (улавливания паров топлива)",
    "evaporative emissions system": "Система EVAP (улавливания паров топлива)",
    "evaporative emission control system purge control valve": "Клапан продувки адсорбера системы EVAP",
    "evaporative emission system purge control valve": "Клапан продувки адсорбера системы EVAP",
    "evaporative emission control system vent": "Вентиляционный клапан системы EVAP",
    "evaporative emission control system vent valve/solenoid": "Вентиляционный клапан/соленоид системы EVAP",
    "evaporative emission system vent valve": "Вентиляционный клапан системы EVAP",
    "evaporative emission control system pressure sensor": "Датчик давления системы EVAP",
    "evaporative emissions (evap) fuel vapor temperature sensor": "Датчик температуры паров топлива системы EVAP",
    "purge flow sensor": "Датчик расхода продувки адсорбера",
    "emission system leak detection reference orifice": "Эталонное отверстие системы обнаружения утечек EVAP",
    "nox adsorber": "Накопительный нейтрализатор NOx",
    "diesel particulate filter": "Сажевый фильтр (DPF)",
    "diesel particulate filter pressure sensor": "Датчик давления сажевого фильтра (DPF)",
    "catalyst system over temperature": "Перегрев каталитического нейтрализатора",
    "scr nox catalyst": "Нейтрализатор NOx (SCR)",
    "scr nox pre-catalyst": "Предварительный нейтрализатор NOx (SCR)",
    "reductant level sensor": "Датчик уровня реагента (AdBlue)",
    "reductant level": "Уровень реагента (AdBlue)",
    "reductant injection valve": "Форсунка впрыска реагента (AdBlue)",
    "reductant injection air pressure sensor": "Датчик давления воздуха впрыска реагента (AdBlue)",
    "reductant injection air pressure": "Давление воздуха впрыска реагента (AdBlue)",
    "reductant injection air pump": "Воздушный насос впрыска реагента (AdBlue)",
    "reductant temperature sensor": "Датчик температуры реагента (AdBlue)",
    "reductant pressure sensor": "Датчик давления реагента (AdBlue)",
    "reductant system": "Система подачи реагента (AdBlue)",
    "reductant tank temperature sensor": "Датчик температуры бака реагента (AdBlue)",
    "reductant tank heater": "Подогреватель бака реагента (AdBlue)",
    "reductant supply": "Подача реагента (AdBlue)",
    "reductant purge control valve": "Клапан продувки системы реагента (AdBlue)",
    "reductant heater": "Подогреватель реагента (AdBlue)",
    "aftertreatment fuel injector": "Форсунка дожига топлива в системе нейтрализации",
    "aftertreatment fuel supply": "Подача топлива в систему нейтрализации",
    "aftertreatment fuel pressure sensor": "Датчик давления топлива системы нейтрализации",
    "positive crankcase ventilation filter": "Фильтр системы вентиляции картера (PCV)",
    "positive crankcase ventilation regulator valve": "Регулирующий клапан вентиляции картера (PCV)",
    "positive crankcase ventilation heater": "Подогреватель системы вентиляции картера (PCV)",
    # cooling, fans
    "cooling fan": "Вентилятор охлаждения",
    "fan": "Вентилятор",
    "fan speed sensor": "Датчик частоты вращения вентилятора",
    "thermostat heater": "Подогреватель термостата",
    "coolant pump": "Насос охлаждающей жидкости",
    "cooling system": "Система охлаждения",
    # speed, idle, misc engine
    "vehicle speed sensor": "Датчик скорости автомобиля",
    "brake switch": "Выключатель стоп-сигнала",
    "idle control system": "Система регулирования холостого хода",
    "idle air control system": "Система регулирования холостого хода",
    "idle air control": "Регулятор холостого хода",
    "cold start idle air control system": "Регулирование холостого хода при холодном пуске",
    "cold start ignition timing": "Опережение зажигания при холодном пуске",
    "cold start engine coolant temperature": "Температура ОЖ при холодном пуске",
    "brake assist vacuum": "Разрежение в усилителе тормозов",
    "closed throttle position switch": "Выключатель закрытого положения дроссельной заслонки",
    "starter request": "Запрос стартера",
    "battery temperature sensor": "Датчик температуры аккумулятора",
    "cruise control servo": "Сервопривод круиз-контроля",
    "cruise control": "Круиз-контроль",
    "cruise control multi-function input": "Многофункциональный вход круиз-контроля",
    "cruise control on": "Круиз-контроль, сигнал включения",
    "cruise control off": "Круиз-контроль, сигнал выключения",
    "cruise control resume": "Круиз-контроль, сигнал возобновления",
    "cruise control set": "Круиз-контроль, сигнал установки",
    "cruise control coast": "Круиз-контроль, сигнал снижения скорости",
    "cruise control accel": "Круиз-контроль, сигнал ускорения",
    "cruise control increase distance": "Круиз-контроль, сигнал увеличения дистанции",
    "cruise control decrease distance": "Круиз-контроль, сигнал уменьшения дистанции",
    "cruise control/brake switch": "Выключатель тормоза/круиз-контроля",
    "cruise control input": "Вход круиз-контроля",
    "cruise control vacuum": "Вакуумный привод круиз-контроля",
    "cruise control vent": "Вентиляционный клапан круиз-контроля",
    "a/c refrigerant pressure sensor": "Датчик давления хладагента кондиционера",
    "a/c evaporator temperature sensor": "Датчик температуры испарителя кондиционера",
    "a/c clutch relay": "Реле муфты компрессора кондиционера",
    "a/c compressor": "Компрессор кондиционера",
    "power steering pressure sensor": "Датчик давления в системе гидроусилителя руля",
    "power steering": "Усилитель руля",
    "brake booster pressure sensor": "Датчик давления в вакуумном усилителе тормозов",
    "system voltage": "Напряжение бортовой сети",
    "generator": "Генератор",
    "generator lamp": "Контрольная лампа генератора",
    "generator lamp l": "Контрольная лампа генератора (вывод L)",
    "generator field f": "Обмотка возбуждения генератора (вывод F)",
    "generator field/f": "Обмотка возбуждения генератора (вывод F)",
    "generator lamp/l-terminal": "Контрольная лампа генератора/вывод L",
    "starter relay": "Реле стартера",
    "starter disable": "Блокировка стартера",
    "intake air heater control": "Управление подогревателем впускного воздуха",
    "sensor reference voltage": "Опорное напряжение датчиков",
    "sensor power supply": "Питание датчиков",
    "actuator supply voltage": "Напряжение питания исполнительных механизмов",
    "voltage sense": "Контроль напряжения",
    "driver display serial communication": "Последовательная связь с дисплеем водителя",
    "immobilizer lamp": "Индикатор иммобилайзера",
    "speed control lamp": "Индикатор круиз-контроля",
    "malfunction indicator lamp (mil)": "Лампа неисправности двигателя (MIL, чек)",
    "engine rpm output": "Выход сигнала оборотов двигателя",
    "engine hot lamp output": "Индикатор перегрева двигателя",
    "power supply": "Питание",
    # control module
    "serial communication link": "Последовательная линия связи",
    "internal control module": "Блок управления",
    "control module": "Блок управления",
    "pcm/ecm/tcm internal temperature sensor": "Внутренний датчик температуры блока управления",
    "ecm/pcm power relay": "Реле питания ЭБУ",
    "pcm power relay de-energized performance": "Отключение реле питания ЭБУ",
    "tcm communication": "Связь с блоком управления АКПП (TCM)",
    "tcm power input signal": "Входное питание блока управления АКПП (TCM)",
    "tcm power relay": "Реле питания блока управления АКПП (TCM)",
    "tcm engine speed output": "Выход сигнала оборотов двигателя блока АКПП (TCM)",
    "ecm/pcm power input signal": "Входное питание ЭБУ",
    "ecm/pcm internal engine off timer": "Внутренний таймер выключения двигателя ЭБУ",
    "glow plug control module to pcm communication": "Связь блока свечей накаливания с ЭБУ",
    # transmission
    "transmission control system": "Система управления АКПП",
    "torque converter/brake switch": "Выключатель тормоза/гидротрансформатора",
    "clutch switch input": "Вход выключателя сцепления",
    "transmission range sensor": "Датчик положения селектора АКПП",
    "transmission fluid level sensor": "Датчик уровня жидкости АКПП",
    "transmission fluid level": "Уровень жидкости АКПП",
    "transmission fluid temperature sensor": "Датчик температуры жидкости АКПП (ATF)",
    "input/turbine speed sensor": "Датчик скорости входного вала/турбины",
    "transmission mode switch": "Переключатель режимов АКПП",
    "output speed sensor": "Датчик скорости выходного вала",
    "engine speed input": "Вход сигнала оборотов двигателя",
    "torque converter clutch": "Муфта блокировки гидротрансформатора",
    "pressure control solenoid": "Соленоид управления давлением",
    "shift solenoid": "Соленоид переключения передач",
    "shift timing solenoid": "Соленоид синхронизации переключений",
    "normal/performance switch": "Переключатель режима нормальный/спортивный",
    "intermediate shaft speed sensor": "Датчик скорости промежуточного вала",
    "transmission friction element": "Фрикционный элемент АКПП",
    "transmission park position sensor/switch": "Датчик/выключатель положения P (стоянка) АКПП",
    "transmission control system mil request": "Запрос лампы MIL от системы управления АКПП",
    "clutch position sensor": "Датчик положения сцепления",
    "clutch position": "Положение сцепления",
    "clutch position control": "Управление положением сцепления",
    "reverse input": "Вход сигнала задней передачи",
    "reverse output": "Выход сигнала задней передачи",
    "transmission range display": "Индикатор положения селектора АКПП",
    "upshift switch": "Переключатель повышения передачи",
    "downshift switch": "Переключатель понижения передачи",
    "driveline disconnect switch input": "Вход выключателя отключения привода",
    "neutral input": "Вход сигнала нейтрали",
    "park input": "Вход сигнала P (стоянка)",
    "clutch pedal switch": "Выключатель педали сцепления",
    "four wheel drive (4wd) switch": "Выключатель полного привода (4WD)",
    "transmission fluid pressure sensor/switch": "Датчик/выключатель давления жидкости АКПП",
    "clutch actuator": "Привод сцепления",
    "reverse inhibit control": "Управление блокировкой задней передачи",
    "speed sensor": "Датчик скорости",
    "control solenoid": "Управляющий соленоид",
    "battery pack": "Тяговая батарея",
}

# Whole descriptions that do not fit the pattern (after cleaning); also used for the cylinder families below
MANUAL = {
    "Engine Position System Performance": "Система определения положения двигателя: нарушение работы",
    "A Camshaft Position - Timing Over-Advanced or System Performance": "Распредвал A: чрезмерное опережение фаз или нарушение работы системы",
    "B Camshaft Position - Timing Over-Advanced or System Performance": "Распредвал B: чрезмерное опережение фаз или нарушение работы системы",
    "A Camshaft Position - Timing Over-Retarded": "Распредвал A: чрезмерное запаздывание фаз",
    "B Camshaft Position - Timing Over-Retarded": "Распредвал B: чрезмерное запаздывание фаз",
    "Cold Start A Camshaft Position Timing Over-Advanced": "Распредвал A при холодном пуске: чрезмерное опережение фаз",
    "Cold Start A Camshaft Position Timing Over-Retarded": "Распредвал A при холодном пуске: чрезмерное запаздывание фаз",
    "Cold Start B Camshaft Position Timing Over-Advanced": "Распредвал B при холодном пуске: чрезмерное опережение фаз",
    "Cold Start B Camshaft Position Timing Over-Retarded": "Распредвал B при холодном пуске: чрезмерное запаздывание фаз",
    "Crankshaft Position - Camshaft Position Correlation": "Рассогласование сигналов положения коленвала и распредвала (ДПКВ и ДПРВ)",
    "Fuel System Over Pressure Relief Valve Activated": "Сработал предохранительный клапан избыточного давления топлива",
    "Fuel System Over Pressure Relief Valve Frequent Activation": "Слишком частое срабатывание предохранительного клапана избыточного давления топлива",
    "Turbocharger/Supercharger Turbine Overspeed": "Превышение частоты вращения турбины турбокомпрессора/нагнетателя",
    "Turbocharger/Supercharger Boost Control A Position Exceeded Learning Limit": "Регулятор давления наддува A: превышен предел адаптации положения",
    "Turbocharger/Supercharger Boost Control B Position Exceeded Learning Limit": "Регулятор давления наддува B: превышен предел адаптации положения",
    "Fuel System Leak Detected - Large Leak": "Обнаружена большая утечка в топливной системе",
    "Fuel System Leak Detected - Small Leak": "Обнаружена малая утечка в топливной системе",
    "Low Pressure Fuel System Pressure - Too Low, Low Ambient Temperature": "Давление в контуре низкого давления топлива ниже нормы при низкой температуре воздуха",
    "Low Fuel Pressure - Forced Limited Power": "Низкое давление топлива: принудительное ограничение мощности",
    "Fuel Injector Insufficient Flow - Forced Limited Power": "Недостаточная производительность форсунок: принудительное ограничение мощности",
    "Mass or Volume Air Flow A Circuit Range/Performance - Air Flow Too Low": "Датчик массового расхода воздуха (ДМРВ) A: выход из диапазона/характеристики, расход воздуха ниже нормы",
    "Mass or Volume Air Flow A Circuit Range/Performance - Air Flow Too High": "Датчик массового расхода воздуха (ДМРВ) A: выход из диапазона/характеристики, расход воздуха выше нормы",
    "Mass or Volume Air Flow B Circuit Range/Performance - Air Flow Too Low": "Датчик массового расхода воздуха (ДМРВ) B: выход из диапазона/характеристики, расход воздуха ниже нормы",
    "Mass or Volume Air Flow B Circuit Range/Performance - Air Flow Too High": "Датчик массового расхода воздуха (ДМРВ) B: выход из диапазона/характеристики, расход воздуха выше нормы",
    "Engine Coolant Flow Low/Performance": "Недостаточная циркуляция охлаждающей жидкости",
    "Insufficient Coolant Temperature for Closed Loop Fuel Control": "Недостаточная температура охлаждающей жидкости для перехода на замкнутый контур регулирования топливоподачи",
    "Insufficient Coolant Temperature for Stable Operation": "Недостаточная температура охлаждающей жидкости для стабильной работы",
    "Intake Air Temperature Too High": "Слишком высокая температура впускного воздуха",
    "Coolant Thermostat (Coolant Temperature Below Thermostat Regulating Temperature)": "Термостат: температура охлаждающей жидкости ниже рабочей температуры термостата",
    "Barometric Pressure Too Low": "Слишком низкое атмосферное давление",
    "Fuel Delivery Error": "Ошибка подачи топлива",
    "Fuel Timing Error": "Ошибка момента подачи топлива",
    "Temperature Too High": "Слишком высокая температура",
    "Incorrect Fuel Composition": "Неверный состав топлива",
    "System too Lean": "Слишком бедная смесь",
    "System too Rich": "Слишком богатая смесь",
    "System Too Lean Off Idle": "Слишком бедная смесь вне режима холостого хода",
    "System Too Rich Off Idle": "Слишком богатая смесь вне режима холостого хода",
    "System Too Lean at Idle": "Слишком бедная смесь на холостом ходу",
    "System Too Rich at Idle": "Слишком богатая смесь на холостом ходу",
    "System Too Lean at Higher Load": "Слишком бедная смесь при высокой нагрузке",
    "System Too Rich at Higher Load": "Слишком богатая смесь при высокой нагрузке",
    "Post Catalyst Fuel Trim System Too Lean": "Топливная коррекция после катализатора: слишком бедная смесь",
    "Post Catalyst Fuel Trim System Too Rich": "Топливная коррекция после катализатора: слишком богатая смесь",
    "Injector Circuit Malfunction": "Цепь форсунок: неисправность",
    "Engine Shutoff Solenoid Malfunction": "Соленоид останова двигателя: неисправность",
    "Engine Overtemp Condition": "Перегрев двигателя",
    "Transmission Over Temperature Condition": "Перегрев АКПП",
    "Engine Overspeed Condition": "Превышение допустимых оборотов двигателя",
    "Vehicle Overspeed Condition": "Превышение допустимой скорости автомобиля",
    "Engine Oil Over Temperature Condition": "Перегрев моторного масла",
    "Engine Turbocharger/Supercharger Overboost Condition": "Избыточное давление наддува (передув)",
    "Turbocharger/Supercharger A Underboost Condition": "Турбокомпрессор/нагнетатель A: недостаточное давление наддува (недодув)",
    "Turbocharger/Supercharger B Overboost Condition": "Турбокомпрессор/нагнетатель B: избыточное давление наддува (передув)",
    "Turbocharger/Supercharger B Underboost Condition": "Турбокомпрессор/нагнетатель B: недостаточное давление наддува (недодув)",
    "Diesel Intake Air Flow Control System - High Air Flow Detected": "Система управления впуском воздуха (дизель): обнаружен повышенный расход воздуха",
    "Diesel Intake Air Flow Control System - Low Air Flow Detected": "Система управления впуском воздуха (дизель): обнаружен пониженный расход воздуха",
    "Random/Multiple Cylinder Misfire Detected": "Обнаружены случайные/множественные пропуски зажигания",
    "Misfire Detected with Low Fuel": "Пропуски зажигания при низком уровне топлива",
    "Single Cylinder Misfire (Cylinder not Specified)": "Пропуски зажигания в одном цилиндре (цилиндр не определён)",
    "Misfire Detected On Startup (First 1000 Revolutions)": "Пропуски зажигания при пуске (первые 1000 оборотов)",
    "Misfire Detected - Fueling Disabled": "Пропуски зажигания: подача топлива отключена",
    "Rough Road Hardware Not Present": "Отсутствует оборудование определения неровной дороги",
    "Knock Control System Error": "Система подавления детонации: ошибка",
    "Glow Plug/Heater Circuit A": "Свеча накаливания/подогреватель, цепь A: неисправность",
    "Glow Plug/Heater Circuit B": "Свеча накаливания/подогреватель, цепь B: неисправность",
    "Exhaust Gas Recirculation A Control Stuck Open": "Клапан EGR A: заклинивание в открытом положении",
    "Exhaust Gas Recirculation B Control Stuck Closed": "Клапан EGR B: заклинивание в закрытом положении",
    "Exhaust Gas Recirculation B Flow": "Расход рециркуляции отработавших газов (EGR) B: неисправность",
    "Exhaust Gas Recirculation B Flow Insufficient Detected": "Расход рециркуляции отработавших газов (EGR) B: недостаточный",
    "Exhaust Gas Recirculation B Flow Excessive Detected": "Расход рециркуляции отработавших газов (EGR) B: избыточный",
    "Exhaust Gas Recirculation A Control Position Exceeded Learning Limit": "Клапан EGR A: превышен предел адаптации положения",
    "Exhaust Gas Recirculation B Control Position Exceeded Learning Limit": "Клапан EGR B: превышен предел адаптации положения",
    "Exhaust Gas Recirculation Flow Malfunction": "Расход рециркуляции отработавших газов (EGR): неисправность",
    "Exhaust Gas Recirculation Flow Insufficient Detected": "Недостаточный расход рециркуляции отработавших газов (EGR)",
    "Exhaust Gas Recirculation Flow Excessive Detected": "Избыточный расход рециркуляции отработавших газов (EGR)",
    "Evaporative Emission Control System Leak Detected (small leak)": "Система EVAP (улавливания паров топлива): обнаружена малая утечка",
    "Evaporative Emission Control System Leak Detected (gross leak)": "Система EVAP (улавливания паров топлива): обнаружена крупная утечка",
    "Evaporative Emissions System Small Leak Detected": "Система EVAP (улавливания паров топлива): обнаружена очень малая утечка",
    "Evaporative Emission Control System Leak Detected": "Система EVAP (улавливания паров топлива): обнаружена утечка (крышка топливного бака не закрыта или негерметична)",
    "EVAP Flow During A Non-Purge Condition": "Система EVAP: поток паров при выключенной продувке",
    "Evaporative Emission System Low Purge Flow": "Система EVAP: недостаточный расход продувки",
    "Emission System Leak Detection Reference Orifice Low Flow": "Эталонное отверстие системы обнаружения утечек EVAP: недостаточный расход",
    "Cold Start Rough Idle": "Неровный холостой ход при холодном пуске",
    "Cold Start Engine Exhaust Temperature Too Low": "Слишком низкая температура отработавших газов при холодном пуске",
    "Brake Assist Vacuum Too Low": "Недостаточное разрежение в усилителе тормозов",
    "Idle Control System RPM Lower Than Expected": "Обороты холостого хода ниже ожидаемых",
    "Idle Control System RPM Higher Than Expected": "Обороты холостого хода выше ожидаемых",
    "Incorrect Immobilizer Key": "Неверный ключ иммобилайзера",
    "Positive Crankcase Ventilation Filter Restriction": "Засорение фильтра системы вентиляции картера (PCV)",
    "Engine Oil Pressure Too Low": "Слишком низкое давление моторного масла",
    "Air Conditioner Refrigerant Charge Loss": "Потеря хладагента кондиционера",
    "Cruise Control System - Vehicle Speed Too High": "Круиз-контроль: слишком высокая скорость автомобиля",
    "System Voltage Unstable": "Напряжение бортовой сети нестабильно",
    "System Voltage Low": "Напряжение бортовой сети: низкое",
    "System Voltage High": "Напряжение бортовой сети: высокое",
    "System Voltage Malfunction": "Напряжение бортовой сети: неисправность",
    "Serial Communication Link Malfunction": "Последовательная линия связи: неисправность",
    "Internal Control Module Memory Checksum Error": "Блок управления: ошибка контрольной суммы памяти",
    "Control Module Programming Error": "Блок управления: ошибка программирования",
    "Internal Control Module Keep Alive Memory (KAM) Error": "Блок управления: ошибка энергонезависимой памяти (KAM)",
    "Internal Control Module Random Access Memory (RAM) Error": "Блок управления: ошибка ОЗУ (RAM)",
    "Internal Control Module Read Only Memory (ROM) Error": "Блок управления: ошибка ПЗУ (ROM)",
    "Internal Control Module EEPROM Error": "Блок управления: ошибка EEPROM",
    "Control Module Non-Volatile Random Access Memory (NVRAM) Error": "Блок управления: ошибка энергонезависимой памяти (NVRAM)",
    "PCM Processor Fault": "Отказ процессора ЭБУ",
    "Control Module Performance": "Блок управления: внутренняя неисправность",
    "Control Module VSS Output A Malfunction": "Блок управления: выход сигнала скорости (VSS) A, неисправность",
    "Control Module VSS Output B Malfunction": "Блок управления: выход сигнала скорости (VSS) B, неисправность",
    "Internal Control Module Monitoring Processor Performance": "Блок управления: нарушение работы контрольного процессора",
    "Internal Control Module A/D Processing Performance": "Блок управления: нарушение работы АЦП",
    "Internal Control Module Main Processor Performance": "Блок управления: нарушение работы основного процессора",
    "Internal Control Module Accelerator Pedal Position Performance": "Блок управления: ошибка обработки положения педали акселератора",
    "Internal Control Module Throttle Position Performance": "Блок управления: ошибка обработки положения дроссельной заслонки",
    "Internal Control Module Coolant Temperature Performance": "Блок управления: ошибка обработки температуры охлаждающей жидкости",
    "Internal Control Module Fuel Injector Control Performance": "Блок управления: ошибка управления форсунками",
    "Control Module Vehicle Options Error": "Блок управления: ошибка конфигурации автомобиля",
    "TCM Processor": "Блок управления АКПП (TCM): ошибка процессора",
    "ECM/TCM Incompatible": "ЭБУ двигателя и блок управления АКПП несовместимы",
    "Alternative Fuel Control Module KAM Error": "Блок управления альтернативным топливом: ошибка памяти KAM",
    "Alternative Fuel Control Module RAM/ROM Error": "Блок управления альтернативным топливом: ошибка ОЗУ/ПЗУ",
    "Control Module Torque Performance": "Блок управления: ошибка расчёта крутящего момента",
    "Control Module Engine Air Mass Performance": "Блок управления: ошибка расчёта массы воздуха",
    "Control Module Brake Signal Performance": "Блок управления: ошибка обработки сигнала тормоза",
    "Control Module O2 Sensor Processor Performance": "Блок управления: ошибка процессора кислородных датчиков",
    "Control Module Knock Sensor Processor 1 Performance": "Блок управления: ошибка процессора датчиков детонации 1",
    "Control Module Ignition Coil Control Performance": "Блок управления: ошибка управления катушками зажигания",
    "VIN Not Programmed or Incompatible - ECM/PCM": "VIN не записан или не совпадает: ЭБУ двигателя",
    "VIN Not Programmed or Incompatible - TCM": "VIN не записан или не совпадает: блок управления АКПП (TCM)",
    "Odometer Not Programmed - ECM/PCM": "Пробег не записан: ЭБУ двигателя",
    "Immobilizer Key Not Programmed - ECM/PCM": "Ключ иммобилайзера не прописан: ЭБУ двигателя",
    "PCM/ECM/TCM Internal Temperature Too High": "Слишком высокая внутренняя температура блока управления",
    "ECM/TCM Internal Temperature B Too High": "Слишком высокая внутренняя температура блока управления (датчик B)",
    "Configuration Throttle Input Not Present": "Конфигурация: нет входного сигнала дросселя",
    "Configuration Engine Coolant Temperature Input Not Present": "Конфигурация: нет входного сигнала температуры охлаждающей жидкости",
    "Torque Management System - Forced Engine Shutdown": "Система управления крутящим моментом: принудительная остановка двигателя",
    "PCM Power Relay De-Energized Performance - Too Early": "Реле питания ЭБУ отключилось слишком рано",
    "Throttle Actuator Control System - Forced Idle": "Система управления приводом дроссельной заслонки: принудительный холостой ход",
    "Throttle Actuator Control System - Forced Engine Shutdown": "Система управления приводом дроссельной заслонки: принудительная остановка двигателя",
    "Throttle Actuator Control System - Forced Limited RPM": "Система управления приводом дроссельной заслонки: принудительное ограничение оборотов",
    "Throttle Actuator Control System - Stuck Open": "Дроссельная заслонка заклинила в открытом положении",
    "Throttle Actuator Control System - Stuck Closed": "Дроссельная заслонка заклинила в закрытом положении",
    "Throttle Actuator Control System - Sudden High Air Flow Detected": "Система управления дроссельной заслонкой: внезапное повышение расхода воздуха",
    "Throttle Actuator Control System - High Air Flow Detected": "Система управления дроссельной заслонкой: повышенный расход воздуха",
    "Throttle Actuator Control System - Sudden Low Air Flow Detected": "Система управления дроссельной заслонкой: внезапное снижение расхода воздуха",
    "Throttle Actuator Control System - Low Air Flow Detected": "Система управления дроссельной заслонкой: пониженный расход воздуха",
    "Flow Restriction/Air Leak Between Air Filter and MAF": "Засорение или подсос воздуха между воздушным фильтром и ДМРВ",
    "Injector Control Pressure Too High - Engine Off": "Управляющее давление форсунок выше нормы при выключенном двигателе",
    "Injector Control Pressure Too Low - Engine Cranking": "Управляющее давление форсунок ниже нормы при прокручивании стартером",
    "Fuel Pressure Regulator 1 Exceeded Control Limits - Pressure Too Low": "Регулятор давления топлива 1: выход за пределы регулирования, давление ниже нормы",
    "Fuel Pressure Regulator 1 Exceeded Learning Limits - Too High": "Регулятор давления топлива 1: превышен верхний предел адаптации",
    "Fuel Pressure Regulator 2 Exceeded Control Limits - Pressure Too Low": "Регулятор давления топлива 2: выход за пределы регулирования, давление ниже нормы",
    "Fuel Pressure Regulator 2 Exceeded Learning Limits - Too Low": "Регулятор давления топлива 2: превышен нижний предел адаптации",
    "Diesel Particulate Filter Restriction - Ash Accumulation": "Сажевый фильтр (DPF): засорение золой",
    "Diesel Particulate Filter Restriction - Soot Accumulation": "Сажевый фильтр (DPF): засорение сажей",
    "Diesel Particulate Filter Over Temperature": "Перегрев сажевого фильтра (DPF)",
    "Catalyst System Over Temperature": "Перегрев каталитического нейтрализатора",
    "SCR NOx Catalyst - Over Temperature": "Перегрев нейтрализатора NOx (SCR)",
    "SCR NOx Pre-Catalyst - Over Temperature": "Перегрев предварительного нейтрализатора NOx (SCR)",
    "Engine Oil Deteriorated": "Моторное масло выработало ресурс",
    "Engine Oil Level Too Low": "Слишком низкий уровень моторного масла",
    "Engine Oil Level Too High": "Слишком высокий уровень моторного масла",
    "Engine Coolant Level Low": "Низкий уровень охлаждающей жидкости",
    "Transmission Fluid Level Too Low": "Слишком низкий уровень жидкости АКПП",
    "Transmission Fluid Level Too High": "Слишком высокий уровень жидкости АКПП",
    "Reductant Level Too Low": "Слишком низкий уровень реагента (AdBlue)",
    "Reductant Leakage": "Утечка реагента (AdBlue)",
    "Reductant Injection Air Pressure Too Low": "Давление воздуха впрыска реагента (AdBlue) ниже нормы",
    "Reductant Injection Air Pressure Too High": "Давление воздуха впрыска реагента (AdBlue) выше нормы",
    "Fuel Fired Heater Disabled": "Топливный подогреватель отключён",
    "Fuel Fired Heater Performance": "Топливный подогреватель: нарушение работы",
    "Fuel Rail/System Pressure - Too Low": "Давление в топливной рампе/системе ниже нормы",
    "Fuel Rail/System Pressure - Too High": "Давление в топливной рампе/системе выше нормы",
    "Low Pressure Fuel System Pressure - Too Low": "Давление в контуре низкого давления топлива ниже нормы",
    "Low Pressure Fuel System Pressure - Too High": "Давление в контуре низкого давления топлива выше нормы",
    "Bank 1 Air/Fuel Ratio Imbalance": "Дисбаланс состава смеси по цилиндрам, банк 1",
    "Bank 2 Air/Fuel Ratio Imbalance": "Дисбаланс состава смеси по цилиндрам, банк 2",
    "Cooling System Performance": "Система охлаждения: нарушение работы",
    "Speed Sensor Circuit - Loss of Direction Signal": "Датчик скорости, цепь: пропал сигнал направления вращения",
    "Speed Sensor Circuit - Direction Error": "Датчик скорости, цепь: ошибка направления вращения",
    "Shift Malfunction": "Неисправность переключения передач",
    "Shift Time Too Short": "Слишком короткое время переключения передач",
    "Shift Time Too Long": "Слишком долгое время переключения передач",
    "Multiple Gears Engaged": "Включено несколько передач одновременно",
    "Transmission Component Slipping": "Проскальзывание в АКПП",
    "Incorrect Gear Ratio": "Неверное передаточное отношение",
    "Reverse incorrect gear ratio": "Задняя передача: неверное передаточное отношение",
    "Stuck in Neutral": "АКПП не выходит из нейтрали",
    "Stuck In Reverse": "АКПП заклинила на задней передаче",
    "Unable to Engage Neutral": "Не включается нейтраль",
    "Unable to Engage Reverse": "Не включается задняя передача",
    "Transfer Case Control System (MIL Request)": "Система управления раздаточной коробкой (запрос лампы MIL)",
    "1-4 Upshift (Skip Shift) Solenoid Control Circuit Malfunction": "Соленоид переключения 1-4 (пропуск передач), цепь управления: неисправность",
    "1-4 Upshift (Skip Shift) Lamp Control Circuit Malfunction": "Индикатор переключения 1-4 (пропуск передач), цепь управления: неисправность",
    "Clutch Position Not Learned": "Положение сцепления: адаптация не выполнена",
    "Excessive Clutch Slippage": "Чрезмерное проскальзывание сцепления",
    "Excessive Clutch B Slippage": "Чрезмерное проскальзывание сцепления B",
    "Up and Down Shift Switch to Transmission Range Correlation": "Рассогласование переключателей повышения/понижения передачи и положения селектора",
    "Battery Pack Over Temperature": "Перегрев тяговой батареи",
    "Battery Pack Deterioration": "Износ (деградация) тяговой батареи",
    "Cylinder Deactivation System": "Система отключения цилиндров: неисправность",
    "Vehicle Speed - Output Shaft Speed Correlation": "Рассогласование скорости автомобиля и скорости выходного вала",
    "Intake Air Temperature/Ambient Air Temperature Correlation": "Рассогласование температуры впускного и наружного воздуха",
    "Engine Coolant Temperature/Fuel Temperature Correlation": "Рассогласование температуры охлаждающей жидкости и топлива",
    "Coolant Temperature/Intake Air Temperature Correlation": "Рассогласование температуры охлаждающей жидкости и впускного воздуха",
    "Charge Air Temperature/Intake Air Temperature Correlation": "Рассогласование температуры наддувочного и впускного воздуха",
    "Radiator Coolant Temperature/Engine Coolant Temperature Correlation": "Рассогласование температуры ОЖ в радиаторе и в двигателе",
    "MAP/MAF - Throttle Position Correlation": "Рассогласование ДАД/ДМРВ и положения дроссельной заслонки",
    "Manifold Absolute Pressure - Barometric Pressure Correlation": "Рассогласование давления во впускном коллекторе (ДАД) и атмосферного давления",
    "MAP - Mass or Volume Air Flow Correlation": "Рассогласование ДАД и ДМРВ",
    "MAP - Exhaust Pressure Correlation": "Рассогласование ДАД и давления отработавших газов",
    "MAP - Turbocharger/Supercharger Inlet Pressure Correlation": "Рассогласование ДАД и давления на входе турбокомпрессора/нагнетателя",
    "Barometric Pressure - Turbocharger/Supercharger Inlet Pressure Correlation": "Рассогласование атмосферного давления и давления на входе турбокомпрессора/нагнетателя",
    "Manifold Absolute Pressure - Turbocharger/Supercharger Boost Sensor A Correlation": "Рассогласование ДАД и датчика давления наддува A",
    "Manifold Absolute Pressure - Turbocharger/Supercharger Boost Sensor B Correlation": "Рассогласование ДАД и датчика давления наддува B",
    "Charge Air Cooler Temperature Sensor Bank1/Bank2 Correlation": "Датчики температуры охладителя наддувочного воздуха: рассогласование банков 1 и 2",
    "Engine Coolant Temperature Sensor 1/2 Correlation": "Датчики температуры охлаждающей жидкости 1 и 2: рассогласование сигналов",
    "Intake Air Temperature Sensor 1/2 Correlation": "Датчики температуры впускного воздуха 1 и 2: рассогласование сигналов",
    "Glow Plug Control Module to PCM Communication Circuit": "Связь блока свечей накаливания с ЭБУ, цепь: неисправность",
    "Glow Plug Control Module to PCM Communication Circuit Range/Performance": "Связь блока свечей накаливания с ЭБУ, цепь: выход из диапазона/характеристики",
    "Intake Air Heater Control Circuit": "Управление подогревателем впускного воздуха, цепь: неисправность",
    "Intake Air Heater System Performance": "Система подогрева впускного воздуха: нарушение работы",
    "Fuel Pump Control Module": "Блок управления топливным насосом: неисправность",
    "Turbocharger/Supercharger Boost Control A Module Performance": "Модуль управления давлением наддува A: нарушение работы",
    "Turbocharger/Supercharger Boost Control B Module Performance": "Модуль управления давлением наддува B: нарушение работы",
    "Crankshaft Position System Variation Not Learned": "Не выполнена адаптация погрешности ДПКВ",
    "Fuel Volume Regulator Control Exceeded Learning Limit": "Регулятор объёма топлива: превышен предел адаптации",
    "Upstream Oxygen Sensors Swapped From Bank To Bank": "Передние кислородные датчики перепутаны местами между банками",
    "Downstream Oxygen Sensors Swapped From Bank To Bank": "Задние кислородные датчики перепутаны местами между банками",
    "Fan Overspeed": "Вентилятор: превышение частоты вращения",
    "Fan Speed Low": "Вентилятор: низкая частота вращения",
    "Fan Speed High": "Вентилятор: высокая частота вращения",
    "Cooling Fan Rationality Check Malfunction": "Вентилятор охлаждения: ошибка проверки достоверности",
    "Cooling Fan Circuit Over Current": "Вентилятор охлаждения, цепь: превышение тока",
    "Cooling Fan Power/Ground Circuit Malfunction": "Вентилятор охлаждения, цепь питания/массы: неисправность",
    "Diesel Particulate Filter Regeneration Duration": "Сажевый фильтр (DPF): превышена длительность регенерации",
    "Diesel Particulate Filter Regeneration Frequency": "Сажевый фильтр (DPF): слишком частая регенерация",
    "Exhaust Gas Recirculation Cooling System Performance": "Система охлаждения EGR: нарушение работы",
    "Exhaust Gas Recirculation Throttle Control Circuit A Open": "Управление дросселем EGR, цепь A: обрыв",
    "Exhaust Gas Recirculation Throttle Control Circuit A Range/Performance": "Управление дросселем EGR, цепь A: выход из диапазона/характеристики",
    "Exhaust Gas Recirculation Throttle Control Circuit A Low": "Управление дросселем EGR, цепь A: низкий уровень сигнала",
    "Exhaust Gas Recirculation Throttle Control Circuit A High": "Управление дросселем EGR, цепь A: высокий уровень сигнала",
    "Exhaust Gas Recirculation Throttle Control Circuit B/Open": "Управление дросселем EGR, цепь B: обрыв",
    "Exhaust Gas Recirculation Throttle Control Circuit B Range/Performance": "Управление дросселем EGR, цепь B: выход из диапазона/характеристики",
    "Cruise Control On Signal Malfunction": "Круиз-контроль, сигнал включения: неисправность",
    "Cruise Control Off Signal Malfunction": "Круиз-контроль, сигнал выключения: неисправность",
    "Cruise Control Resume Signal Malfunction": "Круиз-контроль, сигнал возобновления: неисправность",
    "Cruise Control Set Signal Malfunction": "Круиз-контроль, сигнал установки: неисправность",
    "Cruise Control Coast Signal Malfunction": "Круиз-контроль, сигнал снижения скорости: неисправность",
    "Cruise Control Accel Signal Malfunction": "Круиз-контроль, сигнал ускорения: неисправность",
    "Cruise Control Increase Distance Signal": "Круиз-контроль, сигнал увеличения дистанции: неисправность",
    "Cruise Control Decrease Distance Signal": "Круиз-контроль, сигнал уменьшения дистанции: неисправность",
    "Generator Lamp L Control Circuit Malfunction": "Контрольная лампа генератора (вывод L), цепь управления: неисправность",
    "Generator Field F Control Circuit Malfunction": "Обмотка возбуждения генератора (вывод F), цепь управления: неисправность",
    "Reductant Injection Air Pressure Sensor A/B Correlation": "Датчики давления воздуха впрыска реагента (AdBlue) A и B: рассогласование сигналов",
    "Barometric Pressure Sensor A/B Correlation": "Датчики атмосферного давления A и B: рассогласование сигналов",
    "Mass or Volume Air Flow Sensor A/B Correlation": "Датчики массового расхода воздуха (ДМРВ) A и B: рассогласование сигналов",
    "Brake Switch A/B Correlation": "Выключатели стоп-сигнала A и B: рассогласование сигналов",
    "Clutch Pedal Switch A/B Correlation": "Выключатели педали сцепления A и B: рассогласование сигналов",
    "Cruise Control Multi-Function Input A/B Correlation": "Многофункциональные входы круиз-контроля A и B: рассогласование сигналов",
    "Transmission Park Position Sensor/Switch A/B Correlation": "Датчики/выключатели положения P (стоянка) АКПП A и B: рассогласование сигналов",
    "Exhaust Gas Recirculation Temperature Sensor A/B Correlation": "Датчики температуры EGR A и B: рассогласование сигналов",
    "Temperature Sensor 1/2 Correlation": "Датчики температуры 1 и 2: рассогласование сигналов",
    "Catalyst Temperature Sensor 1/2 Correlation": "Датчики температуры катализатора 1 и 2: рассогласование сигналов",
    "Starter Request Circuit": "Цепь запроса стартера: неисправность",
    "Fuel Injector Control Module Relay Control": "Реле блока управления форсунками: неисправность управления",
    "Cruise Control Multi-Function Input A Circuit Stuck": "Многофункциональный вход круиз-контроля A, цепь: залипание",
    "Cruise Control Multi-Function Input B Circuit Stuck": "Многофункциональный вход круиз-контроля B, цепь: залипание",
    "Starter Disable Circuit": "Цепь блокировки стартера: неисправность",
    "Torque Converter Clutch Circuit Performance or Stuck Off": "Муфта блокировки гидротрансформатора, цепь: нарушение работы или заклинивание в выключенном состоянии",
}

# Families with a cylinder (or coil/gear) number: (regex on the English text, Russian with \1)
FAMILIES = [
    (r"^Cylinder (\d+) Misfire Detected$", r"Пропуски зажигания в цилиндре \1"),
    (r"^Cylinder (\d+) Injection Timing$", r"Момент впрыска в цилиндре \1: неисправность"),
    (r"^Cylinder (\d+) Contribution/Balance Fault$", r"Цилиндр \1: нарушение баланса работы (вклад цилиндра вне нормы)"),
    (r"^Cylinder (\d+) - Fuel Trim at Max Limit$", r"Топливная коррекция цилиндра \1: на максимальном пределе"),
    (r"^Cylinder (\d+) - Fuel Trim at Min Limit$", r"Топливная коррекция цилиндра \1: на минимальном пределе"),
    (r"^Cylinder (\d+) - Injector Restricted$", r"Форсунка цилиндра \1: засорение (недостаточная подача)"),
    (r"^Cylinder (\d+) - Injector Leaking$", r"Форсунка цилиндра \1: негерметичность (подтекает)"),
    (r"^Cylinder (\d+) Fuel Injector Offset Learning At Min Limit$", r"Адаптация смещения форсунки цилиндра \1: на минимальном пределе"),
    (r"^Cylinder (\d+) Fuel Injector Offset Learning At Max Limit$", r"Адаптация смещения форсунки цилиндра \1: на максимальном пределе"),
    (r"^Cylinder (\d+) Above Knock Threshold$", r"Детонация в цилиндре \1 выше порога"),
    (r"^Cylinder (\d+) Air/Fuel Ratio Imbalance$", r"Дисбаланс состава смеси в цилиндре \1"),
    (r"^Cylinder (\d+) Glow Plug Incorrect$", r"Свеча накаливания цилиндра \1: неподходящая или неисправная"),
    (r"^Injector Circuit Malfunction - Cylinder (\d+)$", r"Форсунка цилиндра \1, цепь: неисправность"),
    (r"^Cold Start Injector (\d) Malfunction$", r"Пусковая форсунка \1: неисправность"),
    (r"^Gear (\d) Incorrect ratio$", r"\1-я передача: неверное передаточное отношение"),
    (r"^Stuck in Gear (\d)$", r"АКПП заклинила на \1-й передаче"),
    (r"^Unable [Tt]o Engage Gear (\d)$", r"Не включается \1-я передача"),
    (r"^(\d)-(\d) Shift Malfunction$", r"Неисправность переключения \1-\2"),
    (r"^Cylinder Deactivation System$", r"Система отключения цилиндров: неисправность"),
]

# Cylinder deactivation (P34xx): "Cylinder N Deactivation/Intake Valve Control Circuit …" and "… Exhaust Valve Control …"
P34_PARTS = {
    "Deactivation/Intake Valve Control": "управление отключением/впускным клапаном",
    "Exhaust Valve Control": "управление выпускным клапаном",
}

# Codes whose Subaru-base Russian (ssm_dtc.json) says exactly what the generic code means: reused for consistency
# (only where the Subaru text leaves nothing of the generic one out: most of its entries drop the sensor letter or bank)
SSM_REUSE = {
    "P0301", "P0302", "P0303", "P0304", "P0305", "P0306", "P0506", "P0507", "P0513", "P0601", "P0604", "P0605",
    "P0731", "P0732", "P0733", "P0734", "P0735", "P0736",
}

# Camshaft actuator circuits: SAE J2012 makes camshaft A the intake one and bank 1 the side of cylinder 1; which side
# that is depends on the make, so the generic text says it that way (the Subaru base names the side itself)
_CAM = {"A": "впускного", "B": "выпускного"}
_BANK = {"1": "банк 1 (ряд цилиндра 1)", "2": "банк 2 (ряд без цилиндра 1)"}
_LEVEL = {"Low": "низкий уровень сигнала", "High": "высокий уровень сигнала"}
RU_BY_CODE = {
    f"P{2088 + i}": f"Клапан изменения фаз {_CAM[c]} распредвала ({c}), {_BANK[b]}, цепь управления: {_LEVEL[lv]}"
    for i, (b, c, lv) in enumerate((b, c, lv) for b in "12" for c in "AB" for lv in ("Low", "High"))
}

QUAL_RE = [
    (re.compile(r"\s*\(Bank (\d)[, ]+Sensor (\d|[A-D])\)\s*$"), lambda m: f"банк {m[1]}, датчик {m[2]}"),
    (re.compile(r"\s*\(?Bank (\d)[, ]+Sensor (\d|[A-D])\)?\s*$"), lambda m: f"банк {m[1]}, датчик {m[2]}"),
    (re.compile(r"\s*\(?Bank (\d) Unit (\d)\)?\s*$"), lambda m: f"банк {m[1]}, узел {m[2]}"),
    (re.compile(r"\s*\(Bank 1 or Single Sensor\)\s*$"), lambda m: "банк 1 или единственный датчик"),
    (re.compile(r"\s*\(?Bank (\d)\)?\s*$"), lambda m: f"банк {m[1]}"),
    (re.compile(r"\s*\(Downstream of throttle valve\)\s*$", re.I), lambda m: "после дроссельной заслонки"),
    (re.compile(r"\s*\(Cam/Rotor/Injector\)\s*$"), lambda m: "кулачок/ротор/форсунка"),
    (re.compile(r"\s*\(PRNDL Input\)\s*$"), lambda m: "вход PRNDL"),
]
DESIG = re.compile(r"^(.*?)\s+([A-L]|\d{1,2}|[A-H]/[A-H]|\d/\d)$")


def _suffixes(s, table):
    """Every entry of [table] that [s] ends with (as whole words), longest first: [(rest, Russian)]."""
    low = s.lower()
    hits = [en for en in table if low == en or low.endswith(" " + en) or (en.startswith("/") and low.endswith(en))]
    return [(s[: len(s) - len(en)].rstrip(" -/"), table[en]) for en in sorted(hits, key=len, reverse=True)]


def _suffix(s, table):
    """The longest entry of [table] that [s] ends with: (rest, Russian) or (s, None)."""
    h = _suffixes(s, table)
    return h[0] if h else (s, None)


def _component(s):
    """A component with an optional letter in front ("A Camshaft…") or a designator behind ("…Sensor A"):
    (Russian, designator) or None."""
    lead = None
    m = re.match(r"^([A-H]) (.+)$", s)
    if m and m[2].lower() in COMP or m and DESIG.match(m[2]) and DESIG.match(m[2])[1].lower() in COMP:
        lead, s = m[1], m[2]
    des = None
    if s.lower() not in COMP:
        m = DESIG.match(s)
        if not (m and m[1].lower() in COMP):
            return None
        s, des = m[1], m[2]
    if lead:
        des = lead if des is None else f"{lead} {des}"
    return COMP[s.lower()], des


def translate(text):
    """Russian for one cleaned English description, or None when the glossaries don't cover it."""
    if text in MANUAL:
        return MANUAL[text]
    for rx, ru in FAMILIES:
        m = re.match(rx, text, re.I)
        if m:
            return m.expand(ru)
    s = text
    quals = []
    for _ in range(3):
        for rx, f in QUAL_RE:
            m = rx.search(s)
            if m:
                quals.insert(0, f(m))
                s = s[: m.start()].rstrip(" -,")
                break
        else:
            break
    if s in MANUAL:
        base = MANUAL[s]
        if quals:
            head, _, tail = base.partition(":")
            q = ", ".join(quals)
            if ", цепь" in head:
                a, _, b = head.partition(", цепь")
                head = f"{a}, {q}, цепь{b}"
            else:
                head = f"{head}, {q}"
            return f"{head}:{tail}" if tail else head
        return base
    # "Cylinder N …" in front: the part of that cylinder
    cyl = None
    m = re.match(r"^Cylinder (\d+) (.+)$", s)
    if m:
        cyl, s = m[1], m[2]
        for en, ru in P34_PARTS.items():
            if s.startswith(en):
                rest = s[len(en):].strip()
                rest, fault = _suffix(rest, FAULTS)
                circ = CIRCUITS.get(rest.lower()) if rest else None
                if rest and circ is None:
                    return None
                return f"Цилиндр {cyl}, {ru}" + (f", {circ}" if circ else "") + f": {fault or 'неисправность'}"
    s = s.replace(" - ", " ")
    # the longest fault, then the longest circuit part, that leave a known component
    found = None
    for rest, fault in _suffixes(s, FAULTS) + [(s, None)]:
        if not rest:
            continue
        # the whole rest as a component first ("Throttle Actuator Control"), then with a circuit part taken off
        for r2, circ in [(rest, None)] + _suffixes(rest, CIRCUITS):
            c = _component(r2) if r2 else None
            if c:
                found = (c, fault, circ)
                break
        if found:
            break
    if not found:
        return None
    (comp, des), fault, circ = found
    ru = comp
    if cyl:
        ru += f" цилиндра {cyl}"
    if des and "/" in des and fault in ("рассогласование сигналов", "рассогласование напряжений"):
        # two sensors compared: "Датчики … A и B"
        for one, many in (("Датчик ", "Датчики "), ("Выключатель ", "Выключатели "), ("Датчик/выключатель ", "Датчики/выключатели ")):
            if ru.startswith(one):
                ru = many + ru[len(one):]
                break
        des = des.replace("/", " и ")
    if des:
        ru += f" {des}"
    if quals:
        ru += ", " + ", ".join(quals)
    if circ:
        ru += f", {circ}"
    return f"{ru}: {fault or 'неисправность'}"


def fetch(url, path, offline):
    if os.path.exists(path):
        return
    if offline:
        sys.exit(f"missing {path} (offline)")
    print("download", url)
    with urllib.request.urlopen(url, timeout=60) as r:
        data = r.read()
    with open(path, "wb") as f:
        f.write(data)


def load_sources(dl):
    """codes.json → {code: text}; the few lines where the code and text got mixed up are split again."""
    raw = json.load(open(os.path.join(dl, "fabio.json"), encoding="utf-8"))
    out = {}
    for d in raw:
        c = d["Code"].strip()
        t = d["Description"]
        m = re.match(r"^([PCBU][0-9A-F]{4})(.*)$", c, re.S)
        if not m:
            continue
        if m[2].strip():
            # "P2765/Turbine" + "Speed Sensor…", "P0601\nInternal" + "Control Module…", "P340A," (a range: dropped)
            if m[2].strip().startswith(","):
                continue
            t = m[2].strip(" /\n") + " " + t
        out.setdefault(m[1], t)
    p0 = {}
    with open(os.path.join(dl, "mytrile.csv"), encoding="utf-8", errors="replace") as f:
        for row in csv.reader(f):
            if row and row[0].startswith("P0"):
                p0[row[0].strip()] = row[1].strip()
    return out, p0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dl", required=True, help="cache dir for the downloads")
    ap.add_argument("--offline", action="store_true")
    a = ap.parse_args()
    os.makedirs(a.dl, exist_ok=True)
    fetch(FABIO_URL, os.path.join(a.dl, "fabio.json"), a.offline)
    fetch(MYTRILE_URL, os.path.join(a.dl, "mytrile.csv"), a.offline)
    src, p0 = load_sources(a.dl)
    for c, t in p0.items():
        src.setdefault(c, t)

    ssm = {}
    for o in json.load(open(SSM, encoding="utf-8")):
        if o.get("ru"):
            ssm.setdefault(o["code"], o["ru"])

    codes, missing = {}, []
    for code in sorted(src):
        if code in DROP or not re.match(r"^P(0|2|34)", code):
            continue
        text = FIXES.get(code) or fix_text(code, src[code])
        if "Reserved" in text or "ISO/SAE" in text:
            continue
        ru = RU_BY_CODE.get(code) or (ssm[code] if code in SSM_REUSE and code in ssm else translate(text))
        if ru is None:
            missing.append((code, text))
            continue
        codes[code] = [text, ru]
    for code, (en, ru) in {**U_CODES, **C_CODES}.items():
        codes[code] = [en, ru]
    if missing:
        print(f"{len(missing)} without Russian:", file=sys.stderr)
        for c, t in missing:
            print(f"  {c} {t}", file=sys.stderr)
        sys.exit(1)

    with open(OUT, "w", encoding="utf-8") as f:
        f.write('{"source": ' + json.dumps(SOURCE_NOTE, ensure_ascii=False) + ', "codes": {\n')
        items = sorted(codes.items())
        for i, (c, v) in enumerate(items):
            f.write(json.dumps(c) + ": " + json.dumps(v, ensure_ascii=False) + ("," if i < len(items) - 1 else "") + "\n")
        f.write("}}\n")
    by = {}
    for c in codes:
        by[c[:2]] = by.get(c[:2], 0) + 1
    print(f"{len(codes)} codes -> {OUT}: " + ", ".join(f"{k} {v}" for k, v in sorted(by.items())))


if __name__ == "__main__":
    main()

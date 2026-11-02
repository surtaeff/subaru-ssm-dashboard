#!/bin/sh
# Builds the big panels' firmware into ../bin/usbscreen-<id>.bin (one image for address 0) and writes ../bin/manifest-big.json
# with the sources' hash, so a unit test fails when the sketch changes and the files were not rebuilt. The small boards'
# images and manifest are ../build-bin.sh's and are not touched here.
# The P4 has two silicon generations with incompatible images: <id>.bin for revision 3.00 and newer, <id>-pre3.bin for older.
set -e
cd "$(dirname "$0")"
CORE=esp32:esp32@3.3.10
URL=https://espressif.github.io/arduino-esp32/package_esp32_index.json
arduino-cli core install "$CORE" --additional-urls "$URL" >/dev/null
arduino-cli lib install "GFX Library for Arduino@1.6.9" >/dev/null
arduino-cli lib install "JPEGDEC@1.8.5" >/dev/null
S3="esp32:esp32:esp32s3:USBMode=hwcdc,CDCOnBoot=cdc,FlashSize=4M,PartitionScheme=default,PSRAM=opi"
P4FS="USBMode=hwcdc,CDCOnBoot=cdc,FlashSize=4M,PartitionScheme=default,PSRAM=enabled"
P4HS="USBMode=default,CDCOnBoot=cdc,FlashSize=4M,PartitionScheme=default,PSRAM=enabled"
OUT=$(mktemp -d)
cp big_board.h "$OUT/keep"
trap 'cp "$OUT/keep" big_board.h; rm -r "$OUT"' EXIT
one() { # <board> <file name> <fqbn>
  printf '// The board this build is for (see big_boards.h); build-big.sh writes this file for each one in turn.\n#define SCREEN_%s\n' "$1" > big_board.h
  arduino-cli compile --fqbn "$3" --output-dir "$OUT/$2" . >/dev/null
  python3 -c "import sys; d=open(sys.argv[1],'rb').read().rstrip(b'\\xff'); n=(len(d)+4095)//4096*4096; open(sys.argv[2],'wb').write(d+b'\\xff'*(n-len(d)))" \
    "$OUT/$2/bigscreen.ino.merged.bin" "../bin/usbscreen-$2.bin"
  echo "$2 $(wc -c < "../bin/usbscreen-$2.bin") bytes"
}
one WS_S3_LCD_43 ws_s3_lcd_43 "$S3"
one WS_S3_LCD_5 ws_s3_lcd_5 "$S3"
one WS_S3_LCD_7 ws_s3_lcd_7 "$S3"
one WS_P4_7B ws_p4_7b "esp32:esp32:esp32p4:ChipVariant=postv3,$P4FS"
one WS_P4_7B ws_p4_7b-pre3 "esp32:esp32:esp32p4:ChipVariant=prev3,$P4FS"
one WS_P4_101 ws_p4_101 "esp32:esp32:esp32p4:ChipVariant=postv3,$P4HS"
one WS_P4_101 ws_p4_101-pre3 "esp32:esp32:esp32p4:ChipVariant=prev3,$P4HS"
cp "$OUT/keep" big_board.h
python3 - "$CORE" <<'PY'
import hashlib, json, sys
def sha(b): return hashlib.sha256(b).hexdigest()
src = sha(open("bigscreen.ino", "rb").read() + open("big_boards.h", "rb").read())
names = ["ws_s3_lcd_43", "ws_s3_lcd_5", "ws_s3_lcd_7", "ws_p4_7b", "ws_p4_7b-pre3", "ws_p4_101", "ws_p4_101-pre3"]
files = {}
for n in names:
    b = open(f"../bin/usbscreen-{n}.bin", "rb").read()
    files[n] = {"sha256": sha(b), "bytes": len(b)}
json.dump({"src_sha256": src, "core": sys.argv[1], "files": files}, open("../bin/manifest-big.json", "w"), indent=1)
PY

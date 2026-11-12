# Exact unmodified original SVGs from SG_Delivery_21_Yellow_Frameless_GPS_Icons.zip.
# Source is losslessly compressed; rasterize original 2048px and downsample
# to 352px (88dp at 4x density). No changes to shapes, color or proportions.
import base64, gzip, json, sys
from pathlib import Path
from io import BytesIO
import cairosvg
from PIL import Image

SOURCE = "H4sIAAAAAAAC/+2a32/iRhDH/5URfblIjtmd/eU9pZHSVFEf0oe85OEU6UQvDtAQGxknXFX1f+/sLgGbYnq1rthIkIDZXWOWnc9+Z2bXfw6y0dvnUVFM39J48TYefBxc0AG+vsyyxY8Pg0lZzj8Oh8vlMl6KOC/GQ2SMDemUhwEsp4/lhE5CJhMqTtLpeFJuym/TdPlT/pUqGDDgzD8fBpcXYyiLUbZ4yosXavTvZ6My/aAYKHYGiy+jWfqBxVZphdzIM9iccq5YzFYPOJfJunDmLjwflRN4ms5mdNkfbm5+Rn5D/XAV58XrLKXa9C3N8sdHqn2k0q/0jVbCtURIELgBpdyrUHAlBNA/dZn+EuGqrulA7Spx54YPfgJ3BWRwxemDyp/ufoP7366isz49DIaXF8MxPWkALweRH/z067T8PEufyn6NP48Z59JKVLXxF7Yy/mQM9Y/x9wOraUQTuOdsNf5Ul+VZSqVFWeTPac1Aoer8/efoTdVsmqVfRnOqLPLX7LHe8Hs+zTYtwx1frzXcaW8saUEyuEVFdmjRJY6t+5TP/hjnGcypoXQW5dwNYOTJBZHEHFXECWplCL1YUpOQsRR208vtXu3rKP5Ld3bCVzhujoI+zffQ13Rh993rC5/ThD6xejysbvFKxn3up1i+k1jFVeo1oVD1XFsAuksh3JJ/4aYbBqkHjkFyb3crACVIQQzSMNhuGfTwRSYgKOPEsIiwROodR3eIhHCVh5FLj18/5XIXf0rs4a+tXJ5o7TWtW8Q+p+m8h4JJ0b02TKA1dcG09ei+IbpU3EXidwZBWDASvAG7AFFuQPQIUiIhONwKbDc3viOIiJ5E6UkMeVLEfR1Qk3fdxCU7kG56Cnuomw0YKr4Hw9a6eYL2CKDdAvclLcZpP/UTteDK2D2rI7pJP4WCxMA9im4INNoTaDjNBg2afpcha8MddUtIt9SCgtIx1S2LQnn8/IBCEIQo8ElBRqV0GAENHPZTQf8ziG0V9ITtUWC7hW4xeulpBGr8I9lO2ROj3sG1TSl74lOQazpoBeTSCQVKipSAa+EzEiQmDKCEVrB+zyWc4EVVyEhsrKWJkMfS0FyKrdWRYLE+VPrsUeiniO1igdJnjk0stBWxEzmthcS1jn7LX/tHj9QotNJ1elQs1kpCStycy1okr+L2nCgQx/c9J/RLGu6QdMuBCW5DBxB0EiOXkaRwXBqwJibnH1GoLsShNGRNgd+86B0KQnPDkNediohXJAQUmteBSRCukETArjAw1mGwrnKV/qzOFt48q1jvEXLXyfuuBQt5VbCUm3ARhlxSVwqHwXQxGRXzfu5UCIVWcqwTmlQ31hrFymk/hcBoKAT229vav94p6w7Kh8C31Kq69l06aJYOawcsLL3amDsfi35ZVnOak+aQMPRz32AXDQr30NA28Dmx0zryWcyc0fuZRO30d6ay7yRNk7/TJqxEEhH0VkoXC1MmfSs0cNmxLxFh8TGsRFpvdeX3cdB4IOhwsF3Hlfn7mTjtsj/pR7P9W9+mcaLl2wWDhvV4dqlZ/aae5ujY+4+ObR06uNri1SE3EiLEnLxSOow0lK9F1s8oUzOUzNZTYlnVhYpIbEcK4Y4omuJutwwTUNq9/tK16cOCCK5uh2LV7alVScoDm76fMeUu2yuzx/atY8oTKd/oEV4dLseyblYJHlSTSCC5Aw33/l5tN6qrtYjEpf5wr7u+DyhYOQkEaFlNKWw1wfi/teKvvwE++ijb4i8AAA=="
originals = json.loads(gzip.decompress(base64.b64decode(SOURCE)))
assert len(originals) == 21
out = Path("app/src/main/res/drawable-nodpi")
out.mkdir(parents=True, exist_ok=True)
for filename, svg in sorted(originals.items()):
    assert filename.startswith("nav_") and filename.endswith(".svg")
    assert "#FFD21F" in svg and "2048" in svg
    raw = cairosvg.svg2png(bytestring=svg.encode(), output_width=2048, output_height=2048)
    image = Image.open(BytesIO(raw)).convert("RGBA").resize((352,352), Image.Resampling.LANCZOS)
    bounds = image.getchannel("A").getbbox()
    assert bounds and 0.85 <= (bounds[3]-bounds[1])/352 <= 0.95
    assert image.getpixel((0,0))[3] == 0
    target = out / filename.replace(".svg", ".png")
    buf = BytesIO()
    image.save(buf, format="PNG")
    if "--verify" in sys.argv:
        assert target.read_bytes() == buf.getvalue(), f"Asset mismatch: {target}"
    else:
        target.write_bytes(buf.getvalue())
    print(target.name, bounds)
assert len(list(out.glob("nav_*.png"))) == 21

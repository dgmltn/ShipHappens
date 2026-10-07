#!/usr/bin/env python3
"""
One-off generator for :geo's bundled assets. Run by hand; Gradle never runs it.

Sources (both public domain):
  - US Census Bureau Gazetteer, national places file (2024 vintage):
    https://www2.census.gov/geo/docs/maps-data/data/gazetteer/2024_Gazetteer/2024_Gaz_place_national.zip
  - Natural Earth 1:110m physical land:
    https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_land.geojson

Usage: python3 -I scripts/build-geo-assets.py <scratch-dir>
Writes geo/src/commonMain/composeResources/files/{places.bin,land110m.bin}.
"""
import io, json, os, re, struct, sys, unicodedata, urllib.request, zipfile

GAZ_URL = "https://www2.census.gov/geo/docs/maps-data/data/gazetteer/2024_Gazetteer/2024_Gaz_place_national.zip"
LAND_URL = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_land.geojson"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "geo", "src", "commonMain", "composeResources", "files")

# Gazetteer NAME carries the legal/statistical area description as a trailing word or two.
LSAD = re.compile(r"\s+(city and borough|consolidated government|metropolitan government|metro government|"
                  r"unified government|urban county|municipality|borough|city|town|township|village|CDP|"
                  r"comunidad|zona urbana)$", re.I)
BALANCE = re.compile(r"\s+\(balance\)$", re.I)
CDP_CODE = "57"


def fetch(url, scratch):
    path = os.path.join(scratch, os.path.basename(url))
    if not os.path.exists(path):
        urllib.request.urlretrieve(url, path)
    return path


def fold_accents(text):
    decomposed = unicodedata.normalize("NFKD", text)
    return "".join(c for c in decomposed if not unicodedata.combining(c))


def clean(name, lsad_code=""):
    name = fold_accents(name).strip()
    balanced = bool(BALANCE.search(name))
    name = BALANCE.sub("", name)
    # Code 00 rows are named by their bare legal name ("Carson City"), so a trailing "City" is part
    # of it; only consolidated governments listed as "(balance)" still carry a descriptor to drop.
    if lsad_code != "00" or balanced:
        name = LSAD.sub("", name)
    return re.sub(r"\s+", " ", name).upper().strip()


# Consolidated city-county governments carry no standard LSAD code.
CONSOLIDATED_CODES = {"00", "UG", "UC", "MG", "CG"}
# "X City city" names that people also write as just "X"; a general rule would invent "KANSAS, MO".
CITY_SUFFIX_ALLOW = {"BOISE CITY, ID": "BOISE"}


def aliases(name, state, lsad_code):
    """Spellings people use that the Gazetteer's legal name doesn't match."""
    out = set()
    if lsad_code in CONSOLIDATED_CODES and re.search(r"[-/]", name):
        head = re.sub(r"\s+COUNTY$", "", re.split(r"[-/]", name)[0])
        if head and head != name:
            out.add(head)
    if name.startswith("URBAN "):
        out.add(name[len("URBAN "):])
    if f"{name}, {state}" in CITY_SUFFIX_ALLOW:
        out.add(CITY_SUFFIX_ALLOW[f"{name}, {state}"])
    for n in list(out | {name}):
        out.update(saint_spellings(n))
    for n in list(out | {name}):
        if "-" in n:
            out.add(re.sub(r"\s+", " ", n.replace("-", " ")).strip())
    out.discard(name)
    return out


SAINT_FORMS = ("ST.", "ST", "SAINT")


def saint_spellings(name):
    """Every ST./ST/SAINT spelling of the words in name, at any word position."""
    variants = {()}
    for word in name.split(" "):
        forms = SAINT_FORMS if word in SAINT_FORMS else (word,)
        variants = {v + (f,) for v in variants for f in forms}
    return {" ".join(v) for v in variants}


def places(scratch):
    with zipfile.ZipFile(fetch(GAZ_URL, scratch)) as z:
        name = next(n for n in z.namelist() if n.endswith(".txt"))
        text = z.read(name).decode("utf-8")
    lines = text.splitlines()
    header = [h.strip() for h in lines[0].split("\t")]
    i_usps, i_name, i_lsad = header.index("USPS"), header.index("NAME"), header.index("LSAD")
    i_land = header.index("ALAND")
    i_lat, i_lng = header.index("INTPTLAT"), header.index("INTPTLONG")
    need = max(i_usps, i_name, i_lsad, i_land, i_lat, i_lng)
    best = {}  # key -> (priority, (lat, lng)); real places beat aliases, non-CDPs beat CDPs, then larger land area
    for line in lines[1:]:
        cols = [c.strip() for c in line.split("\t")]
        if len(cols) <= need:
            continue
        city = clean(cols[i_name], cols[i_lsad])
        state = cols[i_usps]
        coord = (float(cols[i_lat]), float(cols[i_lng]))
        land = int(cols[i_land] or 0)
        candidates = [(city, 1)] + [(a, 0) for a in aliases(city, state, cols[i_lsad])]
        for n, real in candidates:
            key = f"{n}, {state}"
            priority = (real, cols[i_lsad] != CDP_CODE, land)
            if key not in best or priority > best[key][0]:
                best[key] = (priority, coord)
    return {k: v[1] for k, v in best.items()}


# Gazetteer internal points sit far from the downtown carriers mean for these (SF's lands in the
# Farallon Islands), plus names carriers use that are not Census places.
COORD_OVERRIDES = {
    "SAN FRANCISCO, CA": (37.7749, -122.4194),
    "ANCHORAGE, AK": (61.2181, -149.9003),
}
SUPPLEMENT = {
    "BROOKLYN, NY": (40.6782, -73.9442),
    "BRONX, NY": (40.8448, -73.8648),
    "QUEENS, NY": (40.7282, -73.7949),
    "STATEN ISLAND, NY": (40.5795, -74.1502),
    "FLUSHING, NY": (40.7675, -73.8331),
    "JAMAICA, NY": (40.7027, -73.7890),
    "LONG ISLAND CITY, NY": (40.7447, -73.9485),
}


def adjust(table):
    table.update(COORD_OVERRIDES)
    for key, coord in SUPPLEMENT.items():
        table.setdefault(key, coord)
    if "INDUSTRY, CA" in table:
        table.setdefault("CITY OF INDUSTRY, CA", table["INDUSTRY, CA"])
    return table


def write_places(table):
    buf = io.BytesIO()
    buf.write(b"SHPL"); buf.write(struct.pack(">ii", 1, len(table)))
    for key in sorted(table, key=lambda k: k.encode("utf-8")):
        kb = key.encode("utf-8"); lat, lng = table[key]
        buf.write(struct.pack(">H", len(kb))); buf.write(kb); buf.write(struct.pack(">ff", lat, lng))
    return buf.getvalue()


def write_land(scratch):
    with open(fetch(LAND_URL, scratch)) as f:
        gj = json.load(f)
    rings = []
    for feat in gj["features"]:
        g = feat["geometry"]
        polys = [g["coordinates"]] if g["type"] == "Polygon" else g["coordinates"]
        for poly in polys:
            rings.append(poly[0])  # outer ring only; 110m land has no lakes worth cutting out
    buf = io.BytesIO()
    buf.write(b"SHLD"); buf.write(struct.pack(">ii", 1, len(rings)))
    for ring in rings:
        buf.write(struct.pack(">i", len(ring)))
        for lng, lat in ring:
            buf.write(struct.pack(">ff", lng, lat))
    return buf.getvalue()


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    scratch = sys.argv[1]
    os.makedirs(scratch, exist_ok=True); os.makedirs(OUT, exist_ok=True)
    table = adjust(places(scratch))
    with open(os.path.join(OUT, "places.bin"), "wb") as f: f.write(write_places(table))
    with open(os.path.join(OUT, "land110m.bin"), "wb") as f: f.write(write_land(scratch))
    print(f"places: {len(table)} keys; wrote {OUT}")


if __name__ == "__main__":
    main()

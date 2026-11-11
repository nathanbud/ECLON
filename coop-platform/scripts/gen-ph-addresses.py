"""Builds the Philippine address list the address fields choose from, from the PSA's Philippine Standard Geographic Code (PSGC) as published at psgc.gitlab.io.

Writes apps/desktop/public/ph-addresses/all.json, one compact file because the address is picked from the barangay first:
  {"p": [province names], "c": [[city or municipality, index into p], ...], "b": [[barangay, index into c], ...]}
Provinces include Metro Manila and the independent cities that belong to no province. Every list is sorted by name.
Run it again to refresh the list after the PSA publishes a new quarter:  python scripts/gen-ph-addresses.py
"""
import json
import os
import sys
import urllib.request

BASE = 'https://psgc.gitlab.io/api/'
OUT = os.path.join(os.path.dirname(__file__), '..', 'apps', 'desktop', 'public', 'ph-addresses')
NCR = '130000000'


def get(name):
    with urllib.request.urlopen(BASE + name, timeout=120) as r:
        return json.load(r)


def main():
    regions = {r['code']: r for r in get('regions.json')}
    provinces = get('provinces.json')
    cities = get('cities-municipalities.json')
    sub = get('sub-municipalities.json')
    barangays = get('barangays.json')

    # a "province" bucket for each province, plus Metro Manila and any city that belongs to neither
    buckets = {p['code']: p['name'] for p in provinces}
    buckets[NCR] = 'Metro Manila'

    def bucket_of(c):
        if c.get('provinceCode'):
            return c['provinceCode']
        if c.get('regionCode') == NCR or c.get('districtCode'):
            return NCR
        code = c['regionCode']
        buckets.setdefault(code, regions[code]['name'])
        return code

    by_bucket = {}
    city_bucket = {}
    for c in cities:
        b = bucket_of(c)
        city_bucket[c['code']] = (b, c['name'])
        by_bucket.setdefault(b, {})[c['code']] = {'n': c['name'], 'b': []}
    sub_parent = {s['code']: s.get('cityCode') for s in sub}

    placed = lost = 0
    for g in barangays:
        parent = g.get('cityCode') or g.get('municipalityCode') or sub_parent.get(g.get('subMunicipalityCode'))
        if not parent or parent not in city_bucket:
            lost += 1
            continue
        by_bucket[city_bucket[parent][0]][parent]['b'].append(g['name'])
        placed += 1

    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        os.remove(os.path.join(OUT, f))
    codes = sorted((c for c in buckets if c in by_bucket), key=lambda c: buckets[c].lower())
    p_index = {c: i for i, c in enumerate(codes)}
    city_rows = sorted(((v['n'], p_index[bk], cc, v) for bk, d in by_bucket.items() if bk in p_index for cc, v in d.items()), key=lambda r: (r[0].lower(), r[1]))
    c_index = {r[2]: i for i, r in enumerate(city_rows)}
    brgy_rows = []
    for name, _, cc, v in city_rows:
        for n in set(v['b']):
            brgy_rows.append([n, c_index[cc]])
    brgy_rows.sort(key=lambda r: (r[0].lower(), r[1]))
    out = {'p': [buckets[c] for c in codes], 'c': [[r[0], r[1]] for r in city_rows], 'b': brgy_rows}
    with open(os.path.join(OUT, 'all.json'), 'w', encoding='utf-8') as fh:
        json.dump(out, fh, ensure_ascii=False, separators=(',', ':'))
    print(f'{len(out["p"])} provinces, {len(out["c"])} cities and municipalities, {len(brgy_rows)} barangays ({placed} placed, {lost} without a city)', file=sys.stderr)


if __name__ == '__main__':
    main()

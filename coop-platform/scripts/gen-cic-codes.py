"""
Turns the code lists of the CIC's CSDF workbook (the Excel file the CIC supplies with its data format) into one tab-separated resource the backend loads:

    python scripts/gen-cic-codes.py <path to the workbook> [output]

Each list in the workbook is a small block: a name cell ("TITLE DOMAIN", "GenderDomain"), a "Value" / "Description" header row under it, then the values down to the first
empty cell. The block can sit anywhere on a sheet and several sit side by side. The output has one line per value: sheet, domain, value, description. The country list is the
one block with a different header ("Code (K)", "Description (en-US)"); it is read as domain COUNTRY.

Run it again when the CIC sends a new workbook and commit the new file; the backend reads it as it is. Needs openpyxl (a development tool, not part of the app).
"""
import re
import sys
import warnings

warnings.filterwarnings('ignore')
import openpyxl  # noqa: E402

HEADER_VALUE = {'value', 'code (k)'}
DEFAULT_OUT = 'backend/coop-core/src/main/resources/cic/csdf-codes.tsv'


def text(v):
    if v is None:
        return ''
    if isinstance(v, float) and v.is_integer():
        v = int(v)
    return str(v).strip().replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')


def sheet_code(title):
    """'ID - Individual' -> 'ID', 'CC- Credit Card ' -> 'CC': the record type the sheet is about."""
    return re.split(r'[\s-]', title.strip(), maxsplit=1)[0].upper()


def blocks(ws):
    # read the sheet into memory once: cell-by-cell access is far too slow for the 2,500-row sheets
    grid = [[text(v) for v in row] for row in ws.iter_rows(values_only=True)]
    height = len(grid)
    width = max((len(r) for r in grid), default=0)

    def at(r, c):
        return grid[r][c] if 0 <= r < height and 0 <= c < len(grid[r]) else ''

    for r in range(height):
        for c in range(width):
            if at(r, c).lower() not in HEADER_VALUE:
                continue
            if not at(r, c + 1).lower().startswith('description'):
                continue
            # the name is the nearest non-empty text above the header, in the same column
            name = ''
            for up in range(r - 1, -1, -1):
                t = at(up, c)
                if t and t.lower() not in HEADER_VALUE:
                    name = t
                    break
            if at(r, c).lower() == 'code (k)':
                name = 'COUNTRY'
            values = []
            rr = r + 1
            while rr < height:
                v, d = at(rr, c), at(rr, c + 1)
                # the next list starts here: a header row, or a name cell with a header row under it
                if v.lower() in HEADER_VALUE or (v and at(rr + 1, c).lower() in HEADER_VALUE):
                    break
                # a value may have no code ("No info"), and a list may have gaps (blank rows between groups of values)
                if v or d:
                    values.append((v, d))
                rr += 1
            if values:
                yield name, values


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    wb = openpyxl.load_workbook(sys.argv[1], data_only=True)
    out = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT
    lines = []
    seen = set()
    for ws in wb.worksheets:
        if ws.title.lower() == 'template':
            continue
        code = sheet_code(ws.title)
        for name, values in blocks(ws):
            domain = re.sub(r'\s+', '', name)
            key = (code, domain)
            if key in seen:
                continue
            seen.add(key)
            done = set()
            for v, d in values:
                if (v, d) in done:  # a list can be repeated further down its column; keep it once
                    continue
                done.add((v, d))
                lines.append(f'{code}\t{domain}\t{v}\t{d}')
    with open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    domains = sorted(seen)
    print(f'{len(lines)} values in {len(domains)} lists -> {out}')
    for code, domain in domains:
        n = sum(1 for l in lines if l.startswith(f'{code}\t{domain}\t'))
        print(f'  {code:3} {domain:34} {n}')


if __name__ == '__main__':
    main()

"""Copy tables from the legacy MySQL database into the PostgreSQL `legacy` schema.

Usage: MYSQL_PWD=<coop_app password> python -I load-legacy-to-postgres.py table [table ...]
Each table is truncated in PostgreSQL, then streamed in with copy. See
documentation/postgres-legacy-load.md.
"""
import sys, os, io, csv, subprocess, datetime, decimal, pymysql
NULL = "~~NULL~~"
pw = os.environ["MYSQL_PWD"]
my = pymysql.connect(host="127.0.0.1", port=3311, user="coop_app", password=pw,
                     database="iaccsbsucmpc01", charset="utf8mb4", cursorclass=pymysql.cursors.SSCursor,
                     read_timeout=3600, conv={**pymysql.converters.conversions})
def pg(sql, stdin=None):
    r = subprocess.run(["docker","exec","-i","eclon-postgres","psql","-U","coop_admin","-d","coop","-v","ON_ERROR_STOP=1","-At","-c",sql],
                       input=stdin, capture_output=True)
    if r.returncode: raise RuntimeError(r.stderr.decode()[:500])
    return r.stdout.decode().strip()
def fmt(v):
    if v is None: return NULL
    if isinstance(v, (bytes, bytearray)): return chr(92) + "x" + v.hex()
    if isinstance(v, datetime.timedelta): return str(v)
    if isinstance(v, bool): return "1" if v else "0"
    return v
def load(t):
    cols = [r[0] for r in (lambda c:(c.execute("show columns from `%s`"%t), c.fetchall())[1])(my.cursor())]
    types = dict(l.split("|") for l in pg("select column_name||'|'||data_type from information_schema.columns where table_schema='legacy' and table_name='%s'"%t.lower()).splitlines())
    byl = {c.lower(): c for c in types}  # PostgreSQL may keep case (e.g. a name starting with a digit)
    lc = [byl.get(c.lower(), c.lower()) for c in cols]
    pg("truncate legacy.\"%s\""%t.lower())
    cur = my.cursor(); cur.execute("select * from `%s`"%t)
    total = 0
    cmd = ["docker","exec","-i","eclon-postgres","psql","-U","coop_admin","-d","coop","-v","ON_ERROR_STOP=1","-c",
           "\\copy legacy.\"%s\" (%s) from stdin with (format csv, null '%s')"%(t.lower(), ",".join('"%s"'%c for c in lc), NULL)]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=subprocess.PIPE, stdout=subprocess.DEVNULL)
    w = io.TextIOWrapper(p.stdin, encoding="utf-8", newline="")
    cw = csv.writer(w, lineterminator="\n")
    while True:
        rows = cur.fetchmany(20000)
        if not rows: break
        for r in rows:
            out = []
            for c, v in zip(lc, r):
                v = fmt(v)
                if isinstance(v, str) and v.startswith("0000-00-00"): v = NULL
                if isinstance(v, str) and "\x00" in v: v = v.replace("\x00","")
                out.append(v)
            cw.writerow(out)
        total += len(rows)
    w.close(); err = p.stderr.read().decode(); p.wait()
    if p.returncode: raise RuntimeError(t + ": " + err[:500])
    return total
if __name__ == "__main__":
    for t in sys.argv[1:]:
        try: print(t, load(t), flush=True)
        except Exception as e: print("FAIL", t, e, flush=True)

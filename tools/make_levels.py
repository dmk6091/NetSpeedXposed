#!/usr/bin/env python3
"""Turn india_dams_positions_levels_rivers_flow.csv into data/dams_levels.txt, the file the app downloads every day.
Usage: python3 tools/make_levels.py <csv> [out]. One line per dam that has a level: name|state|pct|basis|date(ISO)|source|inflow|outRiver|outCanal"""
import csv, re, sys
src = sys.argv[1]; out = sys.argv[2] if len(sys.argv) > 2 else 'data/dams_levels.txt'
def clean(s): return re.sub(r'[|;\\"\n\r]', ' ', s or '').strip()
def iso(d):
    d = (d or '').strip(); m = re.match(r'(\d\d)/(\d\d)/(\d{4})$', d)
    return f'{m.group(3)}-{m.group(2)}-{m.group(1)}' if m else d
def num(x, n):
    try: return ('%.*f' % (n, float(x))).rstrip('0').rstrip('.') if x not in ('', None) else ''
    except Exception: return ''
n = 0
with open(out, 'w') as f:
    f.write('# name|state|pct|basis|date|source|inflow|outRiver|outCanal\n')
    for r in csv.DictReader(open(src, encoding='utf-8-sig')):
        if not r['pct_full']: continue
        f.write('|'.join([clean(r['name']), clean(r['state']), num(r['pct_full'], 1), clean(r['pct_basis']), iso(r['level_date']),
                          clean(r['level_source']), num(r['inflow_cusecs'], 0), num(r['outflow_river_cusecs'], 0), num(r['outflow_canal_cusecs'], 0)]) + '\n')
        n += 1
print(n, 'dams ->', out)

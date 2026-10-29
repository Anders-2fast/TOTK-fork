"""Where each module's imports resolve: another module of the dump, a host implementation in runtime/, or nothing.

Usage: imports.py <exefs> [--module main] [--list unresolved|host|all] [--loaded main,sdk]

runtime/loader.c binds an import to a host implementation first (HLE_FUNC / NVN in runtime/), then to an export of a
loaded module (--loaded: the modules the loader loads, default main,sdk), else to a host stub that stops the game
when called. This shows that for every import, and which module of the dump exports it (subsdk0-3 are not loaded:
their exports have to be host implementations).
"""
import argparse
import collections
import os
import re
import sys

from nso import Nso

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
MODULES = ['main', 'sdk', 'subsdk0', 'subsdk1', 'subsdk2', 'subsdk3']


def host_impls():
    names = set()
    pat = re.compile(r'HLE_FUNC\("([^"]+)"|^(?:NVN|PLAIN)\(([A-Za-z_0-9]+)', re.M)
    for d, _, files in os.walk(os.path.join(ROOT, 'runtime')):
        for f in files:
            if f.endswith(('.c', '.m', '.mm', '.cpp')):
                for m in pat.finditer(open(os.path.join(d, f), encoding='utf-8', errors='replace').read()):
                    names.add(m.group(1) or m.group(2))
    return names


def demangle_ns(name):
    """nn::os::... -> 'nn::os' (a rough namespace for grouping mangled names)."""
    m = re.match(r'_ZN(K?)((?:\d+[A-Za-z_]\w*?)+?)(?=\d|E|C|D)', name)
    if not name.startswith('_ZN'):
        return name.split('_')[0] if name.startswith('nvn') else '(C)'
    parts, s = [], name[3:].lstrip('K')
    while s and s[0].isdigit():
        n = int(re.match(r'\d+', s).group())
        s = s[len(str(n)):]
        parts.append(s[:n])
        s = s[n:]
        if len(parts) == 2:
            break
    return '::'.join(parts) if parts else name


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('exefs')
    ap.add_argument('--module', default='main')
    ap.add_argument('--list', choices=('unresolved', 'host', 'all'))
    ap.add_argument('--loaded', default='main,sdk')
    args = ap.parse_args()

    mods = {n: Nso(os.path.join(args.exefs, n)) for n in MODULES if os.path.exists(os.path.join(args.exefs, n))}
    exports = {n: {name for name, _, shndx, _, _ in m.syms if shndx} for n, m in mods.items()}
    loaded = args.loaded.split(',')
    host = host_impls()
    m = mods[args.module]
    imports = sorted({name for name, _, shndx, _, _ in m.syms if not shndx and name})

    rows = []
    for name in imports:
        owner = next((n for n in MODULES if n in exports and n != args.module and name in exports[n]), None)
        if name in host:
            how = 'host'
        elif owner in loaded:
            how = 'module'
        else:
            how = 'unresolved'
        rows.append((how, owner or '-', name))

    by = collections.Counter((how, owner) for how, owner, _ in rows)
    print(f'{args.module}: {len(imports)} imports (NEEDED: {", ".join(os.path.basename(x) for x in m.needed)})')
    for (how, owner), n in sorted(by.items()):
        print(f'  {how:10} from {owner:8} {n:5}')
    unres = [(owner, name) for how, owner, name in rows if how == 'unresolved']
    groups = collections.Counter((owner, demangle_ns(name)) for owner, name in unres)
    if groups:
        print('unresolved, by module and namespace:')
        for (owner, ns), n in sorted(groups.items(), key=lambda kv: (kv[0][0], -kv[1])):
            print(f'  {owner:8} {ns:40} {n:4}')
    if args.list:
        for how, owner, name in rows:
            if args.list == 'all' or how == args.list:
                print(f'{how:10} {owner:8} {name}')


if __name__ == '__main__':
    sys.exit(main())

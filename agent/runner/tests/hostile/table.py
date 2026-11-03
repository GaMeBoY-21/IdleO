#!/usr/bin/env python3
"""
The hostile tests' table, from the log of a run (run.sh; table_test.py). Sixteen rows, always: a test that stopped before it
reached its verdict (a panic, a timeout, nothing at all) has no HOSTILE line, and it is a FAIL with the reason it stopped,
because an attack that never ran is not an attack refused. Exits non-zero unless all sixteen passed.
"""
import re
import sys

ROWS = 16
HOSTILE = re.compile(r"HOSTILE (\d+) ([^:]*): (PASS|FAIL) ?(.*)$")
PANIC = re.compile(r"thread 'h(\d+)_(\w+)'.* panicked at .*:$")


def rows(log):
    lines = log.splitlines()
    found = {}
    for line in lines:
        if match := HOSTILE.search(line):
            number, name, result, detail = match.groups()
            found[int(number)] = (name, result, detail)
    stopped = {}
    for at, line in enumerate(lines):
        if (match := PANIC.search(line)) and int(match[1]) not in found:
            reason = lines[at + 1].strip() if at + 1 < len(lines) else ""
            stopped[int(match[1])] = (match[2].replace("_", " "), "FAIL", f"no verdict: {reason}")
    return {
        number: found.get(number) or stopped.get(number) or ("(no test ran)", "FAIL", "no verdict: the test never reported")
        for number in range(1, ROWS + 1)
    }


def main():
    table = rows(open(sys.argv[1], errors="replace").read())
    print("\n| # | Attempt | Result |\n| --- | --- | --- |")
    for number, (name, result, detail) in table.items():
        print(f"| {number:02} | {name} | {f'{result} {detail}'.strip()} |")
    fails = sum(result != "PASS" for _, result, _ in table.values())
    print(f"\nhostile: {ROWS - fails} of {ROWS} rows held, {fails} FAIL")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()

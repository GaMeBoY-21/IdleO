#!/usr/bin/env python3
"""table.py: every row is accounted for, and only a row that reached its verdict and held passes (scripts/checks/agent.sh)."""
import os
import subprocess
import sys
import tempfile

TABLE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "table.py")


def run(log):
    with tempfile.NamedTemporaryFile("w", suffix=".log") as f:
        f.write(log)
        f.flush()
        done = subprocess.run([sys.executable, TABLE, f.name], capture_output=True, text=True)
    return done.returncode, done.stdout


def held(number):
    return f"test h{number:02}_x ... HOSTILE {number:02} attack {number}: PASS refused\nok\n"


def expect(condition, message, output):
    if not condition:
        sys.exit(f"table_test: FAIL: {message}\n{output}")


everything = "".join(held(n) for n in range(1, 17))
code, out = run(everything)
expect(code == 0 and "16 of 16 rows held, 0 FAIL" in out, "sixteen that held should pass", out)

# The shape of the runs that couldn't pull: the row that never reached report() has only its panic.
never_ran = "".join(held(n) for n in range(1, 17) if n != 4) + (
    "test h04_reach_the_home_network ... \n"
    "thread 'h04_reach_the_home_network' (26953) panicked at runner/tests/hostile/main.rs:275:9:\n"
    "the attack never ran: pulling the image failed: dial tcp 172.17.0.1:5000: connect: connection refused\n"
    "FAILED\n"
)
code, out = run(never_ran)
expect(code == 1, "a row that never ran must fail the run", out)
expect("| 04 | reach the home network | FAIL no verdict: the attack never ran" in out, "the row says why", out)
expect("15 of 16 rows held, 1 FAIL" in out, "it counts as a FAIL", out)

code, out = run("".join(held(n) for n in range(1, 16)))
expect(code == 1 and "| 16 | (no test ran) | FAIL no verdict" in out, "a row with no line at all must fail", out)

code, out = run(everything.replace("HOSTILE 09 attack 9: PASS refused", "HOSTILE 09 attack 9: FAIL BREACH: wrote it"))
expect(code == 1 and "| 09 | attack 9 | FAIL BREACH: wrote it |" in out, "a breach must fail", out)

code, out = run("")
expect(code == 1 and "0 of 16 rows held, 16 FAIL" in out, "an empty log must fail", out)
print("table_test: ok")

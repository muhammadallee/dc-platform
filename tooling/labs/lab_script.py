"""Turns a guided lab (docs/labs/<capability>/index.md) into one bash script, in the order the reader follows it.

Blocks it reads (any other block is prose for the reader):
  ```bash lab-step        a command to run
  ```bash lab-check       commands that must each exit 0; every line is checked on its own (no continuation lines)
  ```<lang> lab-file PATH the block's text is written to PATH (relative to the current directory at that point)

Usage: python -I -X utf8 lab_script.py <lab.md>   (the script goes to stdout)
"""
import re
import sys
from pathlib import Path

FENCE = re.compile(r"^```(\S*)[ \t]+(lab-step|lab-check|lab-file)[ \t]*(\S*)[ \t]*\n(.*?)^```[ \t]*$", re.M | re.S)


def main(path):
    text = Path(path).read_text(encoding="utf-8")
    out = []
    for number, match in enumerate(FENCE.finditer(text), start=1):
        kind, target, body = match.group(2), match.group(3), match.group(4)
        line = text.count("\n", 0, match.start()) + 1
        out.append(f'LAB_STEP="{number} ({kind}, line {line})"')
        out.append(f'echo "### step $LAB_STEP"')
        if kind == "lab-file":
            if not target:
                sys.exit(f"line {line}: lab-file needs a path")
            delimiter = f"LAB_FILE_{number}_EOF"
            if delimiter in body:
                sys.exit(f"line {line}: block contains its own delimiter")
            out.append(f'mkdir -p "$(dirname "{target}")"')
            out.append(f"cat > \"{target}\" <<'{delimiter}'")
            out.append(body.rstrip("\n"))
            out.append(delimiter)
        elif kind == "lab-check":
            for command in body.rstrip("\n").split("\n"):
                if not command.strip():
                    continue
                if command.rstrip().endswith("\\"):
                    sys.exit(f"line {line}: a lab-check line cannot continue onto the next")
                # Checked here, not by errexit: bash exempts the left of && / || and a negated command from it.
                out.append(command)
                out.append('lab_status=$?; [ "$lab_status" -eq 0 ] || lab_fail "$lab_status"')
        else:
            out.append(body.rstrip("\n"))
            out.append('lab_status=$?; [ "$lab_status" -eq 0 ] || lab_fail "$lab_status"')
    if not out:
        sys.exit("no lab-step, lab-check or lab-file blocks found")
    print("\n".join(out))


if __name__ == "__main__":
    main(sys.argv[1])

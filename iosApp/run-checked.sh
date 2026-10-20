#!/bin/sh
# Surface compiler/test errors in Check annotations as well as the full job log.
set -u
LOG="$(mktemp "${TMPDIR:-/tmp}/raven-ios-check.XXXXXX")"
trap 'rm -f "$LOG"' EXIT
"$@" > "$LOG" 2>&1
STATUS=$?
cat "$LOG"
if [ "$STATUS" -ne 0 ]; then
    python3 - "$LOG" <<'PY'
import collections,sys
errors=[]
tail=collections.deque(maxlen=30)
with open(sys.argv[1],errors='replace') as stream:
    for line in stream:
        tail.append(line.rstrip())
        if 'error:' in line.lower() or 'failed' in line.lower():
            if len(errors)<40: errors.append(line.rstrip())
messages=errors or ['\n'.join(tail)]
for message in messages:
    safe=message[:10000].replace('%','%25').replace('\r','%0D').replace('\n','%0A')
    print('::error title=iOS validation::'+safe)
PY
fi
exit "$STATUS"

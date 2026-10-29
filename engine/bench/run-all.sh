#!/usr/bin/env bash
## Starts one detached benchmark process per engine (parallel, separate CSVs); PIDs in logs/pids.
cd "$(dirname "$0")"
export PATH="$HOME/.local/bin:$PATH"
mkdir -p logs
: > logs/pids
for e in audiowmark audioseal wavmark silentcipher; do
  nohup .venv/bin/python -W ignore bench.py "$e" > "logs/$e.log" 2>&1 &
  echo "$e $!" >> logs/pids
done
cat logs/pids

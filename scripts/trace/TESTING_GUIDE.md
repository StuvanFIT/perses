# Reduction Trace — Tester Guide

This guide takes you from a fresh machine to a `reduction.json` file describing
every step Perses took while shrinking **your own program**.

You do not need to know anything about Perses internals to follow it.

---

## What you will end up with

A single JSON file containing:

| Part | What it holds |
|---|---|
| `summary` | totals — original/final size, reduction ratio, number of tests run, timings |
| `steps[]` | the **accepted** reduction steps, in order, each with a before/after diff |
| `candidates[]` | **every** program variant Perses tried, including the rejected ones |
| `programs{}` | the actual program text, stored once per unique program |

`steps[]` is the story of the reduction. `candidates[]` is the full audit trail.

---

## Before you start

You need a Linux machine (or WSL on Windows) with:

| Tool | Why | Check it with |
|---|---|---|
| `bash` 5.0+ | the wrapper uses a bash 5 feature for fast timestamps | `bash --version` |
| `java` 11+ | Perses runs on the JVM | `java -version` |
| `git` | to fetch the code | `git --version` |
| `curl` | to fetch the build tool | `curl --version` |
| `python3` | the merge step is a Python script | `python3 --version` |

Whatever your interestingness test needs (`gcc`, `clang`, `python`, …) must also
be installed. The bundled example only needs `gcc`.

You also need roughly **2 GB of free disk** and **20–30 minutes** for the first
build. Later builds take seconds.

> **A note on Java:** a *JRE* is not enough to build — you need a *JDK*. Step 3
> works around this without requiring admin rights, so don't worry if you're
> unsure which you have.

---

## Step 1 — Get the code

Clone the repository and enter it:

```bash
cd ~
git clone https://github.com/StuvanFIT/perses.git
cd perses
```

**What this does:** downloads the Perses source, including the tracing feature
(`--trace-ndjson-file`) and the three scripts in `scripts/trace/`.

Throughout this guide the repository lives at `/home/skai0008/PERSES/perses`.
**Substitute your own path everywhere you see it.** If you cloned into your home
directory as above, yours is `/home/YOUR_USERNAME/perses`.

Confirm the tracing scripts are present:

```bash
ls scripts/trace/
```

You should see `perses-trace.sh`, `perses-trace-wrapper.sh`,
`perses-trace-merge.py`, and an `example/` directory.

---

## Step 2 — Install the build tool (bazelisk)

Perses builds with Bazel. `bazelisk` is a small launcher that downloads the
exact Bazel version this project needs, so you never have to match versions by
hand.

```bash
mkdir -p ~/bin
curl -fsSL -o ~/bin/bazelisk \
  https://github.com/bazelbuild/bazelisk/releases/download/v1.25.0/bazelisk-linux-amd64
chmod +x ~/bin/bazelisk
export PATH="$HOME/bin:$PATH"
echo 'export PATH="$HOME/bin:$PATH"' >> ~/.bashrc
```

**Line by line:**

- `mkdir -p ~/bin` — creates a personal directory for programs. No admin rights needed.
- `curl -fsSL -o ...` — downloads the bazelisk binary into it.
- `chmod +x` — marks it executable.
- `export PATH=...` — lets you type `bazelisk` instead of the full path, **for this terminal only**.
- `echo ... >> ~/.bashrc` — makes that permanent for future terminals.

Verify:

```bash
bazelisk version
```

You should see `Bazelisk version: v1.25.0`. The first run also downloads Bazel
itself, so give it a minute.

---

## Step 3 — Point Bazel at a working Java compiler

Many systems ship a Java *runtime* (JRE) without the *compiler* (JDK). Bazel
needs the compiler. Rather than installing one system-wide, tell Bazel to fetch
its own:

```bash
cat >> ~/.bazelrc <<'EOF'

# Use Bazel's own JDK, so a JRE-only system still builds.
build --java_runtime_version=remotejdk_11
build --tool_java_runtime_version=remotejdk_11
test  --java_runtime_version=remotejdk_11
test  --tool_java_runtime_version=remotejdk_11
EOF
```

**What this does:** appends four settings to your personal Bazel config at
`~/.bazelrc`. They apply to every Bazel command you run from now on, so you never
have to pass these flags manually. This file is in your home directory, **not**
in the repository, so it won't interfere with the project's own settings.

**Skip this step only if** `javac -version` already prints a version. Applying it
anyway is harmless.

---

## Step 4 — Build Perses

```bash
cd /home/skai0008/PERSES/perses
bazelisk build //src/org/perses:perses_deploy.jar
```

**What this does:** compiles Perses and packages it, with all its dependencies,
into one runnable `.jar` file.

**This is the slow step — expect 20–30 minutes the first time.** Bazel downloads
the JDK, all third-party libraries, and generates parsers for every language
Perses supports. Later builds reuse that work and take seconds.

You will see a lot of scrolling output and some warnings about "obsolete source
value 8" — those are normal. You want the last lines to read:

```
Target //src/org/perses:perses_deploy.jar up-to-date:
  bazel-bin/src/org/perses/perses_deploy.jar
INFO: Build completed successfully
```

Confirm the jar exists:

```bash
ls -lh bazel-bin/src/org/perses/perses_deploy.jar
```

---

## Step 5 — Tell the scripts where the jar is

```bash
export PERSES_JAR=/home/skai0008/PERSES/perses/bazel-bin/src/org/perses/perses_deploy.jar
echo 'export PERSES_JAR=/home/skai0008/PERSES/perses/bazel-bin/src/org/perses/perses_deploy.jar' >> ~/.bashrc
```

**What this does:** stores the jar's location in an environment variable so the
run script can find it. The first line applies to the current terminal; the
second makes it permanent.

**Change `/home/skai0008/PERSES/perses` to wherever you cloned the repository.**
The path must be **absolute** — starting with `/`, not `~` or `./`.

Verify it points at a real file:

```bash
ls -lh "$PERSES_JAR"
```

If that errors, the variable is wrong. Re-run the export with the correct path.

---

## Step 6 — Do a test run with the bundled example

Before using your own program, confirm the whole pipeline works. The repository
ships a tiny C program and a matching test.

```bash
cd /home/skai0008/PERSES/perses
scripts/trace/perses-trace.sh \
    --input scripts/trace/example/t.c \
    --test  scripts/trace/example/my_test.sh \
    --out   /tmp/example-trace
```

**What the arguments mean:**

- `--input` — the program to shrink.
- `--test` — the script that decides whether a shrunken version is still "interesting".
- `--out` — a directory for the results. **It must not already exist**, or must be empty.

This takes about 20 seconds. The last line should read:

```
==> /tmp/example-trace/reduction.json
```

Check the result:

```bash
python3 -c "
import json
d = json.load(open('/tmp/example-trace/reduction.json'))
print('tokens: %s -> %s' % (d['summary']['originalTokens'], d['summary']['finalTokens']))
print('accepted steps:', d['summary']['acceptedSteps'])
print('candidates tried:', d['summary']['totalCandidates'])
"
```

Expected: `tokens: 75 -> 17`, 12 accepted steps, 111 candidates. Small
variation in the candidate count between runs is normal — Perses tests
candidates in parallel, so timing affects how many get cancelled.

**If this step works, everything is installed correctly.** If not, jump to
[Troubleshooting](#troubleshooting).

---

## Step 7 — Prepare your own program

Create a working folder and put your program in it. Example, for a user whose
program is `mybug.c`:

```bash
mkdir -p /home/skai0008/reduction-test
cd /home/skai0008/reduction-test
cp /path/to/your/mybug.c .
```

Perses supports C, C++, Java, Rust, Go, Python, and others — the language is
detected from the file extension.

### Now write your interestingness test

This is the part that most often goes wrong, so read it carefully.

Perses repeatedly deletes parts of your program and asks your script: *"is this
smaller version still interesting?"* Your script answers with its exit code.

**The four rules:**

1. **Exit `0` means "still interesting". Any other exit code means "not interesting".**
   This is backwards from how some tools work — `0` is the *keep it* answer.

2. **Read your program by filename from the current directory.**
   Write `mybug.c`, never `/home/skai0008/reduction-test/mybug.c`. Perses copies
   your program into a fresh temporary folder for each attempt and runs your
   script inside it.

3. **The script must be executable** — `chmod +x`.

4. **The script must exit `0` on your original, unmodified program.**
   Perses checks this first and refuses to start otherwise.

Here is a working example. It keeps shrinking as long as the program still
compiles and still crashes with a specific error:

```bash
cat > /home/skai0008/reduction-test/my_test.sh <<'EOF'
#!/usr/bin/env bash

# Rule 2: refer to the program by name, not by full path.
gcc -w -o a.out mybug.c 2>/dev/null || exit 1

# Interesting = the program crashes with a segfault.
./a.out 2>/dev/null
[[ $? -eq 139 ]] || exit 1

# Rule 1: exit 0 means "yes, still interesting".
exit 0
EOF

chmod +x /home/skai0008/reduction-test/my_test.sh
```

### Verify your test before running Perses

This one command saves a lot of confusion:

```bash
cd /home/skai0008/reduction-test
./my_test.sh; echo "exit code: $?"
```

**You must see `exit code: 0`.** If you see anything else, fix your test — Perses
will refuse to start otherwise. Common causes: using an absolute path to your
program, or having the "interesting" condition inverted.

---

## Step 8 — Run the reduction

```bash
cd /home/skai0008/PERSES/perses
scripts/trace/perses-trace.sh \
    --input /home/skai0008/reduction-test/mybug.c \
    --test  /home/skai0008/reduction-test/my_test.sh \
    --out   /home/skai0008/reduction-test/trace_out
```

**What this does, in order:**

1. Creates `trace_out/` and copies your program into a private working folder.
2. Runs Perses, which repeatedly shrinks the program and calls your test.
3. Records every attempt from two angles — what Perses did, and what your test saw.
4. Merges both records into `trace_out/reduction.json`.

**How long it takes** depends on your program's size and how slow your test is.
A few hundred lines with a one-second test typically takes minutes. Larger inputs
can run for hours. It is safe to leave running.

To pass extra Perses options, put them after `--`:

```bash
scripts/trace/perses-trace.sh \
    --input /home/skai0008/reduction-test/mybug.c \
    --test  /home/skai0008/reduction-test/my_test.sh \
    --out   /home/skai0008/reduction-test/trace_out \
    -- --threads 4
```

### Re-running

Each run needs a **fresh** output directory. To reuse the same path, add `--force`:

```bash
scripts/trace/perses-trace.sh \
    --input /home/skai0008/reduction-test/mybug.c \
    --test  /home/skai0008/reduction-test/my_test.sh \
    --out   /home/skai0008/reduction-test/trace_out \
    --force
```

`--force` **deletes** the directory first. This restriction is not arbitrary:
the two halves of the trace are matched using temporary folder names that restart
from zero on every run, so mixing two runs in one directory would pair up the
wrong records.

---

## Step 9 — Read your results

Your file is at `/home/skai0008/reduction-test/trace_out/reduction.json`.

### The headline numbers

```bash
python3 -c "
import json
d = json.load(open('/home/skai0008/reduction-test/trace_out/reduction.json'))
s = d['summary']
for k in ('originalTokens','finalTokens','reductionRatio','acceptedSteps',
          'totalCandidates','rejectedCandidates','queryCacheHits',
          'wallTimeMs','testTimeMs','instrumentationOverheadMs'):
    print('  %-28s %s' % (k, s[k]))
"
```

### The reduction trajectory

```bash
python3 -c "
import json
d = json.load(open('/home/skai0008/reduction-test/trace_out/reduction.json'))
for st in d['steps']:
    print('step %-3s %-14s %5s -> %-5s tokens' % (
        st['index'], st['transformation']['type'],
        st['tokensBefore'], st['tokensAfter']))
"
```

### What changed at a given step

```bash
python3 -c "
import json
d = json.load(open('/home/skai0008/reduction-test/trace_out/reduction.json'))
print(d['steps'][0].get('diff', '(no diff)'))
"
```

Change `[0]` to any step number.

### The final reduced program

Perses also writes it as a plain file:

```bash
cat /home/skai0008/reduction-test/trace_out/result/mybug.c
```

### Understanding a few fields

- **`tokensBefore` == `tokensAfter` on an accepted step is normal.** Some steps
  rename or restructure without removing anything; they are still real steps.
- **`unmatchedWrapperLines: 1`** is expected. Perses runs your test once up front
  as a safety check, outside the traced sequence.
- **`testTimeMs` can exceed `wallTimeMs`.** Tests run in parallel, so total test
  time can be greater than elapsed time. For instrumentation cost, compare
  `instrumentationOverheadMs` against `testTimeMs`.
- **`status: "CACHE_HIT"`** means Perses recognised a variant it had already
  tried and skipped running your test. Those records have no timing data.

---

## Troubleshooting

| Message | Cause | Fix |
|---|---|---|
| `Cannot find Java binary bin/java` | Bazel can't find a JDK | Do Step 3 |
| `error: perses_deploy.jar not found` | `PERSES_JAR` unset or wrong | Redo Step 5; check `ls -lh "$PERSES_JAR"` |
| `error: no such input file: ...` | You pasted a placeholder literally | Use your real file path |
| `already exists and is not empty` | Output directory was used before | Add `--force`, or pick a new path |
| `The initial sanity check failed` | Your test doesn't exit 0 on the original program | Run the check at the end of Step 7 |
| `source file and the test script should reside in the same folder` | Running Perses by hand | Use `perses-trace.sh`, which handles this |
| `test script missing or not executable` | Missing executable bit | `chmod +x your_test.sh` |
| Reduction produces nothing | Test is too strict, or always exits 0 | Verify it exits 0 on the original **and** non-zero on an obviously broken version |

### If the sanity check fails

Perses saves the exact files it tried, and prints the location:

```
The files have been saved, and you can check them at:
    /tmp/perses_failure_.../
```

Go there and run your test manually to see what it does:

```bash
cd /tmp/perses_failure_.../
ls
./my_test.sh; echo "exit code: $?"
```

Nine times out of ten it's Rule 2 — an absolute path to the program instead of
its bare filename.

---

## Reporting a problem

Please include:

1. The command you ran.
2. The last 30 lines of output.
3. `summary` from your `reduction.json`, if it was produced.
4. Your `my_test.sh`.
5. `java -version`, `bash --version`, and your OS.

Please don't attach `reduction.json` itself without checking it first — it
contains your program's source code.

# jenkins-atom-zip-artifacts

Zip / unzip — port of **pipeline-utility-steps（zip/unzip）** (约 90,000（utility-steps）) as an atom.

Two guards matter more than the compression: an entry name never escapes the target directory (zip-slip),
and the archive is written to a `.partial` file first so a failed pack leaves nothing behind.

## Build and smoke test

```bash
./build.sh
./tools/smoke.sh
```

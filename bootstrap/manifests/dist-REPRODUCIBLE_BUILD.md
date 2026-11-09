# Reproducible Self-Hosted Build & Verification

1. **Verify Baseline & Closure Fingerprints**:
   - `sha256sum --quiet -c porting/S1/production.sha256` (`3,430` production files)
   - `(cd "$DEPS_ROOT" && sha256sum --quiet -c "$WORK_ROOT/porting/S1/dependencies.sha256")`
2. **Rebuild Runtime Bitcode, `stdlib`, `stdlib-cache`, and Platform Libraries (`D1`)**:
   - Execute `porting/D1/build.sh all` under `scripts/with-zram-build-limits.sh` (`env -i PATH=/nonexistent`, 0 JVM).
3. **Execute `stage1 -> stage2 -> stage3` Bootstrap & Full Regression (`S1`)**:
   - Execute `porting/S1/build.sh all` under `scripts/with-zram-build-limits.sh`.

---
name: moneyflow-permissions
description: >
  Use when working on the 流水记账 (MoneyFlow) Android app at D:\Accounting tool —
  especially when touching permissions, the settings screen, MainActivity startup,
  AndroidManifest, or anything the user asks to "弹窗要权限". Also load before
  claiming a change is safe: this skill holds the project's settled rules, the
  regression that already happened once (the permission-popup feature was silently
  reverted), and the "don't break what works" verification checklist.
whenToUse: Any task in D:\Accounting tool that changes UI wiring, permissions, or could regress existing behaviour.
---

# 流水记账 — 项目约定与防误伤规则

This skill exists because **a feature the user explicitly liked was lost once**.
It was never committed and nothing tested it, so an update silently reverted it to the
older, worse behaviour. Everything below is aimed at that failure mode.

## 0. The incident — read this first

**What happened.** The app was changed so it asks for permissions **itself, via system
popups** instead of dumping the user into the Settings app. The user said they were
happy with it. Then "更新一次后这个改动没有了" — it was gone.

**Why.** Verified against `git log --all` (8 commits) and `git log -S`:

- `SettingsScreen.kt` **never** had request logic in any commit — its buttons have
  always called `startActivity(系统设置页)`.
- `MainActivity.kt` has had the *same* `permissionLauncher.launch(...)` **only inside
  `onCreate`** since the initial commit — a one-shot that dies if the user dismisses it.

So the liked version was an **uncommitted working-tree change**. It left no trace in
history; the only reason it could be reconstructed at all is that the user remembered
the behaviour. **The lesson is not "be careful" — it is that uncommitted + untested =
already lost.**

**What "fixed" now means.** `ui/PermissionRequest.kt` is the single entry point, wired
from both `MainActivity.onCreate` and the Settings page buttons, with 11 unit tests and
5 structural checks guarding it.

## 1. The rule that matters most: 能弹窗就弹窗，弹不了才跳设置页

| 权限 | 能不能 App 自己弹窗 | 用什么 |
|---|---|---|
| 短信 `READ_SMS` / `RECEIVE_SMS` | ✅ | 系统运行时权限框 |
| 通知 `POST_NOTIFICATIONS` (13+) | ✅ | 系统运行时权限框 |
| 忽略电池优化 | ✅ | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` → 系统「是否允许」框 |
| **通知使用权** | ❌ **平台不允许** | 只能 `ACTION_NOTIFICATION_LISTENER_SETTINGS` 跳列表 |

**The last row is the one people get wrong.** Android has never provided an Intent or a
runtime permission to request notification-listener access. There is no trick, no
reflection, no `requestRebind` that opens the grant UI. Sources:
[`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`](https://developer.android.com/reference/android/provider/Settings#ACTION_NOTIFICATION_LISTENER_SETTINGS),
[`SpecialPermission` enum docs](https://pub.dev/documentation/android_special_permissions/latest/android_special_permissions/SpecialPermission.html).

Consequence for the UI: for that one card, **say honestly that Android won't let the app
ask**, and tell the user to flip the switch themselves. Do not promise a popup that
cannot exist — the button would look broken.

## 2. Settled decisions — do not silently reverse

| # | Decision | Why |
|---|---|---|
| 1 | **No `INTERNET` permission, ever** | README promises 「代码里没有申请网络权限，不可能上传」. This is the product's core privacy claim. Guarded by `project_check.py` 7.9 and `PermissionRequestTest.runtimePermissionSetIsSane` |
| 2 | **Only `EXPENSE` / `INCOME`; no `NEUTRAL`** | User's rule (2026-10-15). A neutral row is invisible in monthly totals. Direction genuinely unknown → ask via 待确认转账, never record a vague direction |
| 3 | **`smsOnly = true` on broad bank-SMS rules** | Otherwise they outrank and steal WeChat/Alipay matches (real bug, `wechatMerchantRuleNotStolenByBroadSmsRule`) |
| 4 | **Verification codes are never recorded** | Single implementation in `parse/MoneyText.kt`. Two copies once existed → SMS leaked codes |
| 5 | **One fingerprint implementation** (`parse/Fingerprint.kt`) | Two copies → cross-source dedup silently fails |
| 6 | **宁可漏记，不可记错** | No amount → `NoAmount`, never guess one |
| 7 | **Permission logic lives only in `ui/PermissionRequest.kt`** | This project has been burned twice by duplicated logic (fingerprint, MoneyText). Don't add a `startActivity` for permissions anywhere else |

Rule 7 is the one this skill adds. If you need a new permission guided, add a
`PermissionRequest.Kind`, not a new `Intent` at the call site.

## 3. The traps in permission code

| Trap | Correct behaviour |
|---|---|
| Prompting on every cold start | Guard with `needsRuntimePrompt`. Repeated prompts trigger Android's **「不再询问」**, which locks the user out **permanently** — worse than not prompting at all |
| Prompting again when already granted | Android returns silently; the user sees nothing and thinks the app is broken. Check state first |
| Prompting during `onCreate` on every recreation | Rotation re-runs it. Gate on `savedInstanceState == null` |
| Putting 通知使用权 in the startup path | ~~Cold start would dump the user into system UI unannounced~~ — **REVERSED 2026-10-15**: the user explicitly asked 「每次打开软件自动检测通知权限和后台运行权限，如果没打开就要求打开」. `PermissionRequest.STARTUP_ASK_ORDER` now ends with `NOTIFICATION_ACCESS` (app jumps to the system list itself), once per process via `askedThisProcess`, after all popup-able kinds. Test: `notificationAccessIsDemandedOnAppOpen`. Guard: `project_check.py` 7.6 |
| Assuming `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` always resolves | Some ROMs (Meizu, some HyperOS builds) don't implement it → `ActivityNotFoundException`. **Degrade**, never crash: this is why `startSafely` exists ([known issue](https://github.com/torlando-tech/columba/pull/352)) |
| Confusing the two battery intents | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` = the dialog. `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` = the list page. Only the first is a "popup" |
| Treating 「已授权」 as 「已连接」 | For notification listener these are different. The static grant can be on while the service is unbound and **no notifications arrive**. Settings page shows `已授权·未连接` — see `docs/真机验收记录.md` |
| Checking a constant with `substring in file_text` | Greps match **comments**. The first version of the structural check passed while the feature was deleted. Strip comments first — see §5 |

## 4. Don't-break-what-works checklist

Run before claiming any change in this repo is safe:

```bash
cd "/d/Accounting tool"
bash tools/run_all_tests.sh        # 5 layers; must be all green
bash tools/build_and_install.sh    # only when installing to a phone
```

| Layer | Baseline (2026-10-15) | What it protects |
|---|---|---|
| 结构自检 `project_check.py` | 通过 | Manifest/resources/packages + **permission wiring** (§5) |
| JVM 单元测试 | **173 通过** | Parse rules, fingerprint, CSV, permission logic + startup gate |
| 解析引擎 runner | 通过 | Engine without JUnit |
| `rule_check.py` | 通过 (36/36) | Python↔Kotlin rule agreement |
| `real_sms_check.py` | **19/19** | 10 real bank SMS templates |
| 仪器测试 (`--device`) | 32 通过 *(needs a phone)* | Room, migrations, CSV import dedup |

If a number **drops**, you broke something — the baseline is the contract.
If a number **rises**, update this table and the README in the same sit.

### What is verified, and what is not (be honest)

| Claim | Status |
|---|---|
| Prompt *logic* (when/what to prompt) | ✅ 11 unit tests |
| *Wiring* (startup + 3 buttons actually call it) | ✅ structural check + mutation tested |
| App starts on a real phone without crashing after the change | ✅ REDMI K80 / Android 15, `MainActivity` focused, no `FATAL`, `KeepAliveService` up |
| Anti-nag works (already-granted ⇒ no prompt) | ✅ observed live: all runtime perms granted ⇒ no dialog on launch |
| Startup gate logic (order, session memory, access jump) | ✅ 7 unit tests + structural check 7.6 (mutation-tested) |
| Inline quick-fill wiring (RemoteInput + receiver branch) | ✅ structural checks 7.7/7.8 (mutation-tested); behavior logic covered by `MoneyTextTest.parseAmount` |
| **The system dialog actually appears to the user** | ⏸ **not verified on-device** |
| **Startup gate dialogs / access jump on a real phone** | ⏸ **not verified on-device** (needs: revoke perms by hand → open app → observe) |
| **Quick-fill notification flow on a real phone** | ⏸ **not verified on-device** (needs: trigger a real transfer notification → fill inline → confirm row) |

The last row is a real gap, and the reason is a platform restriction: **`adb shell pm
revoke` is refused on HyperOS** (`SecurityException: REVOKE_RUNTIME_PERMISSIONS`), so
there is no way to put the device back into the "not yet granted" state from adb —
and that state is the only one in which the dialog appears. Do **not** read
"I can't trigger it" as "it isn't implemented". To see it, the user must do it by hand:

> 设置 → 应用管理 → 流水记账 → 权限 → 把「短信」关掉 → 再打开 App → 应当看到系统弹窗

## 5. How the regression is now guarded (and why both layers exist)

Unit tests alone are **not** enough here, and this is the subtle part:

> `PermissionRequestTest` tests the logic *inside* `PermissionRequest`. Delete the call
> in `MainActivity.onCreate` and every unit test still passes — the code is correct,
> it just isn't **wired**. That is exactly how the feature was lost.

So `tools/project_check.py` §7 checks the wiring, on comment-stripped source:

1. `PermissionRequest.kt` exists
2. `onCreate` contains `needsRuntimePrompt` **and** `requestRuntimePermissions` in the
   same expression (the startup prompt, and its anti-nag guard together)
3. All **3/3** settings buttons call `onRequestPermission(PermissionRequest.Kind.*)`
4. No other file redefines `openAppSettings` / `openBatterySettings` /
   `openNotificationAccessSettings`
5. `AndroidManifest.xml` has no `INTERNET` / `ACCESS_NETWORK_STATE`
6. `onResume` calls `PermissionRequest.ensureOnAppOpen` — the every-open permission
   gate (user requirement, 2026-10-15)
7. `capture/TransferPrompt.kt` uses `RemoteInput.Builder(` — the transfer reminder
   supports inline quick-fill without opening the app
8. `capture/PendingTransferActionReceiver.kt` handles `ACTION_QUICK_FILL ->` —
   the quick-fill actually writes to the DB

**These checks were themselves verified by mutation.** A check that cannot fail is
decoration. Reproduce it like this:

```bash
cd "/d/Accounting tool"
PY="/c/Users/24996/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe"
cp app/src/main/java/com/moneyflow/app/MainActivity.kt /tmp/M.bak

python - <<'EOF'
import re
p='app/src/main/java/com/moneyflow/app/MainActivity.kt'
s=open(p,encoding='utf-8').read()
s2=re.sub(r'if \(savedInstanceState == null && PermissionRequest\.needsRuntimePrompt\(this\)\) \{'
          r'\s*PermissionRequest\.requestRuntimePermissions\(permissionLauncher\)\s*\}',
          '// gone', s)
assert s2 != s          # fail loudly if the pattern drifts
open(p,'w',encoding='utf-8').write(s2)
EOF

"$PY" tools/project_check.py; echo "expect exit 1, got $?"
cp /tmp/M.bak app/src/main/java/com/moneyflow/app/MainActivity.kt
```

Confirmed results: clean tree → exit **0**; mutated → exit **1**; restored → exit **0**.
Also verified: removing only the `needsRuntimePrompt` guard, and unwiring one settings
button, are each caught.

> ⚠️ When you touch the permission code, **re-run this mutation test**. If a refactor
> changes the wording, the regex above stops matching — and `assert s2 != s` is what
> tells you the *test* went stale rather than the code going green.

## 6. Code-quality rules this project follows

Distilled from the ponytail skills (`D:\DSH_mobile\.dsh\skills\ponytail*`) and the
verification discipline in `D:\DSH_mobile\.dsh\skills\dsh-mobile-link`.

**One implementation per concept.** The ladder, applied to this repo: does this already
exist here? `Fingerprint`, `MoneyText`, `PermissionRequest` were each extracted after a
duplication bug. Look before writing.

**Root cause, not symptom.** A bug report names a symptom. Before editing, grep every
caller. The lazy fix *is* the root-cause fix: one guard in the shared function beats a
guard in each caller. (This project's 「收款 vs 收款方」 bug was a rule priority problem
fixed in one table, not in five call sites.)

**A change without a runnable check is unfinished.** Non-trivial logic — a branch, a
parser, a money path, **a permission path** — leaves one check that fails if it breaks.
Trivial one-liners need no test.

**Prefer deletion.** No interface with one implementation, no config for a constant, no
scaffolding "for later".

**Mark deliberate shortcuts.** A real ceiling gets a comment naming the ceiling and the
upgrade trigger, e.g. `// ponytail: 全局锁，吞吐有要求时改按账户加锁`.

**Provenance rule (from dsh-mobile-link §7).** Every non-obvious fact in code, a comment,
or a doc must **state where it came from** — a doc §, a source file, or 「真机实测」.
A number with no citation is a defect. When a cited fact turns out false, **report it**
and name the document that needs amending; do not quietly code around it.

**Docs must tell the truth.** README distinguishes 「✅ 已实测」 from 「⏸ 未验证」.
Never upgrade "ran in the emulator" to "verified", and **delete** claims disproved by
real-device testing (the retracted 「打开 App 即可自愈」 was worse than saying nothing).

## 7. Deliverable discipline — so nothing gets lost again

1. **Commit the change in the same sitting.** Uncommitted work is not saved work; that
   is the entire incident in §0.
2. **Leave a check that fails if it regresses** — unit test for logic, structural check
   for wiring (because they fail independently).
3. **Verify the check can fail** (mutation, §5). Unverified guards are decoration.
4. **Update the numbers** in README and `docs/交接文档.md` when the test count changes.
5. **State what you did NOT verify.** Real-device items (overnight keep-alive, CSV
   import on device, per-bank SMS formats) stay marked unverified.

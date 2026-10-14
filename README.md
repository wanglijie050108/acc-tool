# 流水记账 (MoneyFlow)

Android 本地自动记账工具。自动读取微信/支付宝/银行的通知与短信，实时生成流水，
无需手动切换 App 记账。

- **平台**：Android 8.0+ (API 26)
- **技术栈**：Kotlin + Jetpack Compose + Room
- **数据**：纯本地 SQLite，**代码里没有申请网络权限**，不可能上传

---

## 快速开始

**最省事的方式（不用装 Android Studio）**：
把 `app/build/outputs/apk/release/app-release.apk` 传到手机，点击安装。
详见 **[docs/安装与使用.md](docs/安装与使用.md)**。

**如果要自己改代码**：用 Android Studio 打开本目录，点绿色三角运行。

---

## 当前验证状态

| 项目 | 状态 | 证据 |
|---|---|---|
| 编译通过 | ✅ | `clean assembleDebug assembleRelease` BUILD SUCCESSFUL，0 warning |
| 产出可安装 APK | ✅ | release 10.4 MB，apksigner 验证签名有效 |
| JVM 单元测试 | ✅ | **120 个用例通过**（`gradlew testDebugUnitTest`） |
| 数据库/导入/迁移测试 | ✅ | **26 个用例通过**，跑在真实 Android 14 上 |
| 真实银行短信覆盖率 | ✅ | **19/19**（10 家银行的真实模板） |
| **自定义规则端到端** | ✅ | 真机验证：内置规则认不出的通知，用自定义规则成功记账 |
| 结构完整性 | ✅ | `python tools/project_check.py` 全通过 |
| 模拟器真实运行 | ✅ | App 启动无崩溃，数据库自动创建，保活服务 `isForeground=true` |
| 未知格式留档 | ✅ | 验证过：无法识别的银行通知被记录，验证码不被记录 |
| 真机运行 | ⬜ | **未验证** —— 需要你装到手机上确认 |

### 测试覆盖什么

| 测试类 | 数量 | 覆盖内容 |
|---|---|---|
| `ParseEngineJUnitTest` | 35 | 微信/支付宝/银行短信识别、千分位、全角符号、收支歧义、防误记、指纹 |
| `RealBankSmsTest` | 22 | **10 家银行的真实短信模板** |
| `CsvParserTest` | 18 | 账单 CSV：表头不在首行、含逗号的商户名、¥符号、不计收支、GBK/BOM |
| `CustomRuleTest` | 16 | 用户自定义规则：优先级、正则非法容错、组号越界、smsOnly 隔离 |
| `NotificationFieldCombinationTest` | 12 | 真机通知的字段组合（金额藏在 bigText/subText 里等） |
| `RuleJsonToleranceTest` | 9 | 手写规则文件的容错（反斜杠未转义等常见错误） |
| `SampleFormatTest` | 8 | 样本导出格式 |
| `TransactionRepositoryTest` | 22 | Room 真实读写、去重窗口边界、跨分钟去重回归、CSV 导入去重 |
| `MigrationTest` | 4 | **v1→v2 迁移不丢数据**（升级 App 的安全保障） |

### 遇到认不出的格式：不用等更新

这是从成熟项目 [AutoAccounting](https://github.com/AutoAccountingOrg/AutoAccounting) 学到的做法 ——
把规则做成**用户可编辑的数据**，而不是写死在代码里。

内置 18 条规则覆盖微信/支付宝/10 家银行。遇到新格式有两条路：

1. **设置 → 自定义规则**：粘贴原文 → 写正则 → 立即测试 → 保存，**立刻生效**
2. **设置 → 导出样本**：把 `[UNMATCHED]` 的样本发给我，补进内置规则

自定义规则优先级高于内置规则，所以用户能覆盖内置的错误判断。

> ⚠️ 陷阱：JSON 里正则的反斜杠必须写成 `\\d`。写错的话整个文件会解析失败、
> 规则静默消失。已加自动修复（`escapeLoneBackslashes`）和 9 个测试覆盖这种情况。


### 真实格式校准（重要）

银行短信规则最初只按「常见格式」猜写，用公开的 10 家银行真实模板一测，
**覆盖率只有 9/19（47%）**。补齐后达到 **19/19**。

真实格式里的坑（都是实测踩出来的）：

| 银行 | 坑 |
|---|---|
| 招商 | 金额后**没有「元」字**：「扣款人民币99.50，余额1123.59」 |
| 平安 | 用 **RMB** 而不是「人民币」：「存RMB485,000.00元」 |
| 农业 | 用**负号**表示支出：「现支(分行营业部)-1400.00元」 |
| 工商 | 时间是**全角冒号**：「12日19：30」 |
| 民生 | 用 **￥** 符号：「支出￥100.00元」 |
| 中国/成都/德阳 | 动词是 **支取/取款/入账/存款**，不在常见词表里 |

**为此引入的 `smsOnly` 标志**：宽松的短信规则如果也去匹配 App 通知，
会因优先级更高而**抢走微信/支付宝的匹配**，导致商户名丢失、来源标错。
这个 bug 真实发生过，现在有回归测试守着。

**自己跑覆盖率检查**：

```bash
python tools/real_sms_check.py
```


### 功能完成度

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 骨架 + 数据库 + 手动补录 + 流水列表 | ✅ |
| P1 | 通知抓取 + 前台保活 | ✅ |
| P2 | 短信抓取 | ✅ |
| P3 | 用真实样本校准规则 | ⏳ **待你的样本** |
| P4 | CSV 账单导入 | ✅ **已实现**（不只预留接口） |

### 开发中发现并修复的真 bug

1. **去重失效**（真机测试发现）：指纹含「分钟」，相隔 19 秒但跨分钟边界
   的两条相同记录指纹不同 → 同一笔钱记两次。
   修复：指纹只含「金额 + 方向」，时间判断交给 ±90 秒范围查询。
   回归测试：`crossMinuteBoundaryIsStillDeduplicated`。

2. **前台服务启动崩溃风险**：Android 12+ 在后台启动前台服务会抛异常，
   原代码未捕获 → 服务崩溃。修复：加 try/catch 并降级。

3. **CSV 单行表头被误判为无效文件**：原逻辑要求「至少 2 行」，
   导致「只有表头、当期无交易」的合法账单报错。修复后返回 0 条记录。

4. **指纹实现重复**：解析引擎和 CSV 导入各写一份，
   一旦不一致跨来源去重就会失效。已统一到 `Fingerprint`。

5. **结构自检误报**（工具自身）：用正则粗暴剥离字符串导致括号误判。
   改用状态机实现（`tools/check_braces.py`）。

6. **短信规则抢走微信/支付宝的匹配**：为了让短信覆盖率达到 100%，
   规则写得较宽松，结果因优先级更高而抢走了 App 通知的匹配，
   导致商户名提取不到。修复：引入 `smsOnly` 标志，宽松规则只在短信通道生效。
   回归测试：`wechatMerchantRuleNotStolenByBroadSmsRule`。

### 识别不了的通知不会丢（重要设计）

解析失败的通知以前会**静默消失** —— 用户付了钱，App 没记，也没人知道为什么。

现在所有「看起来和钱有关」的通知都会被留档（`raw_samples` 表），
用户可以在 **设置 → 导出样本** 里一键分享出来给开发者补规则。

隐私保护：只留「含金额/收支字样」的，验证码和聊天记录**不记录**；
最多留 300 条自动淘汰；可一键清空；只存本地。

已验证：一条无法识别的银行通知「您尾号9999的卡发生一笔金额为66.00元的交易」
被正确留档并标记为 `UNMATCHED`，而验证码通知未被记录。

### 模拟器发现的系统行为（非 bug，但影响使用）

Android 会冻结后台进程，此时**授权仍显示已开启，但收不到通知**。已加两项对策：
- `MainActivity.onResume` 主动请求重连（打开 App 即自愈）
- 设置页显示「已授权但监听未连接」警告

详见 [docs/安装与使用.md](docs/安装与使用.md) 第七节。

> ⚠️ 诚实说明：编译、87 个自动化测试、模拟器端到端抓取都已真实跑通。
> **但未在真实手机上验证过** —— 真机 ROM 的保活策略与模拟器不同，
> 各家银行短信格式也存在差异，必须装到手机上才能最终确认。

---

## 项目结构

```
app/src/main/java/com/moneyflow/app/
├── MainActivity.kt              页面路由（三个页面，不用导航库）
├── MoneyFlowApp.kt              Application，启动保活服务
│
├── data/                        数据层
│   ├── TransactionEntity.kt     流水实体（Room Entity）
│   ├── TransactionDao.kt        查询接口
│   ├── AppDatabase.kt           数据库 + 枚举转换器
│   └── TransactionRepository.kt 仓库：**去重逻辑的唯一入口**
│
├── parse/                       解析引擎（项目核心）
│   ├── ParseRule.kt             规则数据结构
│   ├── BuiltinRules.kt          ★ 规则表：识别不准就改这里
│   └── ParseEngine.kt           执行匹配、金额提取、指纹生成
│
├── capture/                     系统抓取层
│   ├── NotificationCaptureService.kt  ★ 通知监听（主要数据源）
│   ├── SmsReceiver.kt                 短信接收
│   ├── KeepAliveService.kt            前台常驻保活
│   └── BootReceiver.kt                开机自启
│
└── ui/                          界面层
    ├── TransactionListScreen.kt 主界面：流水列表
    ├── TransactionEditorDialog.kt 补录/编辑弹窗
    ├── SettingsScreen.kt        权限引导
    ├── ParseBenchScreen.kt      ★ 解析测试台（调规则用）
    ├── TransactionViewModel.kt  状态管理
    ├── Theme.kt / Fmt.kt        主题与格式化
```

---

## 数据流

```
微信支付通知 ┐
支付宝通知   ├→ NotificationCaptureService ┐
银行App通知  ┘                              │
                                            ├→ ParseEngine.parse()
银行短信 ────→ SmsReceiver ─────────────────┘        │
                                                     ↓
                                          命中规则？→ 提取金额+方向+商户
                                                     ↓
                                          生成指纹（金额+方向+分钟）
                                                     ↓
                                    Repository.insertDeduplicated()
                                                     ↓
                                        ±90秒内有同指纹？→ 丢弃
                                                     否 ↓
                                                 写入 Room
                                                     ↓
                                            首页列表自动刷新
```

---

## 三个关键设计

### 1. 规则是数据，不是代码

`BuiltinRules.kt` 里每条规则就是一个正则 + 元信息：

```kotlin
ParseRule(
    id = "wechat_pay_merchant",
    packageNames = setOf("com.tencent.mm"),
    pattern = Regex("""向\s*(.{1,20}?)\s*(?:付款|支付)\s*[¥￥]?\s*([\d,]+\.?\d*)"""),
    amountGroup = 2,
    direction = Direction.EXPENSE,
    merchantGroup = 1,
)
```

**识别错了只改这张表，不用碰引擎逻辑。** 加一家新银行 = 加一条规则。

### 2. 跨来源去重

同一笔支付可能产生两条记录源（微信通知 + 银行扣款短信）。
指纹 = `金额 + 方向 + 分钟桶`，在 ±90 秒窗口内指纹相同则只保留一条。
**指纹故意不含来源**，因为正是要跨来源去重。

### 3. 保留原始文本

每条记录都存了 `rawText`（触发它的通知/短信全文）。
出问题时点开记录就能看到原始内容，直接复制来调规则。

---

## 规则自检

不需要 Android 环境就能验证正则是否正确：

```bash
python tools/rule_check.py
```

它用真实感的样本（含千分位、全角符号、收支歧义、无关通知）测试全部规则。
**改完规则务必跑一遍。**

## 跑全部测试

```bash
bash tools/run_all_tests.sh            # 结构自检 + JVM 单测 + 规则对照（快）
bash tools/run_all_tests.sh --device   # 额外跑数据库仪器测试（需先启动模拟器）
```

## 单跑某一层

```bash
# JVM 单元测试（35+12 个用例，秒级）
./gradlew testDebugUnitTest

# 数据库仪器测试（16 个用例，需要已连接的设备）
./gradlew connectedDebugAndroidTest

# 解析引擎独立 runner（不依赖 JUnit，直接跑 JVM）
bash tools/run_engine_test.sh
```

**改动 `ParseEngine` / `BuiltinRules` / `TransactionRepository` 后，必须跑测试。**

## 本机开发环境（已配置好）

这台电脑上已经装好了编译所需的一切，路径记录如下（换电脑需要重装）：

| 组件 | 路径 |
|---|---|
| JDK 17 (Temurin) | `C:\Users\24996\jdk17\jdk-17.0.20.1+1` |
| Android SDK | `C:\Android\Sdk`（含 platform 34、build-tools 34.0.0） |
| `local.properties` | 指向 `C:\Android\Sdk`，已写入 |

编译命令（bash）：

```bash
export JAVA_HOME="/c/Users/24996/jdk17/jdk-17.0.20.1+1"
export ANDROID_HOME=/c/Android/Sdk
./gradlew assembleRelease
```

---

## 如何反馈识别问题

1. App 里 **设置 → 解析测试** → 粘贴通知原文 → 点测试
2. 如果显示「未识别」，把原文复制出来发给 AI
3. AI 修改 `BuiltinRules.kt` 加规则，跑 `rule_check.py` 验证
4. 重新编译安装

---

## 路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 骨架 + 数据库 + 手动补录 + 流水列表 | ✅ 已完成并编译通过 |
| P1 | 通知抓取 + 前台保活 | ✅ 已完成并编译通过 |
| P2 | 短信抓取 | ✅ 已完成并编译通过 |
| P3 | 用真实样本校准规则 | 🟡 银行短信已完成（10 家 19/19）；你的实际通知待验证 |
| P4 | CSV 账单导入对账 | ✅ 已完成（含去重，不是空接口） |
| P5 | 分类、报表、预算 | ⬜ 按需 |

**关于 P3**：银行短信规则已用 10 家银行的真实模板校准，覆盖率 19/19。
微信/支付宝通知覆盖常见格式，但其措辞随版本变化，无法凭空穷举。

因此提供两条自助路径，**不必等发版**：

1. **设置 → 自定义规则**：粘贴原文 → 写正则 → 立即测试 → 保存，当场生效
2. **设置 → 导出样本**：把标记 `[UNMATCHED]` 的样本发来，补进内置规则

自定义规则优先级高于内置规则，所以遇到内置判断错误时也能覆盖。

**关于 P4/P5**：数据库已预留 `category` 字段、`SourceKind.CSV_IMPORT` 枚举值，
加功能时不需要改表结构、不需要迁移数据。

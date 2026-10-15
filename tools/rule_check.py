"""校验 BuiltinRules 里的正则能否匹配常见真实文本。
Python re 与 Java Regex 在这些模式上语法兼容，可直接验证规则正确性。
"""
import re

WECHAT = "com.tencent.mm"
ALIPAY = "com.eg.android.AlipayGphone"
UNIONPAY = "com.unionpay"

# (id, pkg_filter, pattern, amount_group, direction, priority, merchant_group)
rules = [
    ("wechat_receive", {WECHAT}, r"(?:收款到账|收款成功|已收款|收款通知|收到转账|收到一笔|收到红包|到账)\s*(?:通知|提醒)?\s*[:：]?\s*[¥￥]?\s*([\d,]+\.?\d*)", 1, "INCOME", 20, None),
    ("wechat_pay_merchant", {WECHAT}, r"向\s*(.{1,20}?)\s*(?:付款|支付)\s*[¥￥]?\s*([\d,]+\.?\d*)", 2, "EXPENSE", 15, 1),
    ("wechat_pay_generic", {WECHAT}, r"微信支付.*?[-－]\s*[¥￥]?\s*([\d,]+\.?\d*)", 1, "EXPENSE", 10, None),
    ("alipay_pay", {ALIPAY}, r"(?:成功付款|支付成功|付款成功)\s*[¥￥]?\s*([\d,]+\.?\d*)", 1, "EXPENSE", 15, None),
    ("alipay_pay_merchant", {ALIPAY}, r"(?:向|在)\s*(.{1,20}?)\s*(?:付款|支付|消费)\s*[¥￥]?\s*([\d,]+\.?\d*)", 2, "EXPENSE", 14, 1),
    ("alipay_receive", {ALIPAY}, r"(?:收款到账|收款成功|已收款|收款通知|收到转账|收到一笔|收到红包|到账)\s*(?:通知|提醒)?\s*[:：]?\s*[¥￥]?\s*([\d,]+\.?\d*)", 1, "INCOME", 20, None),
    ("alipay_generic_expense", {ALIPAY}, r"支付宝.*?[-－]\s*[¥￥]?\s*([\d,]+\.?\d*)", 1, "EXPENSE", 5, None),
    ("unionpay_pay", {UNIONPAY}, r"(?:消费|支出|付款|交易).*?[¥￥]?\s*([\d,]+\.?\d*)", 1, "EXPENSE", 10, None),
    ("sms_expense", set(), r"(?:支出|消费|付款|转出|扣款|取现)人民币\s*([\d,]+\.?\d*)\s*元", 1, "EXPENSE", 30, None),
    ("sms_income", set(), r"(?:收入|存入|转入|到账|退款)人民币\s*([\d,]+\.?\d*)\s*元", 1, "INCOME", 30, None),
    ("sms_expense_plain", set(), r"(?:支出|消费|扣款|转出)\s*(?:金额)?\s*[¥￥]?\s*([\d,]+\.?\d*)\s*元", 1, "EXPENSE", 20, None),
    ("sms_income_plain", set(), r"(?:收入|转入|存入|到账|退款)\s*(?:金额)?\s*[¥￥]?\s*([\d,]+\.?\d*)\s*元", 1, "INCOME", 20, None),
    ("sms_repayment", set(), r"(?:还款|已还|偿还)\s*(?:金额)?\s*(?:人民币)?\s*[¥￥]?\s*([\d,]+\.?\d*)\s*元", 1, "NEUTRAL", 25, None),
    ("sms_transfer_neutral", set(), r"(?:提现|充值|转存|转账)\s*(?:金额)?\s*(?:人民币)?\s*[¥￥]?\s*([\d,]+\.?\d*)\s*元", 1, "NEUTRAL", 22, None),
    ("wechat_withdraw_settled", {WECHAT}, r"(?:零钱提现|提现).{0,30}?到账\s*[¥￥]?\s*([\d,]+\.?\d*)\s*元?", 1, "NEUTRAL", 23, None),
    ("wechat_withdraw", {WECHAT}, r"(?:零钱提现|提现)\s*(?:金额)?\s*[:：]?\s*(?:人民币)?\s*(?:(?:[-－]|[¥￥])\s*)?([\d,]+\.?\d*)\s*元?", 1, "NEUTRAL", 22, None),
    ("generic_repayment_notification", set(), r"(?:信用卡还款|自动还款|还款成功).*?[¥￥]?\s*([\d,]+\.?\d*)", 1, "NEUTRAL", 22, None),
    ("fallback_expense", set(), r"(?:支付|付款|消费|扣款|支出).*?[¥￥]\s*([\d,]+\.?\d*)", 1, "EXPENSE", -10, None),
]

rules_sorted = sorted(rules, key=lambda r: -r[5])


def normalize(text):
    for a, b in [("\u00a0", " "), ("\u3000", " "), ("\uff0d", "-"),
                 ("\u2013", "-"), ("\u2014", "-"), ("\r\n", " "), ("\n", " ")]:
        text = text.replace(a, b)
    return re.sub(r"\s+", " ", text).strip()


def parse(text, pkg):
    n = normalize(text)
    for rid, pkgs, pat, grp, direction, prio, mgrp in rules_sorted:
        if pkgs:
            if pkg is None or pkg not in pkgs:
                continue
        m = re.search(pat, n)
        if not m:
            continue
        amt_raw = m.group(grp) if m.lastindex and grp <= m.lastindex else None
        if amt_raw is None:
            continue
        amt = float(amt_raw.replace(",", ""))
        if amt <= 0:
            continue
        merchant = None
        if mgrp and m.lastindex and mgrp <= m.lastindex:
            merchant = m.group(mgrp).strip() or None
        return dict(rule=rid, amount=amt, direction=direction, merchant=merchant)
    return None


# 真实感样本： (描述, 包名, 文本, 期望方向, 期望金额)
cases = [
    ("微信-付款给商户", WECHAT, "微信支付 向 星巴克(国贸店) 付款 ¥38.00", "EXPENSE", 38.0),
    ("微信-转账支出", WECHAT, "微信支付-¥200.00", "EXPENSE", 200.0),
    ("微信-收款", WECHAT, "微信支付 收款到账 ¥88.88", "INCOME", 88.88),
    ("微信-千分位", WECHAT, "微信支付-¥1,234.56", "EXPENSE", 1234.56),
    ("支付宝-付款成功", ALIPAY, "支付宝 你已成功付款 ¥25.50", "EXPENSE", 25.50),
    ("支付宝-向商户付款", ALIPAY, "支付宝 向 美团外卖 付款 ¥45.00", "EXPENSE", 45.0),
    ("支付宝-收款到账", ALIPAY, "支付宝 收款到账 ¥300.00", "INCOME", 300.0),
    ("建行-支出短信", None, "您尾号1234的储蓄卡10月14日16:42支出人民币58.00元，余额1234.56元", "EXPENSE", 58.0),
    ("工行-收入短信", None, "您尾号5678账户10月14日收入人民币5000.00元，余额8000.00元", "INCOME", 5000.0),
    ("招行-扣款短信", None, "您尾号9012账户10月14日扣款人民币128.00元", "EXPENSE", 128.0),
    ("退款短信", None, "您尾号1234账户10月14日退款人民币200.00元", "INCOME", 200.0),
    ("支付宝-大额千分位", ALIPAY, "支付宝 支付成功 ¥2,000.00", "EXPENSE", 2000.0),
    ("微信-全角减号", WECHAT, "微信支付－¥66.00", "EXPENSE", 66.0),
    ("无关通知", WECHAT, "张三 发来一条消息", None, None),
    ("银行营销短信", None, "【招商银行】您的信用卡账单已生成，请及时查看", None, None),

    # 微信「收款方」是**支出**语义（指商家），绝不能被判成收入。
    # 这是真机复现过的方向记反 bug：旧规则用裸「收款」命中了「收款方」。
    ("微信-收款方是支出", WECHAT, "微信支付 收款方：星巴克 ¥38.50", "EXPENSE", 38.50),
    ("微信-收款方变体", WECHAT, "微信支付 收款方 美团外卖 ¥25.00", "EXPENSE", 25.00),
    # 修复后真正的入账写法仍必须识别为收入
    ("微信-已收款", WECHAT, "微信支付 已收款 ¥50.00", "INCOME", 50.00),
    ("微信-收到转账", WECHAT, "微信支付 收到转账 ¥20.00", "INCOME", 20.00),
    ("微信-到账", WECHAT, "微信支付 到账 ¥100.00", "INCOME", 100.00),
    # 提现不能被手续费/订单号骗走金额
    ("微信-提现带手续费", WECHAT, "微信支付 零钱提现 手续费 0.10 元 到账 500.00 元", "NEUTRAL", 500.00),

    # ── 边界情况：这些是真实使用中最容易出错的 ──

    # 余额不能被当成消费金额（最常见的误记！）
    ("短信-余额干扰", None, "您尾号1234账户支出人民币58.00元，余额1234.56元", "EXPENSE", 58.0),
    # 通知里带换行（微信常有）
    ("微信-带换行", WECHAT, "微信支付\n向 肯德基 付款\n¥29.90", "EXPENSE", 29.90),
    # 整数金额无小数位
    ("支付宝-整数金额", ALIPAY, "支付宝 付款成功 ¥100", "EXPENSE", 100.0),
    # 微信零钱提现：资金流动，记为 NEUTRAL（不算消费也不算收入）
    ("微信-提现", WECHAT, "微信支付 零钱提现 ¥500.00", "NEUTRAL", 500.0),
    # 红包支出
    ("微信-发红包", WECHAT, "微信支付 向 李四 付款 ¥66.60", "EXPENSE", 66.60),
    # 信用卡还款：资金流动，NEUTRAL
    ("还款短信", None, "您尾号1234账户还款人民币3000.00元", "NEUTRAL", 3000.0),
    # 还款通知
    ("还款通知", None, "信用卡还款成功 ¥3,000.00", "NEUTRAL", 3000.0),
    # 提现短信
    ("提现短信", None, "您尾号1234账户提现人民币500.00元", "NEUTRAL", 500.0),
    # 全角空格干扰
    ("微信-全角空格", WECHAT, "微信支付\u3000向\u3000滴滴出行\u3000付款\u3000¥18.50", "EXPENSE", 18.50),
    # 大额带多个千分位
    ("短信-十万级", None, "您尾号1234账户支出人民币100,000.00元", "EXPENSE", 100000.0),
    # 收入而非支出的歧义：到账
    ("短信-转入到账", None, "您尾号5678账户转入人民币250.00元", "INCOME", 250.0),
    # 纯粹的验证码/营销，绝不能记
    ("验证码短信", None, "【工商银行】您的验证码是123456，请勿泄露", None, None),
    ("快递通知", WECHAT, "您的快递已到达菜鸟驿站，取件码 8-1-2345", None, None),
    ("群消息", WECHAT, "王五: 晚上一起吃饭吗", None, None),
]

print(f"{'样本':<22} {'结果':<10} {'金额':>10}  {'方向':<8} {'命中规则':<22} 商户")
print("-" * 100)
passed = failed = 0
for desc, pkg, text, exp_dir, exp_amt in cases:
    r = parse(text, pkg)
    if exp_dir is None:
        ok = r is None
        got = "未识别(正确)" if r is None else f"误识别! {r['amount']}"
        print(f"{desc:<22} {'PASS' if ok else 'FAIL':<10} {got:>10}")
    else:
        ok = r is not None and r["direction"] == exp_dir and abs(r["amount"] - exp_amt) < 0.001
        if r:
            print(f"{desc:<22} {'PASS' if ok else 'FAIL':<10} {r['amount']:>10.2f}  {r['direction']:<8} {r['rule']:<22} {r['merchant'] or ''}")
        else:
            print(f"{desc:<22} {'FAIL':<10} {'未识别':>10}")
    passed += ok
    failed += (not ok)

print("-" * 100)
print(f"通过 {passed} / {passed + failed}")
if failed:
    print(f"\n[FAIL] {failed} 条失败，需要调整规则")
else:
    print("\n[OK] 全部规则通过")

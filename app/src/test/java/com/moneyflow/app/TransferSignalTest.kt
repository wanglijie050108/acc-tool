package com.moneyflow.app

import com.moneyflow.app.data.Direction
import com.moneyflow.app.parse.TransferSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转账信号识别的单元测试。
 *
 * ⚠️ 这组用例的核心不是「能不能识别转账」，而是**能不能忍住不误报**。
 * 用户每天收到几十条无金额通知，误报一条就弹一次提醒。
 * 所以下面的「必须不识别」用例比「必须识别」更重要。
 */
class TransferSignalTest {

    private val WECHAT = "com.tencent.mm"
    private val ALIPAY = "com.eg.android.AlipayGphone"

    private fun detect(text: String, pkg: String? = WECHAT) =
        TransferSignal.detect(text, pkg)

    private fun assertTransfer(
        text: String,
        pkg: String? = WECHAT,
        // 默认按支出：TransferSignal.guessDirection 在没有「已退还/待确认」等
        // 明确口吻时也兜底成支出（主动转出比被动收款更常见，且用户可改）
        expectedDirection: Direction = Direction.EXPENSE,
    ) {
        val v = detect(text, pkg)
        assertTrue("应识别为转账，实际=$v，文本=$text", v is TransferSignal.Verdict.Transfer)
        v as TransferSignal.Verdict.Transfer
        assertEquals("方向不符（$text）", expectedDirection, v.direction)
    }

    private fun assertNotTransfer(text: String, pkg: String? = WECHAT) {
        val v = detect(text, pkg)
        assertTrue("不应识别为转账，实际=$v，文本=$text", v is TransferSignal.Verdict.NotTransfer)
    }

    // ── 真机确认的真实文案：必须识别 ──

    @Test
    fun wechatTransferAccepted() =
        assertTransfer("[转账]已被接收")

    @Test
    fun wechatTransferAwaitingConfirm() =
        assertTransfer("[转账]待朋友确认收款", expectedDirection = Direction.EXPENSE)

    @Test
    fun wechatTransferRefunded() =
        assertTransfer("[转账]已退还", expectedDirection = Direction.INCOME)

    /** 中文全角方括号也是微信的常用写法 */
    @Test
    fun fullWidthBracketsAlsoWork() =
        assertTransfer("【转账】已被接收")

    @Test
    fun alipayTransferSucceeded() =
        assertTransfer("你已成功转账给张三", ALIPAY)

    @Test
    fun alipayTransferIncoming() =
        assertTransfer("李四向你转账", ALIPAY)

    // ── ⚠️ 误报防护：这些绝不能识别 ──

    /** 验证码短信绝不能变成转账提醒 */
    @Test
    fun verificationCodeIsNotTransfer() =
        assertNotTransfer("[转账]验证码 8231，请勿泄露")

    @Test
    fun bankVerificationCodeIsNotTransfer() =
        assertNotTransfer("您正在转账，验证码 123456", "com.xxx.bank")

    /** 营销推送里也常出现「转账」二字 */
    @Test
    fun marketingIsNotTransfer() =
        assertNotTransfer("微信支付 转账免手续费，点击领取")

    @Test
    fun activityIsNotTransfer() =
        assertNotTransfer("转账抽奖活动开始啦，下载参与")

    /** 普通聊天消息 */
    @Test
    fun chatIsNotTransfer() =
        assertNotTransfer("张三：我转账给你了，你查一下")

    @Test
    fun groupChatIsNotTransfer() =
        assertNotTransfer("群聊(8) 转账的事情明天再说")

    /**
     * ⚠️ 最重要的一条：**带金额**的文本不走这条路。
     *
     * 如果通知里已经有金额，说明是「规则没覆盖到」而不是「通知没金额」，
     * 那种情况应该由用户加规则解决。若这里也识别成转账，
     * 用户会被要求重复输入一个通知里本来就有的金额。
     */
    @Test
    fun textWithAmountIsNotTransfer() =
        assertNotTransfer("[转账]已被接收 ¥50.00")

    @Test
    fun textWithYuanAmountIsNotTransfer() =
        assertNotTransfer("转账 50 元已被接收")

    @Test
    fun textWithDecimalAmountIsNotTransfer() =
        assertNotTransfer("转账成功 12.34")

    // ── 来源限制 ──

    /** 短信（packageName = null）不走这条路：银行短信有金额，且格式由短信规则负责 */
    @Test
    fun smsIsNotTransfer() =
        assertNotTransfer("[转账]已被接收", pkg = null)

    /** 其他 App 的通知不处理，避免误伤 */
    @Test
    fun otherPackageIsNotTransfer() =
        assertNotTransfer("[转账]已被接收", pkg = "com.example.other")

    // ── 边界 ──

    @Test
    fun blankTextIsNotTransfer() =
        assertNotTransfer("")

    @Test
    fun veryLongTextIsNotTransfer() =
        assertNotTransfer("[转账]已被接收" + "很长的广告内容".repeat(100))

    /**
     * 弱标记需要**两个**不同标记同时命中才判定。
     * 单独一个「转账」不算 —— 这正是 BuiltinRules 里踩过的
     * 「裸关键词误判」教训（「收款方」被当成收入）。
     */
    @Test
    fun singleWeakMarkerIsNotEnough() =
        assertNotTransfer("转账")

    @Test
    fun weakMarkerInNormalSentenceIsNotEnough() =
        assertNotTransfer("转账给朋友的时候记得备注")

    // ── 与「样本留档」的边界（回归测试）──

    /**
     * 转账通知会被 [com.moneyflow.app.parse.MoneyText.looksMoneyRelated]
     * 判为「和钱有关」（含「转账」二字）。
     *
     * 这曾经是个隐患：如果捕获层先走留档分支，转账通知会被记成
     * 一条 [UNMATCHED] 样本导出发给开发者 —— 而它**永远不可能**
     * 被规则匹配（通知里根本没有金额）。那会污染样本、误导排查。
     *
     * 这个用例锁定「转账通知确实同时满足两个判定」这个事实，
     * 提醒捕获层必须让两个分支互斥、且转账优先。
     */
    @Test
    fun transferNotificationAlsoLooksMoneyRelatedSoBranchesMustBeExclusive() {
        val text = "[转账]已被接收"
        assertTrue(
            "前提：转账通知确实会被判为「和钱有关」",
            com.moneyflow.app.parse.MoneyText.looksMoneyRelated(text),
        )
        assertTrue(
            "因此捕获层必须先判转账、再判留档",
            detect(text) is TransferSignal.Verdict.Transfer,
        )
    }
}

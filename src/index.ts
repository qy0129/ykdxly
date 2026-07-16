import { WeChatBot } from '@wechatbot/wechatbot'
import qrcode from 'qrcode-terminal'

const REPLIES: Record<string, string> = {
  '你好': '你好！我是 iLink 机器人，很高兴为你服务！',
  'hello': 'Hello! I am an iLink bot, nice to meet you!',
  '你是谁': '我是基于微信官方 iLink 协议开发的机器人助手。',
  '几点了': `现在的时间是 ${new Date().toLocaleTimeString('zh-CN')}`,
  '天气': '今天天气不错，适合写代码！',
  'help': '支持的关键词：你好, hello, 你是谁, 几点了, 天气, 再见, 谢谢',
  '再见': '再见！期待下次聊天！',
  '谢谢': '不客气，很高兴能帮到你！',
}

function getReply(text: string): string {
  const trimmed = text.trim().toLowerCase()
  for (const [key, reply] of Object.entries(REPLIES)) {
    if (trimmed.includes(key.toLowerCase())) {
      return reply
    }
  }
  return `收到你的消息了：${text}`
}

async function main() {
  try {
    console.log('正在启动 iLink 机器人...')
    const bot = new WeChatBot({
      storage: 'file',
      logLevel: 'debug',
    })

    console.log('正在登录...')
    const creds = await bot.login({
      callbacks: {
        onQrUrl: (url) => {
          console.log('\n请用微信扫描以下二维码登录：')
          qrcode.generate(url, { small: true })
          console.log('\n如果无法扫码，请在浏览器打开：')
          console.log(url)
        },
        onScanned: () => console.log('已扫码，请在手机上确认'),
        onExpired: () => console.log('二维码已过期，正在重新获取...'),
      },
    })
    console.log(`\n登录成功！Bot ID: ${creds.accountId}`)
    console.log(`用户 ID: ${creds.userId}`)

    bot.onMessage(async (msg) => {
      console.log(`\n[收到消息] 来自 ${msg.userId}: ${msg.text}`)
      try {
        await bot.sendTyping(msg.userId)
      } catch (e) {
        console.error('发送 typing 状态失败:', e)
      }
      try {
        const reply = getReply(msg.text)
        console.log(`[准备回复] ${reply}`)
        await bot.reply(msg, reply)
        console.log(`[回复成功] ${reply}`)
      } catch (e) {
        console.error('[回复失败]', e instanceof Error ? e.message : String(e))
      }
    })

    bot.on('poll:start', () => console.log('轮询已启动，等待消息中...'))
    bot.on('poll:stop', () => console.log('轮询已停止'))
    bot.on('session:expired', () => console.log('会话已过期，将自动重新登录'))
    bot.on('session:restored', (c) => console.log(`会话已恢复: ${c.accountId}`))
    bot.on('error', (err) => console.error('错误:', err instanceof Error ? err.message : String(err)))
    bot.on('close', () => console.log('机器人已关闭'))

    process.on('SIGINT', () => {
      console.log('\n正在关闭机器人...')
      bot.stop()
      process.exit(0)
    })
    process.on('SIGTERM', () => {
      console.log('\n正在关闭机器人...')
      bot.stop()
      process.exit(0)
    })

    console.log('\n机器人已启动，等待消息...')
    await bot.start()
  } catch (e) {
    console.error('启动失败:', e instanceof Error ? e.message : String(e))
    console.error(e)
    process.exit(1)
  }
}

main()

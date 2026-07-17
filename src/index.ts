//引入依赖
import { WeChatBot } from '@wechatbot/wechatbot'//ilinksdk主类
import { GoogleGenerativeAI } from '@google/generative-ai'
import qrcode from 'qrcode-terminal'//生成二维码
import 'dotenv/config'//读取.evn文件的配置（APIKey)

const geminiApiKey = process.env.GEMINI_API_KEY
const ollamaBaseUrl = process.env.OLLAMA_BASE_URL || 'http://127.0.0.1:11434'
const ollamaModel = process.env.OLLAMA_MODEL || 'gemma3:4b'//用ollama3：4b模型
//调用googlegeminiAPI,但是没配APIKey，所以这段不执行
async function askGemini(text: string, media?: { data: Buffer, mime: string }): Promise<string> {
  const genAI = new GoogleGenerativeAI(geminiApiKey!)
  const model = genAI.getGenerativeModel({ model: 'gemini-2.0-flash-lite' })
  const parts: any[] = [{ text }]
  if (media) {
    parts.push({
      inlineData: {
        mimeType: media.mime,
        data: media.data.toString('base64'),
      },
    })
  }
  const result = await model.generateContent(parts)
  return result.response.text()
}
//ollama的调用
async function askOllama(text: string, media?: { data: Buffer, mime: string }): Promise<string> {
  const body: Record<string, any> = {
    model: ollamaModel,
    prompt: text,
    stream: false,
    options: { num_predict: 512 },
  }
  if (media && media.mime.startsWith('image/')) {
    body.images = [media.data.toString('base64')]
  }
  for (let i = 0; i < 3; i++) {
    try {
      const controller = new AbortController()
      const timer = setTimeout(() => controller.abort(), 120000)
      const res = await fetch(`${ollamaBaseUrl}/api/generate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: controller.signal,
      })
      clearTimeout(timer)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data: any = await res.json()
      if (data.response) return data.response
    } catch (e: any) {
      if (i < 2) {
        console.log(`Ollama 重试 ${i + 1}/3: ${e?.message || e}`)
        await new Promise(r => setTimeout(r, 2000))
      } else {
        throw e
      }
    }
  }
  return ''
}
//固定回复
function getFixedReply(text: string): string {
  const REPLIES: Record<string, string> = {
    '你好': '你好！我是 iLink 机器人，很高兴为你服务！',
    'hello': 'Hello! I am an iLink bot, nice to meet you!',
    '你是谁': '我是基于微信官方 iLink 协议开发的机器人助手。',
    '天气': '今天天气不错，适合写代码！',
    'help': '支持的关键词：你好, hello, 你是谁, 天气, 再见, 谢谢',
    '再见': '再见！期待下次聊天！',
    '谢谢': '不客气，很高兴能帮到你！',
  }
  const trimmed = text.trim().toLowerCase()
  for (const [key, reply] of Object.entries(REPLIES)) {
    if (trimmed.includes(key.toLowerCase())) return reply
  }
  return ''
}
//回复来由
async function getReply(text: string, media?: { data: Buffer, mime: string }): Promise<string> {
  if (geminiApiKey) {
    try {
      const reply = await askGemini(text, media)
      if (reply) return reply
    } catch (e) {
      console.error('Gemini API 调用失败:', e)
    }
  }
  try {
    const reply = await askOllama(text, media)
    if (reply) return reply
  } catch (e: any) {
    console.log(`Ollama 调用失败: ${e?.message || e}`)
  }
  const fixed = getFixedReply(text)
  if (fixed) return fixed
  return `收到你的消息了：${text}`
}
//主函数启动
async function main() {
  try {
    console.log('正在启动 iLink 机器人...')

    if (geminiApiKey) {
      console.log('LLM: Google Gemini (gemini-2.0-flash-lite)')
      console.log('支持: 文字对话 / 图片识别 / 语音识别')
    } else {
      console.log(`LLM: Ollama (${ollamaModel}) — ${ollamaBaseUrl}`)
      try {
        const res = await fetch(`${ollamaBaseUrl}/api/tags`)
        const data: any = await res.json()
        const models = data.models?.map((m: any) => m.name).join(', ') || '无'
        console.log(`已安装模型: ${models}`)
      } catch {
        console.log('⚠ Ollama 未运行！请先启动 Ollama')
        console.log('   启动命令: ollama serve')
      }
      console.log('支持: 文字对话 / 图片识别 (需模型支持)')
      console.log('提示: 如需语音识别，配置 GEMINI_API_KEY')
    }

    const bot = new WeChatBot({
      storage: 'file',//登录文件凭证，下次免扫码
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
//消息处理
    bot.onMessage(async (msg) => {
      try {
        if (msg.type === 'image' && msg.images[0]) {
          console.log(`\n[收到图片] 来自 ${msg.userId}`)//从微信CDN下载图片
          const media = await bot.download(msg)
          if (media) {
            console.log(`[图片已下载] 类型: ${media.type}, 大小: ${media.data.length} bytes`)
            const reply = await getReply('请描述这张图片', { data: media.data, mime: 'image/jpeg' })
            console.log(`[AI回复] ${reply}`)
            await bot.sendTyping(msg.userId)
            await bot.reply(msg, reply)
            return
          }
        }
//处理语音消息
        if (msg.type === 'voice' && msg.voices[0]) {
          const voiceText = msg.voices[0].text || msg.text//微信自带语音识别
          if (voiceText && voiceText !== '[voice]') {
            console.log(`\n[收到语音] 来自 ${msg.userId}, 识别文字: ${voiceText}`)//下载语音
            const reply = await getReply(voiceText)
            console.log(`[AI回复] ${reply}`)
            await bot.sendTyping(msg.userId)
            await bot.reply(msg, reply)
            return
          }
          console.log(`\n[收到语音] 来自 ${msg.userId}（无可识别文字，尝试音频分析）`)
          const media = await bot.download(msg)
          if (media) {
            console.log(`[语音已下载] 格式: ${media.format}, 大小: ${media.data.length} bytes`)
            const mime = media.format === 'wav' ? 'audio/wav' : 'audio/silk'
            const reply = await getReply('请识别这段语音的内容并回复', { data: media.data, mime })
            console.log(`[AI回复] ${reply}`)
            await bot.sendTyping(msg.userId)
            await bot.reply(msg, reply)
            return
          }
        }

        console.log(`\n[收到消息] 来自 ${msg.userId}: ${msg.text}`)
        const reply = await getReply(msg.text)
        console.log(`[AI回复] ${reply}`)
        await bot.sendTyping(msg.userId)
        await bot.reply(msg, reply)
      } catch (e) {
        console.error('[处理消息失败]', e instanceof Error ? e.message : String(e))
        try {
          await bot.reply(msg, '抱歉，处理消息时出了点问题。')
        } catch { }
      }
    })
//事件监听和生命周期
    bot.on('poll:start', () => console.log('轮询已启动，等待消息中...'))
    bot.on('poll:stop', () => console.log('轮询已停止'))
    bot.on('session:expired', () => console.log('会话已过期，将自动重新登录'))
    bot.on('session:restored', (c) => console.log(`会话已恢复: ${c.accountId}`))
    bot.on('error', (err) => console.error('错误:', err instanceof Error ? err.message : String(err)))
    bot.on('close', () => console.log('机器人已关闭'))

    process.on('SIGINT', () => { console.log('\n正在关闭机器人...'); bot.stop(); process.exit(0) })
    process.on('SIGTERM', () => { console.log('\n正在关闭机器人...'); bot.stop(); process.exit(0) })

    console.log('\n机器人已启动，等待消息...')
    await bot.start()
  } catch (e) {
    console.error('启动失败:', e instanceof Error ? e.message : String(e))
    process.exit(1)
  }
}

main()

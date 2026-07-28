---
name: media
description: "Use when working on audio, images, video, voice processing, media files. Triggers: 音频, 图片, 视频, 语音, 音乐, 拍照, 录音, media, audio, image, video, voice, music."
---

# Media Skill

## 本模块职责

处理音频/图片/视频/语音的生成、转换、识别，属于 `capabilities/audio`、`capabilities/image`、`capabilities/media` 目录。

## 负责人

D 成员

## 可用能力

- 语音识别（Vosk）
- TTS 语音合成（CosyVoice）
- 图片处理与生成
- 音频格式转换
- 视频处理

## 代码位置

```
src/main/java/com/example/ilink/capabilities/audio/
src/main/java/com/example/ilink/capabilities/image/
src/main/java/com/example/ilink/capabilities/media/
src/test/java/com/example/ilink/capabilities/audio/
src/test/java/com/example/ilink/capabilities/image/
src/test/java/com/example/ilink/capabilities/media/
```

## 依赖关系

```
adapter → application → media → platform
                          ↓
                    Vosk (语音识别)
                    CosyVoice (TTS)
                    ZXing (二维码)
```

## TTS 配置

```properties
# config.properties
tts.voice.default=FunAudioLLM/CosyVoice2-0.5B:claire
tts.voice.boy=FunAudioLLM/CosyVoice2-0.5B:david
tts.voice.girl=FunAudioLLM/CosyVoice2-0.5B:diana
tts.voice.male=FunAudioLLM/CosyVoice2-0.5B:charles
tts.voice.female=FunAudioLLM/CosyVoice2-0.5B:anna
```

## 开发规范

1. 新增功能优先新增独立类
2. 大文件要流式处理
3. 多媒体操作要有超时控制
4. 新增业务逻辑必须增加对应测试
5. 测试使用小型测试文件

## 测试要求

- 测试名称应体现行为，如 `convertAudioToText()`
- 覆盖正常流程、失败流程和关键边界条件
- 合并前必须通过 `.\mvnw.cmd test`

## 共享热点文件

修改以下文件前必须先沟通：
- `UserRequestHandler.java`
- `IntentRecognizer.java`

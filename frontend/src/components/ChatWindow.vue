<template>
  <main class="chat-area">
    <!-- Messages -->
    <div class="messages-container" ref="msgContainer">
      <div v-if="!sessionId" class="welcome">
        <h2>AI 聊天智能体</h2>
        <p>选择一个会话或创建新对话开始聊天</p>
      </div>

      <MessageBubble
        v-for="(msg, i) in messages"
        :key="i"
        :role="msg.role"
        :content="msg.content"
        :streaming="msg.streaming"
        :citations="msg.citations"
      />

      <div v-if="loading && messages.length === 0" class="loading">
        思考中...
      </div>

      <div ref="bottomAnchor"></div>
    </div>

    <!-- Input -->
    <div class="input-area" v-if="sessionId">
      <div class="input-row">
        <textarea
          v-model="input"
          class="chat-input"
          :placeholder="loading ? 'AI 回复中，可先输入下一条...' : '输入消息...'"
          rows="1"
          @keydown.enter.exact.prevent="send"
          @input="autoResize"
          ref="textInput"
        ></textarea>
        <button
          class="send-btn"
          @click="send"
          :disabled="!input.trim() || loading"
        >
          发送
        </button>
      </div>
    </div>
  </main>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import MessageBubble from './MessageBubble.vue'

const props = defineProps({
  sessionId: { type: String, default: null },
  messages: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
})

const emit = defineEmits(['send'])

const input = ref('')
const textInput = ref(null)
const msgContainer = ref(null)
const bottomAnchor = ref(null)

async function scrollToBottom() {
  await nextTick()
  // Scroll only the messages container itself. scrollIntoView would also
  // scroll every scrollable ancestor (e.g. the sidebar session list).
  const el = msgContainer.value
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

// New message appended
watch(() => props.messages.length, scrollToBottom)

// Content of the last message grows during streaming (length alone doesn't change)
watch(() => {
  const msgs = props.messages
  return msgs.length ? msgs[msgs.length - 1].content : ''
}, scrollToBottom)

watch(() => props.sessionId, () => {
  input.value = ''
  nextTick(() => textInput.value?.focus())
})

function send() {
  const msg = input.value.trim()
  if (!msg || props.loading) return
  emit('send', msg)
  input.value = ''
  nextTick(() => {
    autoResize()
    textInput.value?.focus()
  })
}

function autoResize() {
  const el = textInput.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}
</script>

<style scoped>
.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  /* flex items default to min-height: auto — without this, long message
     content stretches the area past the viewport and pushes the input box
     off-screen. min-height: 0 allows shrinking; .messages-container
     (overflow-y: auto) then scrolls instead. */
  min-height: 0;
}

.messages-container {
  flex: 1;
  overflow-y: auto;
}

.welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #8e8ea0;
}

.welcome h2 {
  font-size: 28px;
  margin-bottom: 8px;
}

.loading {
  padding: 20px 16%;
  color: #8e8ea0;
}

.input-area {
  padding: 16px 16% 24px;
  background: #343541;
  border-top: 1px solid #4d4d4f;
}

.input-row {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.chat-input {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #565869;
  border-radius: 10px;
  background: #40414f;
  color: #ececf1;
  font-size: 15px;
  line-height: 1.5;
  resize: none;
  outline: none;
  font-family: inherit;
  max-height: 200px;
}

.chat-input:focus {
  border-color: #19c37d;
  box-shadow: 0 0 0 1px #19c37d;
}

.chat-input::placeholder {
  color: #8e8ea0;
}

.send-btn {
  padding: 10px 20px;
  background: #19c37d;
  color: #fff;
  border: none;
  border-radius: 10px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s;
  flex-shrink: 0;
}

.send-btn:hover:not(:disabled) {
  background: #15a76b;
}

.send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
